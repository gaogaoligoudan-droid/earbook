package com.earbook.app.tts

/**
 * TTS 引擎抽象：句级合成单元。
 * 双引擎并存：SherpaTtsEngine（bundled，默认）+ 系统 TTS（可选切换）。
 * 两引擎 API 形态不同（同步合成 vs 流式回调），本接口按「句级合成请求」统一。
 */
interface TtsEngine {
    /** 引擎就绪（模型加载完成） */
    val isReady: Boolean

    /** 采样率（合成 PCM 的） */
    fun sampleRate(): Int

    /**
     * 合成一句。callback 每次收到一块 PCM（FloatArray，[-1,1]）——句内流式，首块到达即可起播。
     * 返回总采样数。
     */
    fun synthesize(
        sentence: String,
        speed: Float = 1.0f,
        onChunk: (samples: FloatArray) -> Unit,
    ): Int

    fun release()
}
