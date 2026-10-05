package com.earbook.app.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun `水印行被清除`() {
        val raw = """
            第一章 初见
            
            更多小说请访问 www.xiaoshuo.com
            本章未完，点击下一页继续阅读
            林深第一次见到她，是在那个下雨的傍晚。
            
            求收藏求月票
        """.trimIndent()
        val cleaned = TextNormalizer.clean(raw)
        assertFalse(cleaned.contains("xiaoshuo"))
        assertFalse(cleaned.contains("本章未完"))
        assertFalse(cleaned.contains("求收藏"))
        assertTrue(cleaned.contains("林深第一次见到她"))
    }

    @Test
    fun `重复页眉被清除`() {
        val raw = buildString {
            repeat(4) {
                append("第1章 初见\n\n")
                append("正文内容来了第${it + 1}段。\n\n")
            }
        }
        val cleaned = TextNormalizer.clean(raw)
        // 「第1章 初见」出现 4 次 → 页眉，全清
        assertFalse(cleaned.contains("第1章 初见"))
        assertTrue(cleaned.contains("正文内容来了"))
    }

    @Test
    fun `正常正文不被误伤`() {
        val raw = "他说：三点了，走吧。她说好。\n两人沿着河走了很久。"
        val cleaned = TextNormalizer.clean(raw)
        assertEquals(raw, cleaned)
    }

    @Test
    fun `句内URL剔除保留正文`() {
        val raw = "详情看 example.com/forum 这帖子的第三节。"
        val cleaned = TextNormalizer.clean(raw)
        assertFalse(cleaned.contains("example.com"))
        assertTrue(cleaned.contains("这帖子的第三节"))
    }
}
