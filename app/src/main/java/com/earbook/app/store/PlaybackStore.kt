package com.earbook.app.store

import android.content.Context
import android.content.SharedPreferences
import com.earbook.app.book.Book
import com.earbook.app.book.Progress
import org.json.JSONArray
import org.json.JSONObject

/**
 * M1 版轻量持久化：SharedPreferences + JSON。
 * M2 引入 Room（书架、进度、缓存索引），此接口保持不变。
 */
class PlaybackStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("earbook", Context.MODE_PRIVATE)

    fun listBooks(): List<Book> {
        val raw = prefs.getString(KEY_BOOKS, null) ?: return emptyList()
        val array = JSONArray(raw)
        val books = ArrayList<Book>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            books.add(
                Book(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    uriString = o.getString("uri"),
                    format = o.optString("format", Book.FORMAT_TXT)
                )
            )
        }
        return books
    }

    fun addBook(book: Book): Boolean {
        val books = listBooks().toMutableList()
        if (books.any { it.id == book.id }) return false
        books.add(0, book)
        prefs.edit().putString(KEY_BOOKS, toJson(books)).apply()
        return true
    }

    fun removeBook(bookId: String) {
        val books = listBooks().filter { it.id != bookId }
        prefs.edit()
            .putString(KEY_BOOKS, toJson(books))
            .remove(progressKey(bookId))
            .apply()
    }

    fun saveProgress(bookId: String, progress: Progress) {
        prefs.edit()
            .putString(progressKey(bookId), "${progress.chapterIndex}:${progress.sentenceIndex}")
            .apply()
    }

    fun getProgress(bookId: String): Progress {
        val raw = prefs.getString(progressKey(bookId), "0:0") ?: "0:0"
        val parts = raw.split(":")
        val chapter = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val sentence = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return Progress(chapter, sentence)
    }

    private fun progressKey(bookId: String) = "progress:$bookId"

    private fun toJson(books: List<Book>): String {
        val array = JSONArray()
        for (b in books) {
            array.put(
                JSONObject()
                    .put("id", b.id)
                    .put("title", b.title)
                    .put("uri", b.uriString)
                    .put("format", b.format)
            )
        }
        return array.toString()
    }

    companion object {
        private const val KEY_BOOKS = "books"
    }
}
