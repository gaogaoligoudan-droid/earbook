package com.earbook.app.playback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * 章块音频缓存（磁盘层，讨论方案 §M2-3）：
 * - 存储：每章一个 WAV 文件 + 句偏移 sidecar JSON（时间索引）
 * - LRU：按「最后访问时间」淘汰，**正在听的书锚定不清理**
 * - 容量上限：滑块（100-300MB）→ 超限即时静默清理
 * - 缓存 key 四维：bookId / chapterIndex / 音色 / 优化版本（语速除外——播放端变速）
 *
 * WAV float32 单声道（与 AudioTrack ENCODING_PCM_FLOAT 直接对接）。
 */
class ChapterAudioCache(private val context: Context) {

    private fun root(): File = File(context.filesDir, "tts-cache").apply { mkdirs() }

    /** 缓存 key——四维（语速不进 key：播放端变速复用） */
    data class Key(
        val bookId: String,
        val chapterIndex: Int,
        val voice: String,        // 音色标识（speakerId 或系统引擎名）
        val textVersion: Int = 0, // 优化版本（原文=0，AI 优化后递增）
    )

    fun fileFor(key: Key): File = File(root(), "${key.bookId}_${key.chapterIndex}_${key.voice}_${key.textVersion}.wav")

    fun has(key: Key): Boolean = fileFor(key).exists()

    /**
     * 写入一章：samples 全量 + 每句帧偏移表。
     * 写临时文件后原子 rename，避免半写文件被读。
     */
    fun put(key: Key, sampleRate: Int, samples: FloatArray, sentenceFrameOffsets: LongArray) {
        val f = fileFor(key)
        val tmp = File(f.parentFile, f.name + ".tmp")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.writeBytes("RIFF")
            writeLeInt(raf, 36 + samples.size * 4)
            raf.writeBytes("WAVEfmt ")
            writeLeInt(raf, 16)           // fmt chunk size
            writeLeShort(raf, 3)          // IEEE float
            writeLeShort(raf, 1)          // mono
            writeLeInt(raf, sampleRate)
            writeLeInt(raf, sampleRate * 4)
            writeLeShort(raf, 4)
            writeLeShort(raf, 32)         // bits
            raf.writeBytes("data")
            writeLeInt(raf, samples.size * 4)
            val bb = java.nio.ByteBuffer.allocate(samples.size * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(samples)
            raf.write(bb.array())
        }
        if (tmp.renameTo(f)) {
            // sidecar：句偏移（供跳句/进度显示）
            File(f.parentFile, f.nameWithoutExtension + ".json").writeText(
                JSONObject()
                    .put("sampleRate", sampleRate)
                    .put("frames", samples.size.toLong())
                    .put("sentenceOffsets", JSONArray().also { a ->
                        sentenceFrameOffsets.forEach { a.put(it) }
                    })
                    .toString()
            )
            touch(key)
        } else {
            tmp.delete()
        }
    }

    /** 读整章 PCM（命中缓存直接播，零合成 CPU——「缓存=省电」的实现） */
    fun get(key: Key): CachedChapter? {
        val f = fileFor(key)
        if (!f.exists()) return null
        touch(key)
        return RandomAccessFile(f, "r").use { raf ->
            raf.seek(24) // fmt 之后已读，跳 RIFF 头直接解 fmt fields
            raf.seek(22); val bits = readLeShort(raf)
            raf.seek(24); val sampleRate = readLeInt(raf)
            raf.seek(40); val dataLen = readLeInt(raf)
            if (bits != 32) return null // 只支持 float32
            val n = dataLen / 4
            val bb = java.nio.ByteBuffer.allocate(dataLen)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            raf.seek(44)
            raf.readFully(bb.array())
            CachedChapter(
                sampleRate = sampleRate,
                samples = bb.asFloatBuffer().let { fb ->
                    FloatArray(n).also { fb.get(it) }
                },
            )
        }
    }

    data class CachedChapter(val sampleRate: Int, val samples: FloatArray)

    fun remove(key: Key) {
        fileFor(key).delete()
        File(root(), fileFor(key).nameWithoutExtension + ".json").delete()
    }

    fun clearAll() { root().deleteRecursively(); root().mkdirs() }

    fun totalBytes(): Long = root().walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * LRU 收缩到 limitBytes：淘汰「最后访问最早」的非锚定书章节。
     * 返回释放字节数（toast 反馈用——讨论验收标准：变更即时生效+反馈）。
     */
    fun shrinkTo(limitBytes: Long, anchorBookId: String? = null): Long {
        val files = root().listFiles { f -> f.extension == "wav" } ?: return 0
        var total = files.sumOf { it.length() }
        if (total <= limitBytes) return 0
        val sorted = files.sortedBy { it.lastModified() } // 最旧优先
        var freed = 0L
        for (f in sorted) {
            val bookId = f.name.substringBefore("_")
            if (bookId == anchorBookId) continue // 正在听的书锚定
            val size = f.length()
            f.delete()
            File(root(), f.nameWithoutExtension + ".json").delete()
            freed += size
            total -= size
            if (total <= limitBytes) break
        }
        return freed
    }

    private fun touch(key: Key) {
        fileFor(key).setLastModified(System.currentTimeMillis())
    }

    private fun writeLeInt(raf: RandomAccessFile, v: Int) {
        raf.write(byteArrayOf(
            (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte(),
        ))
    }

    private fun writeLeShort(raf: RandomAccessFile, v: Int) {
        raf.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))
    }

    private fun readLeInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4); raf.readFully(b)
        return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or
            ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)
    }

    private fun readLeShort(raf: RandomAccessFile): Int {
        val b = ByteArray(2); raf.readFully(b)
        return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8)
    }
}
