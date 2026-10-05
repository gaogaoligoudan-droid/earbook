package com.earbook.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceWindowTest {

    @Test
    fun `初始预取前N句`() {
        val w = SentenceWindow(totalSentences = 10, targetLead = 4)
        assertEquals(0, w.nextToSynthesize(emptySet()))
        assertEquals(1, w.nextToSynthesize(setOf(0)))
        // 窗口 (playedTo, playedTo+lead] = [0,3] 全就绪 → null
        assertEquals(null, w.nextToSynthesize(setOf(0, 1, 2, 3)))
    }

    @Test
    fun `播放推进窗口滑动`() {
        val w = SentenceWindow(totalSentences = 100, targetLead = 4)
        w.onPlayed(20)
        // 已播到 20 → 窗口 [21,24]；21-23 已合成 → 补 24
        assertEquals(24, w.nextToSynthesize(setOf(21, 22, 23)))
        assertEquals(null, w.nextToSynthesize((21..24).toSet()))
    }

    @Test
    fun `尾部不越界`() {
        val w = SentenceWindow(totalSentences = 6, targetLead = 4)
        w.onPlayed(4)
        assertEquals(5, w.nextToSynthesize(emptySet()))
        assertNull(w.nextToSynthesize(setOf(5)))
    }

    @Test
    fun `全部就绪返回null`() {
        val w = SentenceWindow(totalSentences = 3, targetLead = 4)
        assertNull(w.nextToSynthesize((0..2).toSet()))
    }
}
