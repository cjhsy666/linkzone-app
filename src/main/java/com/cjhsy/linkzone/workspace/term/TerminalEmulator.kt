package com.cjhsy.linkzone.workspace.term

/**
 * 终端单元格。颜色编码：
 *  -1         默认色
 *  0..255     256 色调色板索引
 *  0x1000000 + (r<<16|g<<8|b)  真彩色
 */
data class TermCell(
    val ch: Char = ' ',
    val fg: Int = -1,
    val bg: Int = -1,
    val bold: Boolean = false,
    val reverse: Boolean = false,
)

/** 渲染用快照，避免 Compose 读到半更新的缓冲 */
data class TermScreen(
    val cols: Int,
    val rows: Int,
    val lines: List<List<TermCell>>,
    val cursorX: Int,
    val cursorY: Int,
    val cursorVisible: Boolean,
)

/**
 * 极简 VT100/xterm 模拟器：够跑 shell、ls、htop、vim 这类程序。
 * 支持：CUP/CUU/CUD/CUF/CUB/CHA/VPA、ED/EL/ECH、IL/DL/ICH/DCH、
 * SGR（含 256 色与真彩色、反显、粗体）、滚动区、备用屏、自动换行。
 */
class TerminalEmulator(cols: Int, rows: Int) {

    var cols = cols
        private set
    var rows = rows
        private set

    private var grid: Array<Array<TermCell>> = newGrid(rows, cols)
    private val history = ArrayDeque<Array<TermCell>>()

    var cursorX = 0
        private set
    var cursorY = 0
        private set
    private var cursorVisible = true

    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var autoWrap = true
    private var pendingWrap = false

    private var fg = -1
    private var bg = -1
    private var bold = false
    private var reverse = false

    private var savedX = 0
    private var savedY = 0
    private var savedFg = -1
    private var savedBg = -1

    private var altScreen = false
    private var primaryGrid: Array<Array<TermCell>>? = null

    // 解析器状态
    private var state = 0 // 0=ground 1=esc 2=csi 3=osc 4=charset
    private val csi = StringBuilder()
    private var csiPrivate = false
    private var osc = StringBuilder()

    fun snapshot(): TermScreen {
        val lines = ArrayList<List<TermCell>>(rows)
        for (y in 0 until rows) {
            lines.add(grid[y].toList())
        }
        return TermScreen(cols, rows, lines, cursorX, cursorY, cursorVisible)
    }

    /** 可回滚的历史行数 */
    val scrollbackSize: Int get() = history.size

    /** 历史滚动行（供后续做回滚用） */
    fun scrollback(max: Int = 200): List<List<TermCell>> =
        history.takeLast(max).map { it.toList() }

    /**
     * 某个滚动位置的可视窗口。offset 表示「从底部往上翻了 N 行」，0 即实时屏幕。
     * 翻页状态下不显示光标（与真实终端一致）。
     */
    fun viewport(offset: Int): TermScreen {
        if (offset <= 0 || altScreen) return snapshot()
        val back = offset.coerceAtMost(history.size)
        val lines = ArrayList<List<TermCell>>(back + rows)
        history.takeLast(back).forEach { lines.add(it.toList()) }
        for (y in 0 until rows) lines.add(grid[y].toList())
        val end = lines.size - back
        val start = (end - rows).coerceAtLeast(0)
        return TermScreen(cols, rows, lines.subList(start, end), cursorX, cursorY, false)
    }

