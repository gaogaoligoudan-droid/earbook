package com.earbook.app.book

/**
 * 章节标题识别与分章（纯逻辑，无 Android 依赖，可在 JVM 单测）。
 * 分章规则参考了 Legado（阅读3.0）等阅读器的通用做法，为独立实现。
 */
object ChapterParser {

    val CHAPTER_PATTERNS = listOf(
        // 第123章 / 第十二章 / 第一百二十三章（前后允许少量空白与说明文字）
        Regex("""^\s*第\s*[0-9零一二三四五六七八九十百千万两]+\s*[章节卷集部回幕]\s*\S{0,30}\s*$"""),
        // （一） (12) 序号式标题（独立成行）
        Regex("""^\s*[（(]\s*[0-9零一二三四五六七八九十百千万]+\s*[)）]\s*$"""),
        // 序章 / 楔子 / 尾声 / 后记 / 番外
        Regex("""^\s*(序章|序言|楔子|尾声|后记|番外\S{0,20})\s*$"""),
        // 1、 / 1. / 1． 数字序号式标题
        Regex("""^\s*[0-9]{1,4}\s*[、．.]\s*\S{1,30}\s*$""")
    )

    /** 识别不出章节结构时，按此字符数切块 */
    private const val FALLBACK_BLOCK_CHARS = 30_000

    fun isChapterTitle(line: String): Boolean {
        if (line.length > 35) return false
        return CHAPTER_PATTERNS.any { it.matches(line) }
    }

    fun splitChapters(text: String): List<Chapter> {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chapters = ArrayList<Chapter>()
        val current = StringBuilder()
        var currentTitle = "开头"
        var matchCount = 0

        fun flush() {
            val body = current.toString().trim()
            if (body.isNotEmpty() || currentTitle != "开头") {
                chapters.add(Chapter(currentTitle, SentenceSplitter.split(body)))
            }
            current.setLength(0)
        }

        for (line in lines) {
            if (isChapterTitle(line)) {
                flush()
                currentTitle = line
                matchCount++
            } else {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
        }
        flush()

        // 结构太碎（大量误判）或完全没有章节标记时，走兜底策略
        if (matchCount < 3 || chapters.size > 5000) {
            return fallbackSplit(text)
        }
        return chapters
    }

    /** 兜底：无章节结构时按固定字数切块 */
    private fun fallbackSplit(text: String): List<Chapter> {
        val compact = text.replace(Regex("\\n{2,}"), "\n").trim()
        if (compact.isEmpty()) return listOf(Chapter("全文", emptyList()))
        val result = ArrayList<Chapter>()
        var start = 0
        var index = 1
        while (start < compact.length) {
            val end = minOf(start + FALLBACK_BLOCK_CHARS, compact.length)
            result.add(Chapter("第 $index 部分", SentenceSplitter.split(compact.substring(start, end))))
            start = end
            index++
        }
        return result
    }
}
