package com.cjhsy.linkzone.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * 消息朗读播放器（单例）：AI 消息操作栏「朗读」按钮的后端。
 *
 * 设计动机：TTS 做成模型工具不可靠——模型未必调用、多一轮工具往返；改为 UI 直读，
 * 用户点哪条读哪条。复用单个 TextToSpeech 实例（引擎首次创建后常驻），
 * 同一条再点=停止，点别的消息=打断当前切换；长文本按引擎单次输入上限分段排队。
 */
object TtsPlayer {

    /** 引擎单次可朗读的最大字符数（系统上限 4000，留余量） */
    private const val MAX_CHUNK = 3800

    /** 当前朗读的消息 key（消息 id）；null = 空闲。含初始化/排队中 */
    var currentKey by mutableStateOf<String?>(null)
        private set

    /** 引擎是否正在发声（开始朗读后为 true，读完/停止/出错复位） */
    var speaking by mutableStateOf(false)
        private set

    private var tts: TextToSpeech? = null
    private var initOk = false

    /** 引擎初始化完成前的待播分段（首装设备引擎异步初始化，秒级） */
    private var awaitingInit: List<String>? = null

    /** 未读完的 utterance id 集合；TTS 回调在 binder 线程，用锁同步 */
    private val pendingIds = mutableSetOf<String>()

    /** 点按切换：同 key 再点=停止；不同 key=打断当前并切换 */
    fun toggle(context: Context, key: String, rawText: String) {
        if (currentKey == key) {
            stop()
            return
        }
        stop()
        val chunks = speakableChunks(rawText)
        if (chunks.isEmpty()) return // 没有可读文本（如纯代码），不进入播放态
        currentKey = key
        val engine = ensureEngine(context)
        if (engine == null) {
            awaitingInit = chunks
            return
        }
        enqueue(engine, chunks)
    }

    /** 停止朗读并复位状态 */
    fun stop() {
        synchronized(pendingIds) { pendingIds.clear() }
        awaitingInit = null
        runCatching { tts?.stop() }
        currentKey = null
        speaking = false
    }

    /** 懒创建引擎；未初始化完成时返回 null（由回调续播 [awaitingInit]） */
    private fun ensureEngine(context: Context): TextToSpeech? {
        tts?.let { return if (initOk) it else null }
        // 回调里不能引用自身初始化语句中的局部 val，故经实例字段 tts 取实例
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                initOk = true
                runCatching { engine.setLanguage(Locale.getDefault()) }
                engine.setOnUtteranceProgressListener(listener)
                val chunks = awaitingInit
                awaitingInit = null
                if (chunks != null) enqueue(engine, chunks)
            } else {
                initOk = false
                runCatching { engine?.shutdown() }
                tts = null
                reset()
            }
        }
        return null
    }

    private fun enqueue(engine: TextToSpeech, chunks: List<String>) {
        synchronized(pendingIds) {
            pendingIds.clear()
            chunks.forEachIndexed { i, chunk -> pendingIds += "lz-$i" }
        }
        speaking = false
        chunks.forEachIndexed { i, chunk ->
            val ok = runCatching {
                engine.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "lz-$i")
            }.getOrDefault(TextToSpeech.ERROR)
            if (ok != TextToSpeech.SUCCESS) finishChunk("lz-$i") // 入队失败的段不会再回调，手动核销
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            speaking = true
        }

        override fun onDone(utteranceId: String?) = finishChunk(utteranceId)

        override fun onError(utteranceId: String?) = finishChunk(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?, errorCode: Int) = finishChunk(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = finishChunk(utteranceId)
    }

    /** 核销一段；全部读完时复位（仅清当前朗读，不影响切换后的新任务） */
    private fun finishChunk(utteranceId: String?) {
        if (utteranceId == null) return
        val allDone = synchronized(pendingIds) {
            pendingIds -= utteranceId
            pendingIds.isEmpty()
        }
        if (allDone) reset()
    }

    private fun reset() {
        speaking = false
        currentKey = null
    }

    /**
     * 提取可朗读文本：去掉朗读无意义的 markdown 语法噪音
     * （围栏代码块、图片/链接 URL、标题/引用/强调标记、表格分隔线），
     * 再按 [MAX_CHUNK] 优先在换行/句读处分段
     */
    private fun speakableChunks(raw: String): List<String> {
        val plain = raw
            .replace(Regex("```[\\s\\S]*?```"), " 代码块省略 ")
            .replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1 图片链接")
            .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("^>\\s?", RegexOption.MULTILINE), "")
            .replace(Regex("[*_~`]{1,3}"), "")
            .replace(Regex("\\{\\{[^}]*}}"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (plain.isBlank()) return emptyList()
        val chunks = mutableListOf<String>()
        var rest = plain
        while (rest.length > MAX_CHUNK) {
            var cut = rest.lastIndexOf('\n', MAX_CHUNK - 1)
            if (cut < MAX_CHUNK / 2) {
                cut = maxOf(
                    rest.lastIndexOf('。', MAX_CHUNK - 1),
                    rest.lastIndexOf('？', MAX_CHUNK - 1),
                    rest.lastIndexOf('！', MAX_CHUNK - 1)
                )
            }
            if (cut < MAX_CHUNK / 2) cut = MAX_CHUNK
            chunks += rest.take(cut).trim()
            rest = rest.drop(cut).trim()
        }
        if (rest.isNotBlank()) chunks += rest
        return chunks
    }
}
