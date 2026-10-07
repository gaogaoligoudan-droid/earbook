package com.earbook.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.earbook.app.playback.ChapterAudioCache
import com.earbook.app.tts.ModelManager
import com.earbook.app.tts.SherpaTtsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AAC 缓存往返验证（M1-g 交付门禁）：
 * 合成 2 句 → put（AAC 32k 编码落盘）→ get（解码读回）：
 * 1) 体积 < 等效 WAV 的 15%（R11 压缩收益）
 * 2) 帧数偏差 ±10%（AAC 帧对齐/编码器延迟余量）
 * 3) 句偏移表完整、句级切回非空（M2 章级单轨播放的原料）
 */
@RunWith(AndroidJUnit4::class)
class AacRoundTripTest {

    companion object { private const val TAG = "AacRoundTrip" }

    @Test
    fun aacRoundTrip() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ModelManager.installIfNeeded(ctx)
        val engine = SherpaTtsEngine(ctx, ModelManager.modelDir(ctx))
        try {
            val s1 = engine.synthesizeFull("你好，欢迎收听这一本测试书。")
            val s2 = engine.synthesizeFull("这是第二个句子，用来验证章内偏移量是否正确。")
            assertTrue("合成失败", s1.size > 1000 && s2.size > 1000)
            val sr = engine.sampleRate()

            val cache = ChapterAudioCache(ctx)
            val key = ChapterAudioCache.Key("aac-test-book", 0, "kokoro30", 0)
            cache.removeBook("aac-test-book")
            val total = s1 + s2
            val offsets = longArrayOf(0, s1.size.toLong(), total.size.toLong())
            cache.put(key, sr, total, offsets)

            // 1) 体积压缩
            val f = cache.existingFile(key)!!
            val wavEq = total.size * 4L
            assertTrue(
                "体积未达压缩预期: ${f.length()}B vs 等效WAV ${wavEq}B",
                f.length() < wavEq * 0.15
            )

            // 2) 读回帧数
            val back = cache.get(key)
            assertTrue("读回失败", back != null)
            back!!
            assertEquals(sr, back.sampleRate)
            val ratio = back.samples.size.toDouble() / total.size
            assertTrue("帧数偏差过大: $ratio", ratio in 0.90..1.10)

            // 3) 偏移表与句级切回
            assertEquals(3, back.sentenceOffsets.size)
            val s2back = back.sentenceAt(1)
            assertTrue("句级切回为空", s2back != null && s2back.size > 1000)

            Log.i(
                TAG,
                "AAC 往返: frames=${back.samples.size}/${total.size} size=${f.length()}B " +
                    "(等效WAV ${wavEq}B, 压缩比 ${f.length() * 100 / wavEq}%)"
            )
        } finally {
            engine.release()
        }
    }
}
