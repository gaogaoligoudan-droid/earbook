package com.earbook.app.playback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * 章块音频缓存（磁盘层，M1 重构）：
 * - 存储：每章一个 **AAC(ADTS, 32kbps mono)** 文件 + JSON sidecar（帧数/句偏移）；
 *   MediaCodec 不可用时降级 WAV float32（体积大但可用）
 * - **无上限、无 LRU**（R11 拍板）：只增不自动删，管理交给用户（R11 管理页）
 * - 缓存 key 四维：bookId / chapterIndex / 音色 / 优化版本（语速除外——播放端变速）
 */
class ChapterAudioCache(private val context: Context) {

    private fun root(): File = File(context.filesDir, "tts-cache").apply { mkdirs() }

    /** 缓存 key——四维（语速不进 key：播放端变速复用） */
    data class Key(
        val bookId: String,
        val chapterIndex: Int,
        val voice: String,        // 音色标识（kokoro30/kokoro70/系统引擎名）
        val textVersion: Int = 0, // 优化版本（原文=0，AI 优化后递增）
    )

    private fun aacFile(key: Key) = File(root(), "${key.bookId}_${key.chapterIndex}_${key.voice}_${key.textVersion}.aac")
    private fun wavFile(key: Key) = File(root(), "${key.bookId}_${key.chapterIndex}_${key.voice}_${key.textVersion}.wav")
    private fun sidecar(f: File) = File(f.parentFile, f.nameWithoutExtension + ".json")

    fun fileFor(key: Key): File = aacFile(key)
    fun has(key: Key): Boolean = aacFile(key).exists() || wavFile(key).exists()

