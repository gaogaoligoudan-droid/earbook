package com.earbook.app.tts

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 模型管理：内置 assets 解包 + 就绪检查 + 完整性标记。
 *
 * 模型包随 APK 内置（assets/model.tar.bz2，git-lfs 存储），首启解包到 filesDir，
 * 之后引擎从 filesDir 加载——全程零网络（替代旧版 GitHub release 下载，国内必败）。
 * 解包/flatten/完整性校验/标记链路与旧版完全一致（真机已验证）。
 */
object ModelManager {

    private const val MARKER = ".ok"
    private const val ASSET_BUNDLE = "model.tar.bz2"

    /** 进度分两段：assets 拷贝 0..0.4，解包 0.4..1.0（按字节数线性映射） */
    private const val COPY_WEIGHT = 0.4f
    private const val ASSET_BYTES = 147_031_220L
    private const val UNPACKED_BYTES = 207_000_000L

    fun modelDir(context: Context): File =
        File(context.filesDir, SherpaTtsEngine.MODEL_DIR_NAME)

    fun isReady(context: Context): Boolean =
        File(modelDir(context), MARKER).exists()

    /**
     * 从内置 assets 解包模型（调用方须在后台线程；进度回调 0..1）。幂等。
     */
    fun installIfNeeded(context: Context, onProgress: (Float) -> Unit = {}): File {
        val dir = modelDir(context)
        if (isReady(context)) return dir
        dir.mkdirs()
        val bz2 = File(context.cacheDir, ASSET_BUNDLE)
        copyAsset(context, ASSET_BUNDLE, bz2, onProgress)
        untarBz2(bz2, dir) { done -> onProgress(COPY_WEIGHT + (1 - COPY_WEIGHT) * done) }
        flatten(dir)
        bz2.delete()
        // 完整性标记：核心文件存在才置
        require(File(dir, "model.int8.onnx").exists() && File(dir, "voices.bin").exists()) {
            "模型解包不完整"
        }
        File(dir, MARKER).createNewFile()
        return dir
    }

    private fun copyAsset(context: Context, name: String, dest: File, onProgress: (Float) -> Unit) {
        context.assets.open(name).use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(COPY_WEIGHT * (done.toFloat() / ASSET_BYTES))
                }
            }
        }
    }

    private fun untarBz2(
        bz2: File,
        dir: File,
        onProgress: (Float) -> Unit = {},
    ) {
        TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(bz2))).use { tar ->
            var extracted = 0L
            val buf = ByteArray(64 * 1024)
            while (true) {
                val entry = tar.nextTarEntry ?: break
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
                    extracted += entry.size
                    onProgress((extracted.toFloat() / UNPACKED_BYTES).coerceAtMost(1f))
                }
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
