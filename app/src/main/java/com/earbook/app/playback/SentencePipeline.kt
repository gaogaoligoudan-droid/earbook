package com.earbook.app.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * 句级流水：内存播放缓冲队列（消灭句间卡顿）。
 *
 * 架构（讨论方案 §M2-2）：
 * - [producer] 逐句调用引擎合成（句内流式回调直接写 AudioTrack，不等整句）
 * - 播放与合成分工：合成线程不断补货，播放端消费 AudioTrack 队列
 * - 缓冲深度 [targetLead]：预合成 N 句，句间零等待
 * - 句间静音 gap 由本层插入（比引擎静音更可控）
 *
 * 纯 Kotlin 可 JVM 单测部分：SentenceWindow 状态机（无 Android 依赖）。
 */
class SentencePipeline(
    private val sampleRate: Int,
    private val channelCount: Int = 1,
    private val targetLead: Int = 4, // 预合成句数（讨论定 2-6，默认 4）
) {
    /** 播放状态（供 UI/服务观察） */
    @Volatile var playing = false
        private set

    private var track: AudioTrack? = null
    private val lock = Object()

    private fun buildTrack(): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .build()
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(sampleRate * 4) // 1 秒缓冲
        .build()

    /** 播放一块 PCM（float mono [-1,1]）；句内流式回调直呼此方法 */
    fun writeChunk(samples: FloatArray) {
        val t = track ?: return
        t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
    }

    /**
     * 句间 gap：静音插入（毫秒）——情感/节奏控制的落点之一
     */
    fun sentenceGap(ms: Int) {
        val silence = FloatArray(sampleRate * ms / 1000)
        writeChunk(silence)
    }

    fun start() {
        synchronized(lock) {
            if (playing) return
            track = buildTrack().also { it.play() }
            playing = true
        }
    }

    fun pause() {
        synchronized(lock) {
            track?.pause()
        }
    }

    fun resume() {
        synchronized(lock) {
            track?.play()
        }
    }

    /** 播放端变速（0.75-1.5× 缓存复用路线；合成端 speed 恒 1.0） */
    fun setSpeed(speed: Float) {
        synchronized(lock) {
            track?.let { t ->
                val p = t.playbackParams
                p.speed = speed
                // pitch 保持音调（timed pitch 不变声线）
                t.playbackParams = p
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            playing = false
            track?.let { it.stop(); it.release() }
            track = null
        }
    }

    /** 队列水位：当前 AudioTrack 剩余帧数（缓冲健康度观测点） */
    fun bufferedFrames(): Int = track?.let { it.playbackHeadPosition - it.underrunCount } ?: 0
}

/**
 * 句窗状态机：决定「该合成第几句」——纯逻辑，JVM 可测。
 * 规则：播放位置 + targetLead ≥ 句索引 → 需要合成
 */
class SentenceWindow(
    private val totalSentences: Int,
    private val targetLead: Int = 4,
) {
    private var playedTo = -1 // 已提交播放的句索引

    /** 播放推进到句 idx（AudioTrack 消费完该句时回调） */
    fun onPlayed(idx: Int) { playedTo = idx }

    /** 下一个需要预合成的句索引（null=全部就绪） */
    fun nextToSynthesize(already: Set<Int>): Int? {
        for (i in (playedTo + 1)..minOf(playedTo + targetLead, totalSentences - 1)) {
            if (i !in already) return i
        }
        return null
    }
}
