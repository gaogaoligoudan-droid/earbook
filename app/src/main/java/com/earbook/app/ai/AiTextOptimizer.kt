package com.earbook.app.ai

/**
 * AI 文本优化层（M3-1，方案 §L2 DeepSeek 增强）：
 * - 仅在预渲染 Worker（充电+网络后台）调用——播放路径零网络、零等待
 * - 失败/无 key 返回 null → 调用方走 L1 原文（兜底链）
 * - 「边优化边听」策略：已听句不回切，AI 版只通过缓存 textVersion 维度对未听章生效
 *
 * 无人区声明：LLM×TTS 前端无成熟范式（社区调研结论）——本层是隔离实验，feature flag 控制。
 */
object AiTextOptimizer {

    private val SYSTEM = """
        你是听书文本优化器。处理小说章节文本，让它更适合文字转语音朗读。规则：
        1. 只输出处理后的正文，不要任何解释、前后缀、markdown 标记
        2. 消除同音字/多音字朗读歧义：把容易读错的词替换为无歧义写法（如「胆囊」→「胆nang」不要这样做，保持中文；正确做法：「了」→按语境保留；「还 hai/huán」歧义时改写句子消歧）
        3. 修正明显的标点缺失或滥用（但不要增删内容）
        4. 长破折号、省略号规范为单个标准符号
        5. 严禁：删减情节、改写对话内容、添加任何原文没有的文字
    """.trimIndent()

    /** 优化一章文本；失败返回 null（走本地原文兜底） */
    fun optimizeChapter(apiKey: String, chapterText: String): String? {
        if (apiKey.isBlank() || chapterText.isBlank()) return null
        // 超长章分批（上下文窗口保护）：单批 ≤6000 字
        return if (chapterText.length <= 6000) {
            DeepSeekClient.chat(apiKey, SYSTEM, chapterText)
        } else {
            val batches = chapterText.chunked(6000)
            val optimized = batches.map { DeepSeekClient.chat(apiKey, SYSTEM, it) }
            // 任一批失败则整章放弃（半旧半新不可接受——与音色切换同原则）
            if (optimized.any { it == null }) null
            else optimized.filterNotNull().joinToString("\n")
        }
    }
}
