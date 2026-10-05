package com.earbook.app.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * sherpa-onnx Kokoro（int8 多语）引擎实现。
 * 模型从 filesDir 加载（首启下载，见 ModelManager），不走 assets。
 *
 * TN 说明：数字/日期/电话的中文转读由模型自带 ruleFsts（number-zh.fst 等）在引擎内完成，
 * App 侧 TextNormalizer 只做文本清洗（水印/URL/页眉），见 text/TextNormalizer.kt。
 */
class SherpaTtsEngine(
    @Suppress("unused") context: Context,
    private val modelDir: File,
    private val speakerId: Int = KOKORO_DEFAULT_SPEAKER,
) : TtsEngine {

    companion object {
        // kokoro multi-lang v1.1：中文女声在 0-10 区（zh 声区），默认 0 号（zf_xiaobei 系）
        const val KOKORO_DEFAULT_SPEAKER = 0
        const val MODEL_DIR_NAME = "kokoro-int8-multi-lang-v1_1"
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2"
    }

    private var tts: OfflineTts? = null
    private val readyLock = Any()

    override val isReady: Boolean
        get() = synchronized(readyLock) { tts != null }

    init {
        require(modelDir.isDirectory) { "model dir missing: $modelDir" }
    }

    private fun ensureLoaded(): OfflineTts = synchronized(readyLock) {
        tts ?: OfflineTts(assetManager = null, config = buildConfig(modelDir.absolutePath))
            .also { tts = it }
    }

    /** data class 直构（官方大工厂 getOfflineTtsConfig 走相对路径拼装，这里统一绝对路径更稳） */
    private fun buildConfig(dir: String): OfflineTtsConfig {
        val kokoro = OfflineTtsKokoroModelConfig(
            model = "$dir/model.int8.onnx",
            voices = "$dir/voices.bin",
            tokens = "$dir/tokens.txt",
            dataDir = "$dir/espeak-ng-data",
            // 多 lexicon 含逗号时官方工厂原样透传 → 统一绝对路径逗号拼接
            lexicon = "$dir/lexicon-zh.txt,$dir/lexicon-us-en.txt,$dir/lexicon-gb-en.txt",
            dictDir = "$dir/dict",
        )
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(kokoro = kokoro, numThreads = 2, provider = "cpu"),
            // 引擎内置中文 TN（数字/日期/电话）
            ruleFsts = "$dir/number-zh.fst,$dir/date-zh.fst,$dir/phone-zh.fst",
        )
    }

    override fun sampleRate(): Int = ensureLoaded().sampleRate()

    override fun synthesize(
        sentence: String,
        speed: Float,
        onChunk: (FloatArray) -> Unit,
    ): Int {
        val engine = ensureLoaded()
        val audio = engine.generateWithCallback(
            text = sentence,
            sid = speakerId,
            speed = speed,
            callback = { chunk ->
                if (chunk.isNotEmpty()) onChunk(chunk)
                1 // 1=继续合成
            },
        )
        return audio.samples.size
    }

    override fun synthesizeFull(sentence: String, speed: Float): FloatArray =
        ensureLoaded().generate(text = sentence, sid = speakerId, speed = speed).samples

    override fun release() = synchronized(readyLock) {
        tts?.free()
        tts = null
    }
}
