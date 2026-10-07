package com.earbook.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 声音试听器：按序播放 files/voice-samples/ 下的 WAV（16bit PCM mono 24k，沙箱生成），
 * 每段之间 1.2s 静音。logcat 标记「VOICE-SAMPLE: 正在播放 x」供远程对时。
 * 用于人工选音色（用户在场听，手机无播放器 App 的替代通道）。
 */
@RunWith(AndroidJUnit4::class)
class VoiceSamplerTest {

    @Test
    fun playAll() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // 可点播：am instrument -e samples 05,07,10 只播指定编号（不传=全播）
        val wanted = InstrumentationRegistry.getArguments()
            .getString("samples")?.split(",")?.map { it.trim() }?.toSet()
        val dir = File(ctx.filesDir, "voice-samples")
        // 扫目录按文件名序播放（不锁死清单，任意批次样本即推即播）
        val files = dir.listFiles { f -> f.name.endsWith(".wav") }
            ?.sortedBy { it.name } ?: emptyList()
        for (f in files) {
            if (!wanted.isNullOrEmpty() && f.name.substringBefore("-") !in wanted) continue
            val name = f.name.removeSuffix(".wav")
            Log.i(TAG, "VOICE-SAMPLE: 正在播放 $name")
            // 标准 44 字节 WAV 头（python wave 模块产物）
            val raw = f.readBytes()
            val data = raw.copyOfRange(44, raw.size)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(24000 * 2 * 2)
                .build()
            t.play()
            var off = 0
            while (off < data.size) {
                val n = t.write(data, off, data.size - off, AudioTrack.WRITE_BLOCKING)
                if (n <= 0) break
                off += n
            }
            // 等播完（16bit mono：1帧=2字节）
            while (t.playbackHeadPosition < data.size / 2 - 8) Thread.sleep(60)
            t.stop(); t.release()
            Thread.sleep(1200)
        }
        Log.i(TAG, "VOICE-SAMPLE: all done")
    }

    companion object { private const val TAG = "VoiceSampler" }
}
