package com.earbook.app.book

/**
 * PDF 文本规整（纯逻辑，无 Android 依赖，可在 JVM 单测）。
 *
 * 处理顺序很重要：必须先解包拼行、再清 CJK 空格——
 * 反过来的话，行首缩进空格会留在拼接后的句子中间（「人工    智能」）。
 */
object PdfTextUnwrap {

    /**
     * PDF 提取文本 → 适合分章/分句的文本：
     * 1. 行解包（Unwrap）：行尾非句末标点且非章节标题 → 与下一行拼接；
     * 2. 英文断行补空格：前后均为 ASCII 字母/数字时补 1 个空格，防止 appleorange 粘连；
     * 3. CJK 空格清理：仅对正文行执行（章节标题行保留原样，
     *    否则「第一章 雨夜来客」会被清成「第一章雨夜来客」）。
     */
    fun normalize(text: String): String {
        val unwrapped = unwrap(text)
        return unwrapped.lineSequence().joinToString("\n") { line ->
            if (ChapterParser.isChapterTitle(line)) line else cleanCjkSpaces(line)
        }
    }

    private val CJK_SPACE_REGEX =
        Regex("(?<=[\\u4e00-\\u9fff，。！？；：、])[ \\t]+(?=[\\u4e00-\\u9fff，。！？；：、])")

    private fun cleanCjkSpaces(line: String): String = line.replace(CJK_SPACE_REGEX, "")

    private fun unwrap(text: String): String {
        val sb = StringBuilder(text.length)
        for (raw in text.split('\n')) {
            // trim 而非 trimEnd：行首的 2~4 个缩进空格也是污染源
            val line = raw.trim()
            if (line.isEmpty()) {
                sb.append('\n')
                continue
            }
            // 与上一行拼接时，防止英文单词粘连
            if (sb.isNotEmpty() && sb.last() != '\n') {
                if (sb.last().isAsciiLetterOrDigit() && line.first().isAsciiLetterOrDigit()) {
                    sb.append(' ')
                }
            }
            sb.append(line)
            val last = line.last()
            val endsSentence = SentenceSplitter.END_PUNCTS.contains(last) ||
                SentenceSplitter.CLOSERS.contains(last)
            if (endsSentence || ChapterParser.isChapterTitle(line)) {
                sb.append('\n')
            }
            // 否则不换行，与下一行拼接
        }
        return sb.toString()
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        (this in 'a'..'z') || (this in 'A'..'Z') || (this in '0'..'9')
}
