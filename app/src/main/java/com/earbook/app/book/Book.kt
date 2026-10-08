package com.earbook.app.book

/** 书架中的一本书（元信息，正文不落库，播放时从 Uri 重新导入） */
data class Book(
    val id: String,       // 稳定 ID：uri 的 hash
    val title: String,
    val uriString: String,
    val format: String = FORMAT_TXT,   // "txt" / "pdf"
    val voice: String = VOICE_FEMALE,  // R1 书级音色：改音色=该书缓存重渲（优化保留）
    val mode: String = MODE_NEURAL,    // R6 书级播放模式：neural=缓存后播 / system=即时
    val totalChars: Long = 0,          // R11 预估数据源（播放时统计写回，0=未知）
    val totalChapters: Int = 0         // R8c 徽标分母（同上）
) {
    companion object {
        const val FORMAT_TXT = "txt"
        const val FORMAT_PDF = "pdf"
        const val VOICE_FEMALE = "female"
        const val VOICE_MALE = "male"
        const val MODE_NEURAL = "neural"
        const val MODE_SYSTEM = "system"
    }
}

/** 导入后的章节，正文已按句切分 */
data class Chapter(
    val title: String,
    val sentences: List<String>
) {
    val sentenceCount: Int get() = sentences.size
}

/** 朗读进度：章节 + 句子双索引 */
data class Progress(
    val chapterIndex: Int,
    val sentenceIndex: Int
)
