package com.earbook.app.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.earbook.app.tts.ModelManager
import com.earbook.app.tts.SherpaTtsEngine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * sherpa-onnx 离线朗读引擎（ReadAloudEngine 第三实现，方案 §M2-1）。
 *
 * speak 语义（对齐 SystemReadAloudEngine）：一次性播放一句，
 * 完成回调驱动 Service 推进——句内用 generateWithCallback 流式（首块即播）。
 * 句间预取（SentenceWindow）在第二波接线时由 Service 层配合实现。
 *
 * 模型未就绪（首启下载中/失败）时 isReady=false，Service 回落 System 引擎。
 */
class SherpaReadAloudEngine(context: Context) : ReadAloudEngine {

    private val appContext = context.applicationContext
    private var core: SherpaTtsEngine? = null
    private var track: AudioTrack? = null
    private var trackSampleRate = 0

    @Volatile private var currentWorker: Thread? = null
    private val stopped = AtomicBoolean(false)

    private var onDone: ((String, Boolean) -> Unit)? = null
    private var onInit: ((Boolean) -> Unit)? = null

    override val isReady: Boolean
        get() = core != null && core!!.isReady

    fun ensureModelLoadedAsync() {
        Thread({
            try {
                ModelManager.downloadIfNeeded(appContext) { }
                synchronized(this) {
                    if (core == null) {
                        core = SherpaTtsEngine(
                            appContext,
                            com.earbook.app.tts.ModelManager.modelDir(appContext),
                        )
                        // 触发 JNI 加载与首句预热
                        core!!.sampleRate()
                    }
                }
                onInit?.invoke(true)
            } catch (e: Throwable) {
                onInit?.invoke(false)
            }
        }, "sherpa-init").apply { priority = Process.THREAD_PRIORITY_BACKGROUND }.start()
    }

    override fun speak(text: String, utteranceId: String) {
        stop()
        val engine = core ?: run { onDone?.invoke(utteranceId, false); return }
        stopped.set(false)
        // M3-2 磁盘缓存命中路径：章缓存 sentenceAt 切句直接播（零合成 CPU）
        val cached = cachedSentenceFor(utteranceId)
        val cachedSr = if (cached != null) cachedSampleRateFor(utteranceId) else null
        currentWorker = Thread({
            try {
                if (cached != null && cachedSr != null) {
                    // 命中：零合成直接播
                    val t = obtainTrack(cachedSr)
                    t.play()
                    val written = writeFloats(t, cached)
                    drainAndFinish(t, written, utteranceId)
                    return@Thread
                }
                // 常规路径：句内流式合成
                val t = obtainTrack(engine.sampleRate())
                t.play()
                val prefetched = synchronized(prefetchCache) { prefetchCache.remove(utteranceId) }
                val written = if (prefetched != null) {
                    writeFloats(t, prefetched)
                    prefetched.size
                } else {
                    engine.synthesize(text) { chunk ->
                        if (stopped.get()) throw InterruptedException()
                        writeFloats(t, chunk)
                    }
                }
                drainAndFinish(t, written, utteranceId)
            } catch (e: InterruptedException) {
                track?.stop()
                onDone?.invoke(utteranceId, false)
            } catch (e: Throwable) {
                track?.stop()
                onDone?.invoke(utteranceId, false)
            }
        }, "sherpa-speak").apply { priority = Process.THREAD_PRIORITY_URGENT_AUDIO }.also {
            it.start()
        }
    }

    private fun cachedSentenceFor(utteranceId: String): FloatArray? {
        val ctx = bookContext ?: return null
        val (ch, sn) = parseChapterSentence(utteranceId)
        if (ch < 0) return null
        return diskCache?.get(
            com.earbook.app.playback.ChapterAudioCache.Key(ctx.first, ch, ctx.second)
        )?.sentenceAt(sn)
    }

