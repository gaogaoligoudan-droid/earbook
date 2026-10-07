package com.earbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.earbook.app.playback.ChapterAudioCache
import com.earbook.app.playback.ChapterPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M2-b 章级单轨播放器验证：
 * 合成 PCM 直写缓存（不依赖 TTS 引擎）→ ChapterPlayer 整章单轨播放：
 * 1) 句边界回调按序触发（0→1→2）
 * 2) 整章播完回调 onChapterDone
 * 3) AAC 流式解码路径贯通（put 走 AAC 编码，play 走 M2-a 流式解码）
 */
@RunWith(AndroidJUnit4::class)
class ChapterPlayerTest {

    @Test
    fun sentenceBoundaryAlignment() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = ChapterAudioCache(ctx)
        val key = ChapterAudioCache.Key("cp-test-book", 0, "kokoro30")
        val sr = 24000
        // 3 句 × 1 秒；非零 PCM（全零可能被编码器异常压缩）
        val total = FloatArray(sr * 3) { ((it % 100) / 100f) - 0.5f }
        val offsets = longArrayOf(0, sr.toLong(), (2 * sr).toLong())
        cache.put(key, sr, total, offsets)
        assertTrue("缓存写入失败", cache.has(key))

        val player = ChapterPlayer(cache)
        val entered = Collections.synchronizedList(mutableListOf<Int>())
        val done = CountDownLatch(1)
        var errorMsg: String? = null
        try {
            player.play(key, 0, object : ChapterPlayer.Callbacks {
                override fun onSentenceEnter(sentence: Int) { entered.add(sentence) }
                override fun onChapterDone() { done.countDown() }
                override fun onError(msg: String) {
                    errorMsg = msg
                    done.countDown()
                }
            })
            assertTrue("章节播放超时（error=$errorMsg）", done.await(30, TimeUnit.SECONDS))
            assertEquals("句边界回调序列", listOf(0, 1, 2), entered.toList())
        } finally {
            player.stop()
            cache.remove(key)
        }
    }
}
