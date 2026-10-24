package com.cjhsy.linkzone.workspace.term

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 一条交互式终端会话：forkpty 出来的 shell，持续可读写。
 * 与「一条命令一个进程」的一次性执行不同，这里保留了 cd/export/历史等状态，
 * 输出实时流入 [TerminalEmulator]，按键即时写入 pty。
 */
class PtySession(
    private val argv: List<String>,
    private val cwd: File,
    private val env: List<String>,
    initialCols: Int = 80,
    initialRows: Int = 24,
) {

    private val emulator = TerminalEmulator(initialCols, initialRows)
    private val _screen = MutableStateFlow(emulator.snapshot())
    val screen: StateFlow<TermScreen> = _screen.asStateFlow()

    /** 子进程退出码；null 表示仍在运行 */
    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private var pfd: ParcelFileDescriptor? = null
    private var output: FileOutputStream? = null
    private var reader: Thread? = null
    private var pid = -1
    private var fd = -1
    private var lastPublish = 0L
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start(): Boolean {
        if (running.get()) return true
        cwd.mkdirs()
        // 原生层异常（如符号缺失）降级为启动失败提示，不闪退
        val res = runCatching {
            NativePty.nativeStart(
                argv = argv.toTypedArray(),
                cwd = cwd.absolutePath,
                env = env.toTypedArray(),
                rows = emulator.rows,
                cols = emulator.cols,
            )
        }.getOrNull() ?: return false
        fd = res[0]
        pid = res[1]
        val descriptor = ParcelFileDescriptor.adoptFd(fd)
        pfd = descriptor
        output = FileOutputStream(descriptor.fileDescriptor)
        running.set(true)
        reader = Thread({ readLoop(descriptor) }, "lz-pty-${pid}").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun readLoop(descriptor: ParcelFileDescriptor) {
        val buffer = ByteArray(8192)
        val pending = java.io.ByteArrayOutputStream()
        try {
            FileInputStream(descriptor.fileDescriptor).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    for (i in 0 until n) pending.write(buffer[i].toInt())
                    val chunk = pending.toByteArray()
                    // 只在字符边界处解码，避免多字节字符被读缓冲切断产生乱码
                    val tail = trailingIncomplete(chunk)
                    val cut = if (tail > 0) chunk.size - tail else chunk.size
                    if (cut > 0) {
                        emulator.feed(String(chunk, 0, cut, Charsets.UTF_8))
                        pending.reset()
                        if (cut < chunk.size) pending.write(chunk, cut, chunk.size - cut)
                    }
                    publish(false)
                }
            }
        } catch (_: Throwable) {
            // fd 被关闭时读线程会抛异常，属正常退出路径
        } finally {
            running.set(false)
            publish(true)
            val code = runCatching { NativePty.nativePoll(pid) }.getOrDefault(-1)
            _exitCode.value = if (code >= 0) code else 0
        }
    }

    /** 写入按键/文本（已含控制字符） */
    fun write(text: CharSequence) {
        val stream = output ?: return
        runCatching {
            stream.write(text.toString().toByteArray(Charsets.UTF_8))
            stream.flush()
        }
    }

    /** 回车类操作发送 CR（pty 的规范模式要求 \r，\n 不会触发执行） */
    fun submit(line: String) {
        write(line)
        write("\r")
    }

    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        if (cols == emulator.cols && rows == emulator.rows) return
        emulator.resize(cols, rows)
        if (fd >= 0) runCatching { NativePty.nativeSetSize(fd, rows, cols) }
        publish(true)
    }

    fun interrupt() {
        if (pid > 0) runCatching { NativePty.nativeSignal(pid, 2) }
    }

    fun clear() {
        emulator.reset()
        publish(true)
    }

    fun close() {
        running.set(false)
        runCatching { output?.close() }
        runCatching { pfd?.close() }
        output = null
        pfd = null
        reader?.interrupt()
        if (pid > 0) runCatching { NativePty.nativeSignal(pid, 9) }
    }

    private fun publish(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < 60) return
        lastPublish = now
        _screen.value = emulator.snapshot()
    }

    /** 末尾有多少字节属于「被截断的多字节字符」 */
    private fun trailingIncomplete(data: ByteArray): Int {
        var i = data.size - 1
        var need = 0
        var count = 0
        while (i >= 0 && count < 4) {
            val b = data[i].toInt() and 0xFF
            if (b and 0xC0 == 0x80) { count++; i--; continue }
            need = when {
                b and 0x80 == 0 -> 1
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            break
        }
        if (need == 0) return 0
        val have = count + 1
        return if (have < need) have else 0
    }
}