    /**
     * 写入一章：samples 全量 + 每句帧偏移表。临时文件原子 rename。
     * AAC 优先，MediaCodec 失败降级 WAV（风险表既定策略）。
     */
    fun put(key: Key, sampleRate: Int, samples: FloatArray, sentenceFrameOffsets: LongArray) {
        val aac = try {
            AacCodec.encodePcmToAacAdts(samples, sampleRate)
        } catch (e: Exception) {
            android.util.Log.w("ChapterAudioCache", "AAC 编码失败降级 WAV: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
        val f = if (aac != null) aacFile(key) else wavFile(key)
        val tmp = File(f.parentFile, f.name + ".tmp")
        if (aac != null) {
            tmp.writeBytes(aac)
        } else {
            writeWav(tmp, sampleRate, samples)
        }
        if (tmp.renameTo(f)) {
            sidecar(f).writeText(
                JSONObject()
                    .put("format", if (aac != null) "aac" else "wav")
                    .put("sampleRate", sampleRate)
                    .put("frames", samples.size.toLong())
                    .put("bytes", f.length())
                    .put("sentenceOffsets", JSONArray().also { a ->
                        sentenceFrameOffsets.forEach { a.put(it) }
                    })
                    .toString()
            )
        } else {
            tmp.delete()
        }
    }

    /** 读整章 PCM（AAC 解码；WAV 直读）。null=不可用（视缓存无效）。 */
    fun get(key: Key): CachedChapter? {
        val af = aacFile(key)
        val wf = wavFile(key)
        return when {
            af.exists() -> {
                val meta = readMeta(af) ?: return null
                val pcm = AacCodec.decodeAacAdtsToPcm(af.readBytes(), meta.optInt("sampleRate", 24000))
                    ?: return null
                CachedChapter(meta.optInt("sampleRate", 24000), pcm, readOffsets(af))
            }
            wf.exists() -> readWav(wf, readOffsets(wf))
            else -> null
        }
    }

    /** 本缓存中该章实际文件（管理页显示/删除用） */
    fun existingFile(key: Key): File? = aacFile(key).takeIf { it.exists() } ?: wavFile(key).takeIf { it.exists() }

    /** 章级条目（M2-b 章级单轨播放器用）：只取文件与元数据，不解码——全章 PCM 不进内存 */
    data class Entry(
        val file: File,
        val format: String,      // "aac" | "wav"
        val sampleRate: Int,
        val frames: Long,        // 总帧数（float 样本口径）
        val sentenceOffsets: LongArray,
    )

    fun getEntry(key: Key): Entry? {
        val f = existingFile(key) ?: return null
        val meta = readMeta(f) ?: return null
        return Entry(
            file = f,
            format = meta.optString("format", if (f.extension == "aac") "aac" else "wav"),
            sampleRate = meta.optInt("sampleRate", 24000),
            frames = meta.optLong("frames", 0),
            sentenceOffsets = readOffsets(f),
        )
    }

    fun remove(key: Key) {
        existingFile(key)?.let { f ->
            f.delete()
            sidecar(f).delete()
        }
    }

    /** 清一本书全部缓存（跨音色/版本）——删书（R11「尺寸归零」）与「删除 AI 优化」（R7 缓存全作废）用 */
    fun clearBook(bookId: String) {
        root().listFiles()?.forEach { f ->
            if (f.name.startsWith("$bookId")) f.delete()
        }
    }

    /** 清一本书指定音色的缓存——R1 换音色=旧缓存重渲（AI 优化与音色无关，不动 assets-index） */
    fun clearBookVoice(bookId: String, voice: String) {
        root().listFiles()?.forEach { f ->
            // 文件名：bookId_chapter_voice_textVersion.aac|wav
            if (f.name.startsWith("$bookId") && f.name.contains("_${voice}_")) f.delete()
        }
    }

    /** 某书全部缓存章节文件（R11 管理页/R8d 单书删除） */
    fun filesForBook(bookId: String): List<File> =
        root().listFiles { f -> f.isFile && f.name.startsWith("$bookId") }?.toList() ?: emptyList()

    fun removeBook(bookId: String) {
        filesForBook(bookId).forEach { f ->
            f.delete()
            sidecar(f).delete()
        }
    }

    fun clearAll() { root().deleteRecursively(); root().mkdirs() }

    fun totalBytes(): Long = root().walkTopDown().filter { it.isFile && it.extension != "json" }.sumOf { it.length() }

    fun perBookBytes(): Map<String, Long> =
        root().listFiles { f -> f.isFile && f.extension != "json" }
            ?.groupBy({ it.name.substringBefore("_") }, { it.length() })
            ?.mapValues { (_, sizes) -> sizes.sum() } ?: emptyMap()

    // ── 内部 ─────────────────────────────────────────────

    private fun readMeta(f: File): JSONObject? = try {
        JSONObject(sidecar(f).readText())
    } catch (_: Exception) { null }

    private fun readOffsets(f: File): LongArray {
        val meta = readMeta(f) ?: return LongArray(0)
        val arr = meta.optJSONArray("sentenceOffsets") ?: return LongArray(0)
        return LongArray(arr.length()) { arr.getLong(it) }
    }

    private fun writeWav(f: File, sampleRate: Int, samples: FloatArray) {
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0)
            raf.writeBytes("RIFF")
            writeLeInt(raf, 36 + samples.size * 4)
            raf.writeBytes("WAVEfmt ")
            writeLeInt(raf, 16)
            writeLeShort(raf, 3)          // IEEE float
            writeLeShort(raf, 1)          // mono
            writeLeInt(raf, sampleRate)
            writeLeInt(raf, sampleRate * 4)
            writeLeShort(raf, 4)
            writeLeShort(raf, 32)
            raf.writeBytes("data")
            writeLeInt(raf, samples.size * 4)
            val bb = java.nio.ByteBuffer.allocate(samples.size * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(samples)
            raf.write(bb.array())
        }
    }

    private fun readWav(f: File, offsets: LongArray): CachedChapter? {
        return RandomAccessFile(f, "r").use { raf ->
            raf.seek(22); val bits = readLeShort(raf)
            raf.seek(24); val sampleRate = readLeInt(raf)
            raf.seek(40); val dataLen = readLeInt(raf)
            if (bits != 32) return null
            val n = dataLen / 4
            val bb = java.nio.ByteBuffer.allocate(dataLen)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            raf.seek(44)
            raf.readFully(bb.array())
            CachedChapter(sampleRate, FloatArray(n).also { bb.asFloatBuffer().get(it) }, offsets)
        }
    }

    data class CachedChapter(
        val sampleRate: Int,
        val samples: FloatArray,
        /** 每句的起始帧偏移（末尾哨兵=总帧数）；空=未知切分（按整章播） */
        val sentenceOffsets: LongArray = LongArray(0),
    ) {
        /** 取第 idx 句的 PCM（offsets 缺失时返回 null） */
        fun sentenceAt(idx: Int): FloatArray? {
            if (sentenceOffsets.size < 2) return null
            if (idx < 0 || idx >= sentenceOffsets.size - 1) return null
            val from = sentenceOffsets[idx].toInt()
            val to = sentenceOffsets[idx + 1].toInt()
            return samples.copyOfRange(from, minOf(to, samples.size))
        }
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
