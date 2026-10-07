package com.earbook.app.playback

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.earbook.app.book.BookImporter
import com.earbook.app.tts.ModelManager
import com.earbook.app.tts.SherpaTtsEngine

/**
 * 章节预渲染（M3-2，方案 §预渲染）：
 * - 写入者：ChapterAudioCache 的唯一写入方（听完的章后台渲染 N+1/N+2…）
 * - 触发：WorkManager charging+idle 约束（不充电任务待命，正好=「充电时预渲染」语义）
 * - 限额：渲染后执行滑块上限 LRU（锚定当前书）
 * - 读取者：SherpaReadAloudEngine.speak 命中整章缓存→句级零合成播放
 */
object PrerenderManager {

    const val DEFAULT_VOICE = "kokoro30" // 与 VoicePrefs.sid（试听拍板 7 号女声）对齐；男声切换时由调用方传 kokoro70
    private const val BATCH = 3 // 一次预渲染 3 章

    /** 渲染单章：逐句合成→拼接→WAV+句偏移落盘。幂等（已缓存跳过）。返回渲染的章数。 */
    fun renderChapter(
        context: Context,
        bookId: String,
        chapterIndex: Int,
        voice: String = DEFAULT_VOICE,
    ): Boolean {
        val cache = ChapterAudioCache(context)

        // 解析书（Worker 独立进程无 Service 内存态，重新流式导入）
        val chapters = runCatching {
            BookImporter.import(context, android.net.Uri.parse(bookId)).second
        }.getOrElse { return false }
        val chapter = chapters.getOrNull(chapterIndex) ?: return false

        // M3-1→M1：AI 优化（BYOK 有 key 时）+ **章级幂等闸门**（R7 硬约束：
        // isOptimized 的章永不再调 API；失败标记后不自动重试，按原文缓存）
        val registry = com.earbook.app.book.AssetRegistry(context)
        val apiKey = context.getSharedPreferences("earbook", Context.MODE_PRIVATE)
            .getString("deepseek_key", "").orEmpty()
        var textVersion = registry.textVersion(bookId, chapterIndex)
        var sentences = chapter.sentences
        if (apiKey.isNotEmpty() && !registry.isOptimized(bookId, chapterIndex)) {
            val raw = sentences.joinToString("\n")
            val optimized = com.earbook.app.ai.AiTextOptimizer.optimizeChapter(apiKey, raw)
            if (optimized != null && optimized.length > raw.length / 2) {
                // CJK 保结构（业界对照报告硬雷）：优化结果重新分句，结构异常作废
                val resplit = runCatching {
                    com.earbook.app.book.SentenceSplitter.split(optimized)
                }.getOrNull()
                if (resplit != null && resplit.size >= sentences.size / 2) {
                    sentences = resplit
                    textVersion = registry.markOptimized(bookId, chapterIndex)
                } else {
                    registry.markOptimizeFailed(bookId, chapterIndex)
                }
            } else {
                registry.markOptimizeFailed(bookId, chapterIndex)
            }
        }

        val key = ChapterAudioCache.Key(bookId, chapterIndex, voice, textVersion)
        if (cache.has(key)) return true

        if (!ModelManager.isReady(context)) return false
        // 音色随 voice 缓存键走（kokoro30/kokoro70）：渲染音色与缓存命名空间一致
        val sid = voice.removePrefix("kokoro").toIntOrNull() ?: SherpaTtsEngine.KOKORO_DEFAULT_SPEAKER
        val engine = SherpaTtsEngine(context, ModelManager.modelDir(context), sid)
        try {
            val sr = engine.sampleRate()
            val all = ArrayList<FloatArray>(sentences.size)
            val offsets = ArrayList<Long>(sentences.size + 1)
            var acc = 0L
            offsets.add(0)
            for (s in sentences) {
                val pcm = engine.synthesizeFull(s)
                if (pcm.isEmpty()) continue
                all.add(pcm)
                acc += pcm.size
                offsets.add(acc)
            }
            val total = FloatArray(all.sumOf { it.size })
            var pos = 0
            for (p in all) {
                System.arraycopy(p, 0, total, pos, p.size)
                pos += p.size
            }
            // M1：AAC 落盘（无上限无 LRU，R11 拍板）+ 资产登记（R8c 徽标/管理页）
            cache.put(key, sr, total, offsets.toLongArray())
            val f = cache.existingFile(key)
            if (f != null) registry.markCached(bookId, chapterIndex, voice, f.length())
        } finally {
            engine.release()
        }
        return true
    }

    /** 排队预渲染 fromChapter 起的 N 章（charging+idle 约束——不满足就待命） */
    fun enqueue(context: Context, bookId: String, fromChapter: Int, count: Int = BATCH) {
        val wm = WorkManager.getInstance(context)
        val req = OneTimeWorkRequestBuilder<PrerenderWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true) // 方案：充电时预渲染
                    .setRequiresDeviceIdle(true) // 空闲（Doze 兼容）
                    .build()
            )
            .setInputData(
                Data.Builder()
                    .putString(PrerenderWorker.KEY_BOOK, bookId)
                    .putInt(PrerenderWorker.KEY_FROM, fromChapter)
                    .putInt(PrerenderWorker.KEY_COUNT, count)
                    .build()
            )
            .build()
        wm.enqueueUniqueWork("prerender-$bookId", ExistingWorkPolicy.REPLACE, req)
    }
}
