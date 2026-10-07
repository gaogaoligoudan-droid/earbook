package com.earbook.app.playback

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log

/**
 * PCM(float32 mono) ↔ AAC 编解码（M1，R11 拍板 AAC 32kbps 缓存）。
 * - 编码：MediaCodec AAC-LC → ADTS 帧流（每帧 7 字节头）→ 独立 .aac 文件可直接解码
 * - 解码：流式（M2 章级单轨播放器用），也可一次性整章解回（M1 过渡期 get 路径）
 * - 兼容兜底：MediaCodec 不可用/异常由调用方降级 WAV（评估文档风险表）
 */
object AacCodec {

    private const val TAG = "AacCodec"
    const val DEFAULT_BITRATE = 32_000 // R11 拍板：32kbps 单声道语音

    /** float PCM → AAC ADTS 字节流。失败抛异常（调用方降级 WAV）。 */
    fun encodePcmToAacAdts(samples: FloatArray, sampleRate: Int, bitrate: Int = DEFAULT_BITRATE): ByteArray {
        // MediaCodec AAC 编码器普遍只收 16bit PCM（ENCODING_PCM_16BIT）
        val pcm16 = ByteArray(samples.size * 2)
        var i = 0
        for (s in samples) {
            val v = (s * 32767f).toInt().coerceIn(-32768, 32767)
            pcm16[i++] = (v and 0xff).toByte()
            pcm16[i++] = ((v shr 8) and 0xff).toByte()
        }
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val out = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inOff = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ib = codec.getInputBuffer(idx)!!
                        ib.clear()
                        val n = minOf(ib.capacity(), pcm16.size - inOff)
                        ib.put(pcm16, inOff, n)
                        inOff += n
                        val eos = inOff >= pcm16.size
                        codec.queueInputBuffer(idx, 0, n, 0, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (eos) inputDone = true
                    }
                }
                val oidx = codec.dequeueOutputBuffer(info, 10_000)
                if (oidx >= 0) {
                    val ob = codec.getOutputBuffer(oidx)!!
                    // 原始 AAC 裸帧（无 ADTS）——补 7 字节 ADTS 头成独立可解码帧
                    val frameLen = info.size + 7
                    out.write(adtsHeader(sampleRate, 1, frameLen))
                    out.write(ob.array(), ob.position(), info.size)
                    codec.releaseOutputBuffer(oidx, false)
                } else if (oidx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    // 编码器消化中，继续轮询
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
            return out.toByteArray()
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /** ADTS 头（AAC-LC，44.1k 表中 24000Hz=索引6；1 通道） */
    private fun adtsHeader(sampleRate: Int, channels: Int, frameLen: Int): ByteArray {
        val srIdx = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
            44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
            16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 4
        }
        val full = frameLen
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), // syncword, MPEG-4, no CRC
            ((1 shl 6) or (srIdx shl 2) or ((channels shr 2) and 0x1)).toByte(), // profile=LC(1)
            (((channels and 0x3) shl 6) or ((full shr 11) and 0x3)).toByte(),
            ((full shr 3) and 0xFF).toByte(),
            (((full and 0x7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    /**
     * 整章 .aac（ADTS 流）→ float PCM。M1 过渡期 get() 用；
     * M2 章级单轨播放改用流式 [AacAdtsStreamDecoder]。
     * @return null=解码失败（调用方视为缓存无效）
     */
    fun decodeAacAdtsToPcm(aac: ByteArray, expectedSampleRate: Int): FloatArray? {
        try {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, expectedSampleRate, 1).apply {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            codec.configure(fmt, null, null, 0)
            codec.start()
            val out = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inOff = 0
            var inputDone = false
            var outputDone = false
            var outputFormat: MediaFormat? = null
            while (!outputDone) {
                if (!inputDone) {
                    // 按 ADTS 帧切（头 7B + 长度字段）
                    val frame = nextAdtsFrame(aac, inOff) ?: run { inputDone = true; null }
                    if (frame != null) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val ib = codec.getInputBuffer(idx)!!
                            ib.clear()
                            ib.put(aac, frame.first, frame.second)
                            inOff = frame.first + frame.second
                            val eos = inOff >= aac.size || nextAdtsFrame(aac, inOff) == null
                            codec.queueInputBuffer(idx, 0, frame.second, 0, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (eos) inputDone = true
                        }
                    }
                }
                val oidx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oidx >= 0 -> {
                        val ob = codec.getOutputBuffer(oidx)!!
                        out.write(ob.array(), ob.position(), info.size)
                        codec.releaseOutputBuffer(oidx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                }
            }
            runCatching { codec.stop() }; runCatching { codec.release() }
            // 16bit → float
            val b = out.toByteArray()
            val n = b.size / 2
            val f = FloatArray(n)
            for (j in 0 until n) {
                val v = ((b[j * 2].toInt() and 0xff) or (b[j * 2 + 1].toInt() shl 8)).toShort()
                f[j] = v / 32767f
            }
            return f
        } catch (e: Exception) {
            Log.w(TAG, "AAC decode failed: ${e.message}")
            return null
        }
    }

    /** @return (offset, length) of ADTS frame at/after pos；null=无更多帧 */
    private fun nextAdtsFrame(data: ByteArray, pos: Int): Pair<Int, Int>? {
        var p = pos
        while (p + 7 <= data.size) {
            if (data[p].toInt() == 0xFF && (data[p + 1].toInt() and 0xF0) == 0xF0) {
                val len = (((data[p + 3].toInt() and 0x3) shl 11) or
                    ((data[p + 4].toInt() and 0xFF) shl 3) or
                    ((data[p + 5].toInt() and 0xE0) shr 5))
                if (len in 7..(data.size - p)) return p to len
            }
            p++
        }
        return null
    }
}
