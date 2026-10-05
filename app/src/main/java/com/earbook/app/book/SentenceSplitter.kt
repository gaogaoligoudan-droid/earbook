package com.earbook.app.book

/**
 * 中文分句器：按句末标点切句，超长句在逗号处二次切分。
 *
 * 向后窥探（Lookahead）：句末标点后紧跟的闭引号/闭括号/连续标点
 * 一律并入当前句——否则 TTS 会把单独的 ” ！ …… 念成标点符号名。
 * 行拼接（PDF 解包）也需要这两组集合判断行尾。
 */
object SentenceSplitter {

    /** 句末标点 */
    val END_PUNCTS = charArrayOf('。', '！', '？', '；', '…', '!', '?')

    /** 闭引号/闭括号：紧跟句末标点时并入当前句 */
    val CLOSERS = charArrayOf('”', '’', '）', '》', '」', '』', '】', '"', '\'', ')')

    /** 超过该长度在逗号/顿号处二次切分，避免单次合成过重 */
    private const val LONG_SENTENCE = 70

    /** 二次切分的最短句长（短于它的逗号不再切） */
    private const val MIN_SPLIT_LEN = 20

    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val raw = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\n') {
                // 换行视为句界（小说正文换行通常即段落）
                flushTo(raw, sb)
            } else {
                sb.append(ch)
                if (END_PUNCTS.contains(ch)) {
                    // 向后窥探：闭引号/闭括号/连续句末标点并入当前句
                    var j = i + 1
                    while (j < text.length &&
                        (CLOSERS.contains(text[j]) || END_PUNCTS.contains(text[j]))
                    ) {
                        sb.append(text[j])
                        j++
                    }
                    i = j - 1
                    flushTo(raw, sb)
                }
            }
            i++
        }
        flushTo(raw, sb)

        // 二次切分超长句
        val result = ArrayList<String>(raw.size)
        for (sentence in raw) {
            if (sentence.length <= LONG_SENTENCE) {
                result.add(sentence)
            } else {
                result.addAll(splitLong(sentence))
            }
        }
        return result.filter { it.isNotBlank() }
    }

    private fun flushTo(raw: ArrayList<String>, sb: StringBuilder) {
        if (sb.isNotEmpty()) {
            val s = sb.toString().trim()
            if (s.isNotEmpty()) raw.add(s)
        }
        sb.setLength(0)
    }

    private fun splitLong(sentence: String): List<String> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in sentence) {
            sb.append(ch)
            if ((ch == '，' || ch == '、' || ch == '：') && sb.length >= MIN_SPLIT_LEN) {
                parts.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) parts.add(sb.toString())
        return parts
    }
}