    // ---------------- 尺寸 ----------------

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        val next = newGrid(newRows, newCols)
        for (y in 0 until minOf(rows, newRows)) {
            for (x in 0 until minOf(cols, newCols)) {
                next[y][x] = grid[y][x]
            }
        }
        grid = next
        cols = newCols
        rows = newRows
        scrollTop = 0
        scrollBottom = rows - 1
        cursorX = cursorX.coerceIn(0, cols - 1)
        cursorY = cursorY.coerceIn(0, rows - 1)
        pendingWrap = false
    }

    fun reset() {
        grid = newGrid(rows, cols)
        history.clear()
        cursorX = 0
        cursorY = 0
        scrollTop = 0
        scrollBottom = rows - 1
        fg = -1; bg = -1; bold = false; reverse = false
        pendingWrap = false
    }

    // ---------------- 输出解析 ----------------

    fun feed(text: CharSequence) {
        text.forEach { c -> consume(c) }
    }

    private fun consume(c: Char) {
        when (state) {
            0 -> when (c) {
                '\u001b' -> state = 1
                '\r' -> { cursorX = 0; pendingWrap = false }
                '\n', '\u000b', '\u000c' -> lineFeed()
                '\b' -> { if (cursorX > 0) cursorX--; pendingWrap = false }
                '\t' -> { cursorX = minOf(cols - 1, (cursorX / 8 + 1) * 8); pendingWrap = false }
                '\u0007' -> Unit
                '\u000e', '\u000f', '\u0000' -> Unit
                else -> putChar(c)
            }
            1 -> parseEscape(c)
            2 -> parseCsi(c)
            3 -> parseOsc(c)
            4 -> state = 0 // 字符集切换，忽略一个字符
            5 -> if (c == '\\') state = 0 else state = 3 // OSC 的 ESC 终止符
        }
    }

    private fun parseEscape(c: Char) {
        when (c) {
            '[' -> { state = 2; csi.setLength(0); csiPrivate = false }
            ']' -> { state = 3; osc.setLength(0) }
            '(' , ')', '*', '+' -> state = 4
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'M' -> reverseIndex()
            'D' -> lineFeed()
            'E' -> { cursorX = 0; lineFeed() }
            '=' , '>' -> state = 0
            else -> state = 0
        }
    }

    private fun parseCsi(c: Char) {
        when {
            c == '?' -> {
                csiPrivate = true
                state = 2
            }
            c in '0'..'9' || c == ';' || c == ':' || c == '<' -> {
                csi.append(c)
                state = 2
            }
            c == ' ' -> state = 2
            else -> {
                state = 0
                applyCsi(c)
            }
        }
    }

    private fun parseOsc(c: Char) {
        // OSC 以 BEL 或 ESC\ 结束
        if (c == '\u0007') {
            state = 0
            return
        }
        if (c == '\u001b') {
            state = 5
            return
        }
        osc.append(c)
    }

    private fun applyCsi(final: Char) {
        val nums = parseParams()
        fun p(index: Int, default: Int = 1): Int = nums.getOrNull(index)?.takeIf { it > 0 } ?: default

        when (final) {
            'm' -> applySgr(nums)
            'H', 'f' -> {
                cursorY = (p(0) - 1).coerceIn(0, rows - 1)
                cursorX = (p(1) - 1).coerceIn(0, cols - 1)
                pendingWrap = false
            }
            'A' -> cursorY = (cursorY - p(0)).coerceAtLeast(0)
            'B' -> cursorY = (cursorY + p(0)).coerceAtMost(rows - 1)
            'C' -> cursorX = (cursorX + p(0)).coerceAtMost(cols - 1)
            'D' -> cursorX = (cursorX - p(0)).coerceAtLeast(0)
            'E' -> { cursorY = (cursorY + p(0)).coerceAtMost(rows - 1); cursorX = 0 }
            'F' -> { cursorY = (cursorY - p(0)).coerceAtLeast(0); cursorX = 0 }
            'G' -> cursorX = (p(0) - 1).coerceIn(0, cols - 1)
            'd' -> cursorY = (p(0) - 1).coerceIn(0, rows - 1)
            'J' -> eraseDisplay(nums.getOrNull(0) ?: 0)
            'K' -> eraseLine(nums.getOrNull(0) ?: 0)
            'X' -> eraseChars(p(0))
            'L' -> insertLines(p(0))
            'M' -> deleteLines(p(0))
            '@' -> insertChars(p(0))
            'P' -> deleteChars(p(0))
            'r' -> {
                scrollTop = (p(0) - 1).coerceIn(0, rows - 1)
                scrollBottom = (p(1, rows) - 1).coerceIn(scrollTop, rows - 1)
                cursorX = 0; cursorY = scrollTop
            }
            'h' -> if (csiPrivate) setMode(nums, true)
            'l' -> if (csiPrivate) setMode(nums, false)
            's' -> saveCursor()
            'u' -> restoreCursor()
            else -> Unit
        }
        pendingWrap = false
    }

    private fun parseParams(): List<Int> {
        if (csi.isEmpty()) return emptyList()
        return csi.toString().split(';').map { it.substringBefore(':').toIntOrNull() ?: 0 }
    }

    private fun setMode(nums: List<Int>, on: Boolean) {
        nums.forEach { mode ->
            when (mode) {
                25 -> cursorVisible = on
                7 -> autoWrap = on
                47, 1047, 1049 -> {
                    if (on && !altScreen) {
                        primaryGrid = grid
                        grid = newGrid(rows, cols)
                        altScreen = true
                        if (mode == 1049) { saveCursor(); cursorX = 0; cursorY = 0 }
                    } else if (!on && altScreen) {
                        grid = primaryGrid ?: newGrid(rows, cols)
                        primaryGrid = null
                        altScreen = false
                    }
                }
            }
        }
    }

    // ---------------- 编辑操作 ----------------

    private fun putChar(c: Char) {
        if (cursorX >= cols) { cursorX = cols - 1 }
        if (pendingWrap && autoWrap) { cursorX = 0; lineFeed() }
        grid[cursorY][cursorX] = TermCell(c, fg, bg, bold, reverse)
        pendingWrap = false
        if (cursorX >= cols - 1) {
            if (autoWrap) pendingWrap = true else cursorX = cols - 1
        } else {
            cursorX++
        }
    }

    private fun lineFeed() {
        if (cursorY == scrollBottom) scrollUp(1) else if (cursorY < rows - 1) cursorY++
        pendingWrap = false
    }

    private fun reverseIndex() {
        if (cursorY == scrollTop) scrollDown(1) else if (cursorY > 0) cursorY--
    }

    private fun scrollUp(count: Int) {
        repeat(count.coerceAtMost(scrollBottom - scrollTop + 1)) {
            if (!altScreen) history.addLast(grid[scrollTop].copyOf())
            while (history.size > 500) history.removeFirst()
            for (y in scrollTop until scrollBottom) grid[y] = grid[y + 1]
            grid[scrollBottom] = Array(cols) { TermCell() }
        }
    }

    private fun scrollDown(count: Int) {
        repeat(count.coerceAtMost(scrollBottom - scrollTop + 1)) {
            for (y in scrollBottom downTo scrollTop + 1) grid[y] = grid[y - 1]
            grid[scrollTop] = Array(cols) { TermCell() }
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseLine(0); for (y in cursorY + 1 until rows) grid[y] = Array(cols) { TermCell() } }
            1 -> { eraseLine(1); for (y in 0 until cursorY) grid[y] = Array(cols) { TermCell() } }
            2, 3 -> { for (y in 0 until rows) grid[y] = Array(cols) { TermCell() } }
        }
    }

    private fun eraseLine(mode: Int) {
        val row = grid[cursorY]
        when (mode) {
            0 -> for (x in cursorX until cols) row[x] = TermCell()
            1 -> for (x in 0..cursorX) row[x] = TermCell()
            2 -> for (x in 0 until cols) row[x] = TermCell()
        }
    }

    private fun eraseChars(count: Int) {
        val row = grid[cursorY]
        for (x in cursorX until minOf(cols, cursorX + count)) row[x] = TermCell()
    }

    private fun insertLines(count: Int) {
        repeat(count) {
            for (y in scrollBottom downTo cursorY + 1) grid[y] = grid[y - 1]
            grid[cursorY] = Array(cols) { TermCell() }
        }
    }

    private fun deleteLines(count: Int) {
        repeat(count) {
            for (y in cursorY until scrollBottom) grid[y] = grid[y + 1]
            grid[scrollBottom] = Array(cols) { TermCell() }
        }
    }

    private fun insertChars(count: Int) {
        val row = grid[cursorY]
        for (x in cols - 1 downTo cursorX + count) row[x] = row[x - count]
        for (x in cursorX until minOf(cols, cursorX + count)) row[x] = TermCell()
    }

    private fun deleteChars(count: Int) {
        val row = grid[cursorY]
        for (x in cursorX until cols - count) row[x] = row[x + count]
        for (x in maxOf(cursorX, cols - count) until cols) row[x] = TermCell()
    }

    private fun saveCursor() {
        savedX = cursorX; savedY = cursorY
        savedFg = fg; savedBg = bg
    }

    private fun restoreCursor() {
        cursorX = savedX.coerceIn(0, cols - 1)
        cursorY = savedY.coerceIn(0, rows - 1)
        fg = savedFg; bg = savedBg
    }

    // ---------------- SGR ----------------

    private fun applySgr(nums: List<Int>) {
        if (nums.isEmpty()) { resetAttrs(); return }
        var i = 0
        while (i < nums.size) {
            when (val n = nums[i]) {
                0 -> resetAttrs()
                1 -> bold = true
                22 -> bold = false
                7 -> reverse = true
                27 -> reverse = false
                39 -> fg = -1
                49 -> bg = -1
                in 30..37 -> fg = n - 30
                in 90..97 -> fg = n - 90 + 8
                in 40..47 -> bg = n - 40
                in 100..107 -> bg = n - 100 + 8
                38, 48 -> {
                    val target = if (n == 38) 1 else 2
                    when (nums.getOrNull(i + 1)) {
                        5 -> {
                            val idx = nums.getOrNull(i + 2) ?: 0
                            if (target == 1) fg = idx else bg = idx
                            i += 2
                        }
                        2 -> {
                            val r = nums.getOrNull(i + 2) ?: 0
                            val g = nums.getOrNull(i + 3) ?: 0
                            val b = nums.getOrNull(i + 4) ?: 0
                            val color = 0x1000000 or (r shl 16) or (g shl 8) or b
                            if (target == 1) fg = color else bg = color
                            i += 4
                        }
                    }
                }
            }
            i++
        }
    }

    private fun resetAttrs() {
        fg = -1; bg = -1; bold = false; reverse = false
    }

    private companion object {
        fun newGrid(rows: Int, cols: Int): Array<Array<TermCell>> {
            val c = cols.coerceAtLeast(1)
            val r = rows.coerceAtLeast(1)
            return Array(r) { Array(c) { TermCell() } }
        }
    }
}
