package com.earbook.app.engine

/**
 * 朗读引擎抽象——M2 的微软引擎、M4 的 sherpa-onnx 离线引擎都实现此接口。
 *
 * M1 阶段使用系统 TTS 引擎（SystemReadAloudEngine）。
 * 设计约束：speak() 是一次性播放（非队列），完成/失败通过回调通知，
 * 由 ReadAloudService 负责推进句子队列。
 */
interface ReadAloudEngine {

    /** 引擎就绪状态（系统 TTS 初始化是异步的） */
    val isReady: Boolean

    /** 朗读一段文本。utteranceId 用于回调时定位句子索引 */
    fun speak(text: String, utteranceId: String)

    /**
     * 预取提示（M2 句级流水）：Service 播当前句时把下一句喂给引擎，
     * 支持预合成的引擎（Sherpa）提前生成——speak 到来时命中即零等待。
     * 默认空实现（系统 TTS 自带队列，无需预取）。
     */
    fun prefetch(text: String, utteranceId: String) {}

    /** 停止当前朗读（可被新的 speak() 打断） */
    fun stop()

    /** 释放资源 */
    fun shutdown()

    /** 一段朗读结束（正常完成或被打断后的收尾） */
    fun setOnDoneListener(listener: ((utteranceId: String, success: Boolean) -> Unit)?)

    /** 引擎初始化结果：true=就绪；false=重试耗尽后仍失败（用户需感知） */
    fun setOnInitListener(listener: ((success: Boolean) -> Unit)?)

    /** 设置朗读音量（0f~1f，句子粒度生效）。用于音频焦点 Duck */
    fun setVolume(volume: Float)
}
