package com.earbook.app.text

/**
 * L1 本地文本规范化（兜底层，永远可用——讨论方案 §M2-4）：
 * 数字/日期/电话的中文转读已由引擎 ruleFsts 原生处理（number-zh.fst 等），
 * 本层只做**文本清洗**：水印/URL/页眉重复行——「用户第一耳朵口碑」的守门员。
 *
 * 纯逻辑，JVM 可单测。
 */
object TextNormalizer {

    private val urlPattern = Regex(
        """(https?://|www\.)[^\s，。；、""'']{3,}|\b[a-zA-Z0-9.-]+\.(com|cn|net|org|cc|top|xyz|info|me)\b([^\s，。；、""'']*)?"""
    )

    private val watermarkPatterns = listOf(
        Regex("""^\s*更多.*(小说|章节|全文|下载|请|访问|搜|关注).*$"""),
        Regex("""^\s*(本章未完|点击下一页|继续阅读|求收藏|求推荐|求月票|求订阅|求打赏|防盗|防采集|起点|晋江|纵横|飞库).*"""),
        Regex("""^\s*首发[^\s]{0,30}(网|站).*$"""),
        Regex("""^\s*本文来自.*$"""),
        Regex("""^\s*\\(本章完|完\\)\s*$"""),
        Regex("""^\s*\d+[、.．]?\s*(顶|踩|收藏|推荐|月票|打赏)\s*$"""),
    )

    /** 重复页眉检测：同一短行在文中出现 ≥3 次视为页眉（章节名/书名/网站名） */
    private fun repeatedHeaderLines(lines: List<String>): Set<String> {
        val counts = HashMap<String, Int>()
        for (l in lines) {
            val t = l.trim()
            if (t.length in 1..25) counts[t] = (counts[t] ?: 0) + 1
        }
        return counts.filter { it.value >= 3 }.keys
    }

    /**
     * 清洗整章文本 → 朗读用行列表。
     * 规则：
     * 1. 删 URL 行/句内 URL
     * 2. 删水印行（匹配已知模式）
     * 3. 删重复页眉
     * 4. 折叠空行
     */
    fun clean(chapterText: String): String {
        val lines = chapterText.lines()
        val headers = repeatedHeaderLines(lines)
        val kept = lines.filter { raw ->
            val t = raw.trim()
            if (t.isEmpty()) return@filter false // 折叠空行
            if (t in headers) return@filter false
            // 纯链接行：删 URL 后剩余实义内容 ≤2 字才算（否则正文陪葬）
            val residue = t.replace(urlPattern, "")
                .replace(Regex("""[\s，。；、：:·—\-~]+"""), "")
            if (residue.length <= 2) return@filter false
            watermarkPatterns.none { it.containsMatchIn(t) }
        }
        // 句内残留 URL（混在正文中的）
        return kept.joinToString("\n") { it.replace(urlPattern, "") }
            .replace(Regex("""\n{2,}"""), "\n")
            .trim()
    }
}
