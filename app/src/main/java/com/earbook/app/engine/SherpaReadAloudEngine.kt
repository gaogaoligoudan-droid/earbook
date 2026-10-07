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
    @Volatile private var currentTrack: AudioTrack? = null

    private companion object {
        private const val TAG = "SherpaEngine"
    }

    @Volatile private var currentWorker: Thread? = null
    private val stopped = AtomicBoolean(false)

    private var onDone: ((String, Boolean) -> Unit)? = null
    private var onInit: ((Boolean) -> Unit)? = null

    override val isReady: Boolean
        get() = core != null && core!!.isReady

    fun ensureModelLoadedAsync() {
        Thread({
            try {
                ModelManager.installIfNeeded(appContext) { }
                synchronized(this) {
                    if (core == null) {
                        core = SherpaTtsEngine(
                            appContext,
                            com.earbook.app.tts.ModelManager.modelDir(appContext),
                            com.earbook.app.tts.VoicePrefs.sid(appContext),
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

    /** 等待异步初始化完成（有界），供 speak 在冷启动窗口内使用 */
    private fun waitForCore(timeoutMs: Long): SherpaTtsEngine? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(this) { core }?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    override fun speak(text: String, utteranceId: String) {
        stop()
        // 音色切换即时生效：每句开始时同步偏好（引擎已加载则原地换 sid，无需重建）
        core?.let { c ->
            val wantSid = com.earbook.app.tts.VoicePrefs.sid(appContext)
            if (c.speakerId != wantSid) c.speakerId = wantSid
            bookContext = bookContext?.copy(second = com.earbook.app.tts.VoicePrefs.cacheKey(appContext))
        }
        // 真机验证修复：模型冷加载需 10-30s，初始化窗口内 core==null 时等待而非秒败
        // （否则服务侧连续 3 句秒败即熔断，永远等不到初始化完成）
        val engine = waitForCore(30_000) ?: run { onDone?.invoke(utteranceId, false); return }
        stopped.set(false)
        // M3-2 磁盘缓存命中路径：章缓存 sentenceAt 切句直接播（零合成 CPU）
        val cached = cachedSentenceFor(utteranceId)
        val cachedSr = if (cached != null) cachedSampleRateFor(utteranceId) else null
        currentWorker = Thread({
            var t: AudioTrack? = null
            try {
                var audio: FloatArray? = cached
                var sr = cachedSr ?: 0
                if (audio == null) {
                    val prefetched = synchronized(prefetchCache) { prefetchCache.remove(utteranceId) }
                    // 整句先合成再播：RTF>1 设备（真机实测 ~10）流式边合成边播必然中途 underrun，
                    // 且 Android 9 的 underrun 轨永不恢复 → 句子被掐断。数据全在手后写入永远领先播放头。
                    // 句间间隔（合成耗时）由预渲染缓存兜底（PrerenderManager/磁盘缓存路径）。
                    audio = prefetched ?: run {
                        if (stopped.get()) throw InterruptedException()
                        engine.synthesizeFull(text)
                    }
                    sr = engine.sampleRate()
                }
                // 模型对极短/非常规文本可能返回空——按跳过处理（推进不熔断）
                if (audio!!.isEmpty()) {
                    android.util.Log.w(TAG, "empty audio for $utteranceId, skip")
                    onDone?.invoke(utteranceId, true)
                    return@Thread
                }
                t = obtainTrack(sr).also { it.play() }
                val written = writeFloats(t!!, audio!!)
                val t0 = android.os.SystemClock.elapsedRealtime()
                drainAndFinish(t!!, written, utteranceId)
                android.util.Log.i(
                    TAG,
                    "speak $utteranceId: audio=${audio!!.size}f written=${written}f " +
                        "drain=${android.os.SystemClock.elapsedRealtime() - t0}ms"
                )
            } catch (e: InterruptedException) {
                currentTrack?.stop()
                onDone?.invoke(utteranceId, false)
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "speak $utteranceId failed", e)
                currentTrack?.stop()
                onDone?.invoke(utteranceId, false)
            } finally {
                t?.release()
                if (currentTrack === t) currentTrack = null
            }
        }, "sherpa-speak").also {
            // 真机修复：THREAD_PRIORITY_URGENT_AUDIO 是 Process.setThreadPriority 的 Linux nice 值(-19)，
            // 喂给 Thread.setPriority(1-10) 在 Android 9 真机抛 IllegalArgumentException（模拟器钳位不炸）
            it.start()
            try { Process.setThreadPriority(it.id.toInt(), Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) { }
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
        // 真机（Mi Note 3/Android 9）实测：track 播放中 underrun 会被系统直接停轨（flinger 状态 0x009），
        // playbackHeadPosition 随之冻结——若只比 head<written 会死循环卡死整条播放链。
        // 逃生舱：头部停滞 1.5s（轨死）即跳出，按已完成推进（句尾可能被截，优于整卡死）。
        var lastHead = -1
        var lastProgressMs = android.os.SystemClock.elapsedRealtime()
        while (!stopped.get() && t.playbackHeadPosition < written * 0.999) {
            Thread.sleep(60)
            val head = t.playbackHeadPosition
            if (head != lastHead) {
                lastHead = head
                lastProgressMs = android.os.SystemClock.elapsedRealtime()
            } else if (android.os.SystemClock.elapsedRealtime() - lastProgressMs > 1500) {
                break
            }
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

    fun setBookContext(bookId: String, voice: String = com.earbook.app.tts.VoicePrefs.cacheKey(appContext)) {
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

    /** FloatArray → AudioTrack（帧单位返回） */
    private fun writeFloats(t: AudioTrack, chunk: FloatArray): Int {
        if (chunk.isEmpty()) return 0
        // ENCODING_PCM_FLOAT 轨走原生 float[] write（返回帧数，无字节/帧换算歧义）
        val ret = t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
        if (ret < 0) android.util.Log.w(TAG, "AudioTrack.write err=$ret (${chunk.size}f)")
        return if (ret > 0) ret else 0
    }

    /** 预取缓存（句级内存）：容量保护——最多 2 句 */
    private val prefetchCache = HashMap<String, FloatArray>()

    private fun trimPrefetchLocked() {
        while (prefetchCache.size > 2) {
            prefetchCache.remove(prefetchCache.keys.first())
        }
    }

    private fun obtainTrack(sampleRate: Int): AudioTrack {
        // 每句新轨（不跨句复用）：复用同一 AudioTrack 时 playbackHeadPosition 是终身累计值，
        // 第 2 句起 drain 的「head vs written」比较即失效（真机实测连环假推进/0 帧交付）。
        // TTS 每句新轨是标准做法；轨的创建成本（~ms 级）远低于句级合成。
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
            // 2 秒缓冲：1 秒在慢速首写/系统抖动下仍会被 underrun 停轨（真机实测 0x009）
            .setBufferSizeInBytes(sampleRate * 4 * 2)
            .build()
        t.setVolume(volume)
        currentTrack = t
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
        currentTrack?.pause()
    }

    override fun shutdown() {
        stop()
        currentTrack?.release()
        currentTrack = null
        core?.release()
        core = null
    }

    override fun setOnDoneListener(listener: ((String, Boolean) -> Unit)?) { onDone = listener }
    override fun setOnInitListener(listener: ((Boolean) -> Unit)?) { onInit = listener }

    @Volatile private var volume = 1f
    override fun setVolume(volume: Float) {
        this.volume = volume
        currentTrack?.setVolume(volume)
    }
}