    private fun cachedSampleRateFor(utteranceId: String): Int? {
        val ctx = bookContext ?: return null
        val (ch, _) = parseChapterSentence(utteranceId)
        if (ch < 0) return null
        return diskCache?.get(
            com.earbook.app.playback.ChapterAudioCache.Key(ctx.first, ch, ctx.second)
        )?.sampleRate
    }

    /** 等播放追上写入后收尾回调（供缓存命中与流式路径共用） */
    private fun drainAndFinish(t: AudioTrack, written: Int, utteranceId: String) {
        while (!stopped.get() && t.playbackHeadPosition < written * 0.999) {
            Thread.sleep(60)
        }
        t.stop()
        onDone?.invoke(utteranceId, !stopped.get())
    }

    /** utteranceId "c{n}:s{n}" → (章, 句) */
    private fun parseChapterSentence(id: String): Pair<Int, Int> {
        val m = Regex("""c(\d+):s(\d+)""").find(id) ?: return -1 to -1
        return (m.groupValues[1].toInt()) to (m.groupValues[2].toInt())
    }

    /** 磁盘缓存上下文（Service 播放时注入：当前书+音色） */
    private var bookContext: Pair<String, String>? = null
    private var diskCache: com.earbook.app.playback.ChapterAudioCache? = null

    fun setBookContext(bookId: String, voice: String = com.earbook.app.playback.PrerenderManager.DEFAULT_VOICE) {
        diskCache = diskCache ?: com.earbook.app.playback.ChapterAudioCache(appContext)
        bookContext = bookId to voice
    }

    /** 预取：后台预合成下一句存内存（命中即播，句间零等待） */
    override fun prefetch(text: String, utteranceId: String) {
        val engine = core ?: return
        if (synchronized(prefetchCache) { prefetchCache.containsKey(utteranceId) }) return
        Thread({
            try {
                val audio = engine.synthesizeFull(text)
                if (audio.isNotEmpty()) {
                    synchronized(prefetchCache) {
                        prefetchCache[utteranceId] = audio
                        trimPrefetchLocked()
                    }
                }
            } catch (_: Throwable) {
                // 预取失败静默——speak 走正常合成路径
            }
        }, "sherpa-prefetch").apply { priority = Process.THREAD_PRIORITY_BACKGROUND }.start()
    }

    /** FloatArray → AudioTrack（LITTLE_ENDIAN float32） */
    private fun writeFloats(t: AudioTrack, chunk: FloatArray): Int {
        if (chunk.isEmpty()) return 0
        val bb = java.nio.ByteBuffer.allocate(chunk.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in chunk) bb.putFloat(s)
        return t.write(bb.array(), 0, bb.array().size, AudioTrack.WRITE_BLOCKING)
    }

    /** 预取缓存（句级内存）：容量保护——最多 2 句 */
    private val prefetchCache = HashMap<String, FloatArray>()

    private fun trimPrefetchLocked() {
        while (prefetchCache.size > 2) {
            prefetchCache.remove(prefetchCache.keys.first())
        }
    }

    private fun obtainTrack(sampleRate: Int): AudioTrack {
        val existing = track
        if (existing != null && trackSampleRate == sampleRate) return existing
        existing?.release()
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(sampleRate * 4) // 1 秒缓冲
            .build()
        track = t
        trackSampleRate = sampleRate
        return t
    }

    override fun stop() {
        stopped.set(true)
        currentWorker?.let { w ->
            w.interrupt()
            // 等收尾（最多 500ms，避免句间卡顿）
            try { w.join(500) } catch (_: InterruptedException) {}
        }
        currentWorker = null
        track?.pause()
    }

    override fun shutdown() {
        stop()
        track?.release()
        track = null
        core?.release()
        core = null
    }

    override fun setOnDoneListener(listener: ((String, Boolean) -> Unit)?) { onDone = listener }
    override fun setOnInitListener(listener: ((Boolean) -> Unit)?) { onInit = listener }

    @Volatile private var volume = 1f
    override fun setVolume(volume: Float) {
        this.volume = volume
        track?.setVolume(volume)
    }
}
