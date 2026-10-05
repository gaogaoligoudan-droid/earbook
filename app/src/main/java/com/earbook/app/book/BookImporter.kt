package com.earbook.app.book

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * 统一导入入口：按 MIME / 扩展名分发到对应导入器。
 */
object BookImporter {

    fun import(context: Context, uri: Uri): Pair<Book, List<Chapter>> {
        return if (isPdf(context, uri)) {
            PdfImporter.import(context, uri)
        } else {
            TxtImporter.import(context, uri)
        }
    }

    /**
     * 书架导入只取元数据（书名/格式/URI），不做全书解析。
     * 全书解析推迟到点开播放时（ReadAloudService），避免导入几 MB 的书时
     * 白白切几万句再丢弃；解析失败的报错也在播放时以 Toast 呈现。
     */
    fun buildBookMeta(context: Context, uri: Uri): Book = Book(
        id = uri.toString(),
        title = queryTitle(context, uri),
        uriString = uri.toString(),
        format = if (isPdf(context, uri)) Book.FORMAT_PDF else Book.FORMAT_TXT
    )

    /**
     * PDF 判定，按可靠性排序：
     * 1. Provider MIME（部分 Provider 返回 octet-stream/null，不可靠）；
     * 2. DISPLAY_NAME 扩展名（SAF 的 lastPathSegment 是虚拟文档 ID，不含文件名！）；
     * 3. 魔数嗅探（读文件头 5 字节是否 %PDF-，最终兜底，无视一切 Provider 行为）。
     */
    fun isPdf(context: Context, uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri)
        if (mime == "application/pdf") return true
        val displayName = queryDisplayName(context, uri)?.lowercase() ?: ""
        if (displayName.endsWith(".pdf")) return true
        if (uri.lastPathSegment?.lowercase()?.endsWith(".pdf") == true) return true
        return sniffPdfMagic(context, uri)
    }

    /** 读文件头嗅探 %PDF- 魔数 */
    private fun sniffPdfMagic(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(5)
            var n = 0
            while (n < 5) {
                val r = input.read(head, n, 5 - n)
                if (r < 0) break
                n += r
            }
            String(head, 0, n, Charsets.US_ASCII) == "%PDF-"
        } ?: false
    } catch (_: Exception) {
        false
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** 从 Uri 解析书名（显示名优先，扩展名截掉） */
    fun queryTitle(context: Context, uri: Uri): String {
        // SAF 的 DISPLAY_NAME 是用户看到的名字，优先
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) {
                        return name.substringBeforeLast('.')
                    }
                }
            }
        } catch (_: Exception) {
            // 某些 provider 不支持 query，走路径兜底
        }
        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?: "未命名"
    }
}
