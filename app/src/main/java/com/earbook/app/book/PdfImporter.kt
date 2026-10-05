package com.earbook.app.book

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * PDF 导入：提取文字层后复用 TXT 的分章/分句管道。
 *
 * 限制：仅支持带文字层的 PDF；扫描版（图片型）无文字可提取，会抛出友好错误。
 * CJK 间距清理：部分 PDF 提取出的中文之间夹空格，朗读前合并。
 */
object PdfImporter {

    @Volatile
    private var pdfBoxReady = false

    fun import(context: Context, uri: Uri): Pair<Book, List<Chapter>> {
        if (!pdfBoxReady) {
            synchronized(this) {
                if (!pdfBoxReady) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    pdfBoxReady = true
                }
            }
        }

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取文件：$uri")

        val text = input.use { stream ->
            PDDocument.load(stream).use { doc ->
                val pageCount = doc.numberOfPages
                if (pageCount <= 0) throw IllegalStateException("PDF 没有页面")
                val raw = PDFTextStripper().getText(doc)
                if (raw.length < pageCount * 10) {
                    throw IllegalStateException(
                        "该 PDF 几乎提取不到文字，可能是扫描版（图片型），暂不支持"
                    )
                }
                raw
            }
        }

        // 统一换行 → 解包拼行 → 清 CJK 空格（顺序不能反，见 PdfTextUnwrap 注释）
        val normalized = PdfTextUnwrap.normalize(text.replace("\r\n", "\n"))

        val book = Book(
            id = uri.toString(),
            title = BookImporter.queryTitle(context, uri),
            uriString = uri.toString(),
            format = Book.FORMAT_PDF
        )
        return book to ChapterParser.splitChapters(normalized)
    }
}
