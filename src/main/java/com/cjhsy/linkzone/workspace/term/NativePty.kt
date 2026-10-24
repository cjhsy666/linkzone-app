package com.cjhsy.linkzone.workspace.term

/**
 * 原生 pty 绑定。Java 层拿不到伪终端，只能靠 forkpty 在原生层创建，
 * 这样才能获得真正的行缓冲/信号/窗口尺寸语义（vim、top、python REPL 都依赖它）。
 */
internal object NativePty {

    init {
        System.loadLibrary("lzpty")
    }

    /** @return int[]{masterFd, pid}，失败为 null */
    external fun nativeStart(
        argv: Array<String>,
        cwd: String,
        env: Array<String>,
        rows: Int,
        cols: Int,
    ): IntArray?

    external fun nativeSetSize(fd: Int, rows: Int, cols: Int)
    external fun nativePoll(pid: Int): Int
    external fun nativeSignal(pid: Int, sig: Int)
}
