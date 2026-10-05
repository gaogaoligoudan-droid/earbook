package com.earbook.app.tts

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型管理：首启下载 + 就绪检查 + 完整性标记。
 * 下载源 GitHub release（tts-models）；tar.bz2 解包走 commons-compress。
 */
object ModelManager {

    private const val MARKER = ".ok"

    fun modelDir(context: Context): File =
        File(context.filesDir, SherpaTtsEngine.MODEL_DIR_NAME)

    fun isReady(context: Context): Boolean =
        File(modelDir(context), MARKER).exists()

    /**
     * 同步下载并解包（调用方须在后台线程；进度回调 0..1）。幂等。
     */
    fun downloadIfNeeded(context: Context, onProgress: (Float) -> Unit = {}): File {
        val dir = modelDir(context)
        if (isReady(context)) return dir
        dir.mkdirs()
        val bz2 = File(dir, "model.tar.bz2")
        download(SherpaTtsEngine.MODEL_URL, bz2, onProgress)
        untarBz2(bz2, dir)
        flatten(dir)
        bz2.delete()
        // 完整性标记：核心文件存在才置
        require(File(dir, "model.int8.onnx").exists() && File(dir, "voices.bin").exists()) {
            "模型解包不完整"
        }
        File(dir, MARKER).createNewFile()
        return dir
    }

    private fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        val total = conn.contentLengthLong
        FileInputStreamSafe(conn.inputStream).use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
    }

    private fun FileInputStreamSafe(s: java.io.InputStream) = s

    private fun untarBz2(bz2: File, dir: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(bz2))).use { tar ->
            var entry = tar.nextTarEntry
            val buf = ByteArray(64 * 1024)
            while (entry != null) {
                if (entry.isDirectory) {
                    File(dir, entry.name).mkdirs()
                } else {
                    val f = File(dir, entry.name)
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { out ->
                        while (true) {
                            val n = tar.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                entry = tar.nextTarEntry
            }
        }
    }

    /** tar 内顶层目录 flatten 到 modelDir 根 */
    private fun flatten(dir: File) {
        val top = dir.listFiles { f -> f.isDirectory }?.firstOrNull { it.name != "dict" } ?: return
        top.listFiles()?.forEach { it.copyRecursively(File(dir, it.name), overwrite = true) }
        val innerDict = File(top, "dict")
        if (innerDict.isDirectory) innerDict.copyRecursively(File(dir, "dict"), overwrite = true)
        top.deleteRecursively()
    }
}
