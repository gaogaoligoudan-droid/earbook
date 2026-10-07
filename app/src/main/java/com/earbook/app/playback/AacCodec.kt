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
            var outputStall = 0 // EOS 喂完后连续零输出计数（wedge 看门狗）
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
                    outputStall = 0
                    // 首个输出 buffer 是 csd-0（AudioSpecificConfig，真机实测 2B "13 08"=24kHz/mono/LC），
                    // 带 CODEC_CONFIG 标志——套上 ADTS 头会变成垃圾帧，Android 9 解码器吃下后
                    // wedge（不输出/不报错/EOS 不回，M1-g 首跑 2026-10-08 实锤），必须跳过
                    val isCsd = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isCsd && info.size > 0) {
                        val ob = codec.getOutputBuffer(oidx)!!
                        // 原始 AAC 裸帧（无 ADTS）——补 7 字节 ADTS 头成独立可解码帧
                        // 注意：Android 9 的 getOutputBuffer 是 direct buffer，禁止 array()（真机实测 UOE）
                        val frameLen = info.size + 7
                        out.write(adtsHeader(sampleRate, 1, frameLen))
                        val tmp = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.get(tmp, 0, info.size)
                        out.write(tmp)
                    }
                    codec.releaseOutputBuffer(oidx, false)
                } else if (oidx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    // 编码器消化中；但 EOS 喂完后长时间零输出 = 编码器 wedge，按失败处理
                    // （调用方降级 WAV）而非无限挂
                    if (++outputStall > 500) throw IllegalStateException("AAC 编码器 5s 零输出（EOS 已喂完），判定 wedge")
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
     * 从 ADTS 头推导 csd-0（AudioSpecificConfig）。
     * csd-0 + 裸帧是 Android 全版本通吃的标准解码配方（KEY_IS_ADTS in-band 在 OMX 软解上
     * 兼容性存疑——真机两轮挂死期间 in-band 路径从未被验证到喂帧环节，见下）。
     * 推导验证：24kHz/mono/LC 头 0x58 0x40 → ASC 0x13 0x08（与真机编码器 csd 输出逐字节吻合）。
     */
    private fun aacCsd0FromAdts(adts: ByteArray): ByteArray? {
        if (adts.size < 7 || adts[0] != 0xFF.toByte() || (adts[1].toInt() and 0xF0) != 0xF0) return null
        val profile = (adts[2].toInt() and 0xC0) shr 6          // ADTS profile 字段 = AOT-1
        val srIdx = (adts[2].toInt() and 0x3C) shr 2
        val ch = ((adts[2].toInt() and 0x01) shl 2) or ((adts[3].toInt() and 0xC0) shr 6)
        return byteArrayOf(
            (((profile + 1) shl 3) or (srIdx shr 1)).toByte(),
            (((srIdx and 1) shl 7) or (ch shl 3)).toByte(),
        )
    }

    /** ADTS 头长度：protection_absent=0 → 9 字节（含 CRC）；我们编码器恒写 7 */
    private fun adtsHeaderLen(firstTwo: Int): Int = if (firstTwo and 0x01 != 0) 7 else 9

    /**
     * 整章 .aac（ADTS 流）→ float PCM。M1 过渡期 get() 用；
     * M2 章级单轨播放改用流式 [AacAdtsStreamDecoder]。
     * @return null=解码失败（调用方视为缓存无效）
     */
    fun decodeAacAdtsToPcm(aac: ByteArray, expectedSampleRate: Int): FloatArray? {
        try {
            // 首帧推导 csd-0（OMX 软解必需）；喂帧一律剥 ADTS 头
            val first = nextAdtsFrame(aac, 0) ?: return null
            val csd = aacCsd0FromAdts(aac.copyOfRange(first.first, first.first + first.second)) ?: return null
            val hdrLen = adtsHeaderLen(aac[first.first + 1].toInt())
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, expectedSampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
            }
            codec.configure(fmt, null, null, 0)
            codec.start()
            val out = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inOff = first.first
            var inputDone = false
            var outputDone = false
            var outputFormat: MediaFormat? = null
            var outputStall = 0 // EOS 喂完后连续零输出计数（wedge 看门狗——首跑实锤的挂死形态）
            while (!outputDone) {
                if (!inputDone) {
                    // 按 ADTS 帧定位，剥头喂裸帧
                    val frame = nextAdtsFrame(aac, inOff) ?: run { inputDone = true; null }
                    if (frame != null) {
                        val rawLen = frame.second - hdrLen
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val ib = codec.getInputBuffer(idx)!!
                            ib.clear()
                            ib.put(aac, frame.first + hdrLen, rawLen)
                            inOff = frame.first + frame.second
                            val eos = inOff >= aac.size || nextAdtsFrame(aac, inOff) == null
                            codec.queueInputBuffer(idx, 0, rawLen, 0, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (eos) inputDone = true
                        }
                    }
                }
                val oidx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oidx >= 0 -> {
                        outputStall = 0
                        val ob = codec.getOutputBuffer(oidx)!!
                        // direct buffer（Android 9）：禁 array()，走 position/limit + get
                        val tmp = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.get(tmp, 0, info.size)
                        out.write(tmp)
                        codec.releaseOutputBuffer(oidx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    oidx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone ->
                        if (++outputStall > 500) throw IllegalStateException("AAC 解码器 5s 零输出（EOS 已喂完），判定 wedge")
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

    /**
     * 流式 ADTS 解码（M2-b 章级单轨播放器专用）：
     * 边解边回调 16bit PCM 块（每 ADTS 帧 1024 样本 ≈ 2KB@24kHz），调用方直接写 AudioTrack——
     * 整章 float 不再载入内存（9 分钟章 ≈52MB float 不可接受，M1 的 get() 全量路径仅过渡期用）。
     * 输出格式 = 16bit PCM（AAC 解码原生），播放轨用 ENCODING_PCM_16BIT 零转换直写。
     * @param shouldStop 协作式取消（ChapterPlayer stop 时置位）；true=立即停止
     * @return 已解码样本数（16bit 样本）；-1=解码器初始化失败
     */
    fun decodeAdtsStream(
        input: java.io.InputStream,
        sampleRate: Int,
        onPcm16: (ByteArray) -> Unit,
        shouldStop: () -> Boolean = { false },
    ): Long {
        val codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: Exception) {
            Log.w(TAG, "AAC decoder create failed: ${e.message}")
            return -1
        }
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        var totalSamples = 0L
        try {
            val info = MediaCodec.BufferInfo()
            // 滑动缓冲：readPos 之前已消费；缓冲过大时向头压缩（帧级消费速率，32KB 阈值足够）
            var acc = ByteArray(0)
            var readPos = 0
            var streamEos = false
            var inputEos = false
            var outputDone = false
            var configured = false // OMX 软解需首帧推导 csd-0 后才能 configure（懒配置）
            var hdrLen = 7
            var outputStall = 0 // EOS 喂完后连续零输出计数（wedge 看门狗）
            val chunk = ByteArray(64 * 1024)
            fun compactLocked() {
                if (readPos > 32 * 1024) {
                    acc = acc.copyOfRange(readPos, acc.size)
                    readPos = 0
                }
            }
            while (!outputDone && !shouldStop()) {
                // ── 进料：流→acc；acc→ADTS 帧（剥头）→codec ──
                if (!inputEos && acc.size - readPos < 32 * 1024) {
                    val n = input.read(chunk)
                    if (n < 0) streamEos = true else acc += chunk.copyOf(n)
                }
                if (!configured) {
                    // 懒配置：拿到首个完整 ADTS 帧后推导 csd-0（OMX.google.aac.decoder 不支持 in-band ADTS）
                    val first = nextAdtsFrame(acc, readPos)
                    if (first != null) {
                        val csd = aacCsd0FromAdts(acc.copyOfRange(first.first, first.first + first.second))
                        if (csd == null) {
                            Log.w(TAG, "ADTS 首帧无 syncword，判定非 AAC 流")
                            return -1
                        }
                        hdrLen = adtsHeaderLen(acc[first.first + 1].toInt())
                        fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
                        codec.configure(fmt, null, null, 0)
                        codec.start()
                        configured = true
                    } else if (streamEos) {
                        Log.w(TAG, "流尽仍未找到 ADTS 帧")
                        return -1
                    }
                }
                if (configured && !inputEos) {
                    val frame = nextAdtsFrame(acc, readPos)
                    if (frame != null) {
                        val (foff, flen) = frame
                        val rawLen = flen - hdrLen
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val ib = codec.getInputBuffer(idx)!!
                            ib.clear()
                            ib.put(acc, foff + hdrLen, rawLen)
                            readPos = foff + flen
                            compactLocked()
                            // EOS 判定：流尽且缓冲里没有下一个完整帧
                            val lastFrame = streamEos && nextAdtsFrame(acc, readPos) == null
                            codec.queueInputBuffer(
                                idx, 0, rawLen, 0,
                                if (lastFrame) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            )
                            if (lastFrame) inputEos = true
                        }
                    } else if (streamEos) {
                        // 流尽且无完整帧：剩余垃圾（半个头）直接 EOS
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        }
                    }
                }
                // ── 出料：16bit PCM 块回调 ──
                val oidx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oidx >= 0 -> {
                        outputStall = 0
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(oidx)!!
                            // direct buffer（Android 9）：禁 array()（真机实测 UOE）
                            val tmp = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            ob.get(tmp, 0, info.size)
                            onPcm16(tmp)
                            totalSamples += info.size / 2
                        }
                        codec.releaseOutputBuffer(oidx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* 采样率固定 24k，忽略 */ }
                    oidx == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos ->
                        // EOS 喂完后长时间零输出 = 解码器 wedge（M1-g 首跑实锤），按失败退出
                        if (++outputStall > 500) throw IllegalStateException("AAC 流式解码器 5s 零输出（EOS 已喂完），判定 wedge")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AAC stream decode aborted at $totalSamples samples: ${e.message}")
            if (totalSamples == 0L) return -1
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        return totalSamples
    }

    /** @return (offset, length) of ADTS frame at/after pos；null=无更多帧 */
    private fun nextAdtsFrame(data: ByteArray, pos: Int): Pair<Int, Int>? {
        var p = pos
        while (p + 7 <= data.size) {
            // 注意：Byte 有符号，0xFF.toByte().toInt() == -1——必须按 Byte 比较或 and 0xFF
            if (data[p] == 0xFF.toByte() && (data[p + 1].toInt() and 0xF0) == 0xF0) {
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
