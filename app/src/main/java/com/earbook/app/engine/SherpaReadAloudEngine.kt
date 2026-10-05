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
        currentWorker = Thread({
            try {
                val sr = engine.sampleRate()
                val t = obtainTrack(sr)
                t.play()
                // 预取命中 → 直接播整句（合成等待归零）
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
                // 等播放追上写入（buffer drain）
                while (!stopped.get() &&
                    t.playbackHeadPosition < written * 0.999 // float 采样数即帧数
                ) {
                    Thread.sleep(60)
                }
                t.stop()
                onDone?.invoke(utteranceId, !stopped.get())
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
