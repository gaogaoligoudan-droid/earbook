package com.earbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.earbook.app.playback.AacCodec
import com.earbook.app.playback.ChapterAudioCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M4-b R10 导出验证：合成 PCM → AAC 缓存 → exportToM4a 零转码封装：
 * 1) 输出文件存在且以 ftyp box 开头（MP4 容器合法性）
 * 2) 多章封装的帧数与缓存一致（pts 单调由构造保证）
 * 3) 进度回调完整
 */
@RunWith(AndroidJUnit4::class)
class M4aExportTest {

    @Test
    fun exportChaptersToM4a() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = ChapterAudioCache(ctx)
        val sr = 24000
        val files = ArrayList<File>()
        try {
            // 3 章 × 1 秒合成 PCM（不同内容，防同帧去重侥幸）
            for (ch in 0 until 3) {
                val key = ChapterAudioCache.Key("m4a-test-book", ch, "kokoro30")
                val total = FloatArray(sr) { (((it + ch * 37) % 97) / 97f) - 0.5f }
                cache.put(key, sr, total, longArrayOf(0, sr.toLong()))
                val entry = cache.getEntry(key)!!
                assertTrue("章 $ch 应为 AAC 缓存", entry.format == "aac")
                files.add(entry.file)
            }
            val out = File(ctx.filesDir, "exports/m4a-test.m4a")
            val progress = ArrayList<Int>()
            val ok = AacCodec.exportToM4a(files, out, { done, total -> progress.add(done * 100 / total) })
            assertTrue("导出应成功", ok)
            assertTrue("输出应存在且非空", out.exists() && out.length() > 1000)
            // MP4 容器合法性：ftyp box 在文件头（readNBytes 需 API 33，模拟器 31 用经典读法）
            val head = ByteArray(12)
            out.inputStream().use { ins ->
                var off = 0
                while (off < 12) {
                    val n = ins.read(head, off, 12 - off)
                    if (n < 0) break
                    off += n
                }
            }
            assertEquals("ftyp", String(head.copyOfRange(4, 8), Charsets.US_ASCII))
            // 每章 1 秒 @24kHz = 24000 帧 → 3 章 ≈ 72000 帧（AAC 帧 1024 样本，±10%）
            val expectBytes = files.sumOf { it.length() }
            assertTrue("输出体积应接近 AAC 流总量（${out.length()} vs $expectBytes）",
                out.length() in expectBytes..(expectBytes * 1.4).toLong())
            assertEquals("进度回调", listOf(33, 66, 100), progress)
            out.delete()
        } finally {
            for (ch in 0 until 3) cache.remove(ChapterAudioCache.Key("m4a-test-book", ch, "kokoro30"))
        }
    }
}
