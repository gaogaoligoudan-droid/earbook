package com.earbook.app.book

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 章级资产登记（M1-c）：
 * - 服务对象：R7 优化幂等闸门 / R8c 书架徽标 / R11 管理页（每书占用与资产明细）
 * - 存储：每书 assets-index/{bookId}.json（书删除时随 clearBook 清）
 * - 语义：optimized=true → 永不再调 AI（R7 硬约束）；optimizeFailed → 不自动重试，
 *   按原文缓存播（用户拍板「失败即原文」）
 */
class AssetRegistry(private val context: Context) {

    private fun root(): File = File(context.filesDir, "assets-index").apply { mkdirs() }
    private fun fileFor(bookId: String) = File(root(), "$bookId.json")

    fun clearBook(bookId: String) { fileFor(bookId).delete() }

    private fun load(bookId: String): JSONObject = try {
        JSONObject(fileFor(bookId).readText())
    } catch (_: Exception) { JSONObject() }

    private fun save(bookId: String, o: JSONObject) {
        val tmp = File(root(), "$bookId.json.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(fileFor(bookId))) tmp.delete()
    }

    private fun chapterNode(bookId: String, chapterIndex: Int, create: JSONObject.() -> Unit): JSONObject {
        val root = load(bookId)
        val chapters = root.optJSONObject("chapters") ?: JSONObject().also { root.put("chapters", it) }
        val node = chapters.optJSONObject(chapterIndex.toString())
            ?: JSONObject().also { chapters.put(chapterIndex.toString(), it) }
        node.create()
        save(bookId, root)
        return node
    }

    // ── R7 优化标记 ──

    fun isOptimized(bookId: String, chapterIndex: Int): Boolean =
        load(bookId).optJSONObject("chapters")?.optJSONObject(chapterIndex.toString())
            ?.optBoolean("optimized", false) ?: false

    fun markOptimized(bookId: String, chapterIndex: Int): Int {
        var version = 0
        chapterNode(bookId, chapterIndex) {
            version = optInt("textVersion", 0) + 1
            put("optimized", true)
            put("optimizeFailed", false)
            put("textVersion", version)
        }
        return version
    }

    fun markOptimizeFailed(bookId: String, chapterIndex: Int) {
        chapterNode(bookId, chapterIndex) { put("optimizeFailed", true) }
    }

    fun textVersion(bookId: String, chapterIndex: Int): Int =
        load(bookId).optJSONObject("chapters")?.optJSONObject(chapterIndex.toString())
            ?.optInt("textVersion", 0) ?: 0

    // ── R8c/R11 徽标与占用 ──

    fun markCached(bookId: String, chapterIndex: Int, voice: String, bytes: Long) {
        chapterNode(bookId, chapterIndex) {
            val voices = optJSONArray("cachedVoices") ?: org.json.JSONArray().also { put("cachedVoices", it) }
            if (!voices.toString().contains("\"$voice\"")) voices.put(voice)
            put("cacheBytes", bytes)
        }
    }

    fun unmarkVoice(bookId: String, voice: String) {
        val root = load(bookId)
        val chapters = root.optJSONObject("chapters") ?: return
        chapters.keys().forEach { k ->
            val node = chapters.optJSONObject(k) ?: return@forEach
            val voices = node.optJSONArray("cachedVoices") ?: return@forEach
            val kept = org.json.JSONArray()
            for (i in 0 until voices.length()) {
                val v = voices.getString(i)
                if (v != voice) kept.put(v)
            }
            if (kept.length() == 0) node.remove("cachedVoices") else node.put("cachedVoices", kept)
            if (kept.length() == 0) node.remove("cacheBytes")
        }
        save(bookId, root)
    }

    /** 书级汇总（徽标/管理页）：优化 N/M 章、已缓存 N/M 章、缓存总字节 */
    data class BookSummary(
        val optimizedChapters: Int,
        val failedChapters: Int,
        val cachedChapters: Int,
        val cachedVoices: Set<String>,
        val cacheBytes: Long,
    )

    fun bookSummary(bookId: String): BookSummary {
        val chapters = load(bookId).optJSONObject("chapters") ?: return BookSummary(0, 0, 0, emptySet(), 0)
        var opt = 0; var failed = 0; var cached = 0; var bytes = 0L
        val voices = mutableSetOf<String>()
        chapters.keys().forEach { k ->
            val n = chapters.optJSONObject(k) ?: return@forEach
            if (n.optBoolean("optimized")) opt++
            if (n.optBoolean("optimizeFailed")) failed++
            val v = n.optJSONArray("cachedVoices")
            if (v != null && v.length() > 0) {
                cached++
                for (i in 0 until v.length()) voices.add(v.getString(i))
            }
            bytes += n.optLong("cacheBytes", 0)
        }
        return BookSummary(opt, failed, cached, voices, bytes)
    }
}

/**
 * 存储预估（M1-d，R11 缓存前预警）：
 * 中文按 ~4.5 字/秒（TTS 实测常见区间 4-5 取保守值），AAC 32kbps=4000B/s，
 * ×1.2 保险系数（用户拍板「宁可保守」）。
 */
object StorageEstimator {
    private const val CHARS_PER_SEC = 4.5
    private const val AAC_BYTES_PER_SEC = 4000L
    private const val SAFETY = 1.2

    fun estimateBookBytes(totalChars: Long): Long =
        (totalChars / CHARS_PER_SEC).toLong() * AAC_BYTES_PER_SEC * SAFETY.toLong()

    fun estimateChapterBytes(chapterChars: Long): Long = estimateBookBytes(chapterChars)
}
