package com.earbook.app.book

import android.content.Context
import android.net.Uri

/**
 * TXT 导入：流式读取 + 编码探测，分章逻辑在 [ChapterParser]（纯逻辑可单测）。
 */
object TxtImporter {

    /** 编码探测采样字节数 */
    private const val CHARSET_SAMPLE_BYTES = 64 * 1024

    /** 导入 Uri 指向的 TXT，返回分章结果 */
    fun import(context: Context, uri: Uri): Pair<Book, List<Chapter>> {
        val text = readText(context, uri)
        val book = Book(
            id = uri.toString(),
            title = BookImporter.queryTitle(context, uri),
            uriString = uri.toString(),
            format = Book.FORMAT_TXT
        )
        return book to ChapterParser.splitChapters(text)
    }

    /**
     * 流式读取文本：
     * 1) 只取前 64KB 做编码探测（大文件全量校验会瞬间堆出几百 MB 导致 OOM）；
     * 2) 用 InputStreamReader 流式解码，全程不持有完整字节数组；
     * 3) 剔除 UTF-8 BOM（\uFEFF 不被 \s 匹配，会破坏第一章标题识别）。
     */
    private fun readText(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取文件：$uri")
        return input.use { stream ->
            val buffered = java.io.BufferedInputStream(stream)
            buffered.mark(CHARSET_SAMPLE_BYTES + 8)
            val sample = ByteArray(CHARSET_SAMPLE_BYTES)
            var n = 0
            while (n < sample.size) {
                val r = buffered.read(sample, n, sample.size - n)
                if (r < 0) break
                n += r
            }
            val charset = CharsetDetector.detect(sample, n)
            buffered.reset()
            java.io.InputStreamReader(buffered, charset).use { reader ->
                reader.readText().removePrefix("\uFEFF")
            }
        }
    }
}
