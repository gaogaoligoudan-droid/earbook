package com.earbook.app.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 章级单轨播放器（M2，R6 终拍板「缓存命中整章一条 AudioTrack 播到底」）：
 * - 数据源流式：AAC 逐帧解码直写 / WAV float32 块读直写——全章 PCM 不进内存
 *   （历史教训内化：逐句换轨是 drain/head 对位坑的根源；单轨 head 单调累计正是
 *   句偏移对位需要的语义，M4 变速改 playbackParams 也不破坏对位）
 * - 句边界：播放头跨 sentenceOffsets[i+1] → onSentenceEnter(i)（按听感推进，非写入量）
 * - 起播句：解码丢弃前 N 帧（seek 到句边界，断点续听精确）
 * - 暂停/恢复：同轨 pause()/play()（写线程随缓冲满自然停驻，恢复即续）
 * - 输出 16bit PCM：AAC 解码原生，WAV 降级路径做 float→int16 转换（语音透明）
 */
class ChapterPlayer(private val cache: ChapterAudioCache) {

    interface Callbacks {
        /** 句 i 开始播放（含起播句） */
        fun onSentenceEnter(sentence: Int)
        fun onChapterDone()
        fun onError(msg: String)
    }

    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private val stopped = AtomicBoolean(true)
    @Volatile private var volume = 1f

    val isPlaying: Boolean
        get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    fun play(key: ChapterAudioCache.Key, startSentence: Int, cb: Callbacks) {
        stop()
        val entry = cache.getEntry(key) ?: run { cb.onError("章缓存缺失"); return }
        if (entry.sentenceOffsets.isEmpty()) { cb.onError("句偏移表缺失"); return }
        stopped.set(false)
        val t = buildTrack(entry.sampleRate).also { it.setVolume(volume) }
        track = t
        worker = Thread({
            try {
                streamToTrack(t, entry, startSentence, cb)
            } catch (e: Exception) {
                if (!stopped.get()) cb.onError("章播放失败: ${e.message}")
            } finally {
                runCatching { t.stop() }
                runCatching { t.release() }
                if (track === t) track = null
            }
        }, "ChapterPlayer").also { it.start() }
    }

    fun pause() {
        runCatching { track?.pause() }
    }

    fun resume() {
        runCatching { track?.play() }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        val t = track
        worker?.let { w ->
            runCatching { t?.stop() } // 解除 WRITE_BLOCKING 停驻
            try { w.join(1000) } catch (_: InterruptedException) {}
            w.interrupt()
        }
        worker = null
        track = null
    }

    fun setVolume(v: Float) {
        volume = v
        runCatching { track?.setVolume(v) }
    }

    // ── 内部：流式泵 ─────────────────────────────────────

    private fun buildTrack(sr: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes((minBuf * 4).coerceAtLeast(16384))
            .build()
    }

    private fun streamToTrack(
        t: AudioTrack,
        entry: ChapterAudioCache.Entry,
        startSentence: Int,
        cb: Callbacks,
    ) {
        val offsets = entry.sentenceOffsets
        val totalFrames = if (entry.frames > 0) entry.frames else offsets.last()
        val seekBase = (offsets.getOrNull(startSentence) ?: 0L).coerceAtMost(totalFrames)

        var lastSentence = startSentence
        t.play()
        cb.onSentenceEnter(lastSentence)

        fun advance() {
            val head = t.playbackHeadPosition.toLong() + seekBase
            while (lastSentence + 1 < offsets.size && head >= offsets[lastSentence + 1]) {
                lastSentence++
                cb.onSentenceEnter(lastSentence)
            }
        }

        fun writeAll(chunk: ByteArray, len: Int) {
            var off = 0
            while (off < len && !stopped.get()) {
                val n = t.write(chunk, off, len - off, AudioTrack.WRITE_BLOCKING)
                if (n < 0) throw RuntimeException("AudioTrack.write=$n")
                off += n
                advance()
            }
        }

        when (entry.format) {
            "aac" -> {
                val skipBytes: Long = seekBase * 2 // 16bit
                var skipped = 0L
                FileInputStream(entry.file).use { input ->
                    val rc = AacCodec.decodeAdtsStream(
                        input, entry.sampleRate,
                        { chunk ->
                            var c = chunk
                            if (skipped < skipBytes) {
                                val remain = (skipBytes - skipped).toInt()
                                if (c.size <= remain) { skipped += c.size; return@decodeAdtsStream }
                                c = c.copyOfRange(remain, c.size)
                                skipped = skipBytes
                            }
                            writeAll(c, c.size)
                        },
                        shouldStop = { stopped.get() },
                    )
                    if (rc < 0) throw RuntimeException("AAC 解码失败")
                }
            }
            else -> { // wav（降级缓存）：44B 头后 float32 LE，块读块转
                RandomAccessFile(entry.file, "r").use { raf ->
                    raf.seek(44 + seekBase * 4)
                    val fb = ByteArray(65536)
                    val out = ByteArray(65536 / 2)
                    while (!stopped.get()) {
                        val n = raf.read(fb)
                        if (n <= 0) break
                        val samples = n / 4
                        var o = 0
                        for (i in 0 until samples) {
                            val bits = (fb[i * 4].toInt() and 0xff) or
                                ((fb[i * 4 + 1].toInt() and 0xff) shl 8) or
                                ((fb[i * 4 + 2].toInt() and 0xff) shl 16) or
                                ((fb[i * 4 + 3].toInt() and 0xff) shl 24)
                            val f = java.lang.Float.intBitsToFloat(bits)
                            val v = (f * 32767f).toInt().coerceIn(-32768, 32767)
                            out[o++] = (v and 0xff).toByte()
                            out[o++] = ((v shr 8) and 0xff).toByte()
                        }
                        writeAll(out, samples * 2)
                    }
                }
            }
        }

        // 尾部排空：数据写完 ≠ 播完，等播放头走完最后一帧。
        // sidecar frames 与 AAC 解码实际帧数有编码器延迟级偏差（±10% 内），
        // 播放中停走 1s（排除暂停态）视为到达实际 EOF，防死循环。
        var lastHead = -1L
        var stall = 0
        while (!stopped.get()) {
            advance()
            val head = t.playbackHeadPosition.toLong() + seekBase
            if (head >= totalFrames) break
            if (head == lastHead && t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                if (++stall >= 20) break
            } else {
                stall = 0
                lastHead = head
            }
            Thread.sleep(50)
        }
        if (!stopped.get()) cb.onChapterDone()
    }
}
