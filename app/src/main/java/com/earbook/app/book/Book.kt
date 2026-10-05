package com.earbook.app.book

/** 书架中的一本书（元信息，正文不落库，播放时从 Uri 重新导入） */
data class Book(
    val id: String,       // 稳定 ID：uri 的 hash
    val title: String,
    val uriString: String,
    val format: String = FORMAT_TXT   // "txt" / "pdf"
) {
    companion object {
        const val FORMAT_TXT = "txt"
        const val FORMAT_PDF = "pdf"
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
