package com.earbook.app.engine

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * M1 临时引擎：包装系统 TTS（TextToSpeech）。
 *
 * 说明：系统 TTS 没有真正的 pause——「暂停」实现为 stop()，
 * 恢复时从当前句子重新朗读（句子级断点，粒度足够听书使用）。
 *
 * 韧性：初始化失败自动重试（最多 MAX_INIT_RETRY 次），仍失败则回调通知用户。
 */
class SystemReadAloudEngine(context: Context) : ReadAloudEngine {

    @Volatile
    override var isReady: Boolean = false
        private set

    private var onDoneListener: ((String, Boolean) -> Unit)? = null
    private var onInitListener: ((Boolean) -> Unit)? = null
    private var pendingText: String? = null
    private var pendingId: String? = null
    private var initRetryLeft = MAX_INIT_RETRY
    private var volume = 1.0f

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null

    init {
        createTts()
    }

    private fun createTts() {
        tts = TextToSpeech(appContext, ::onTtsInit).also {
            it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { /* noop */ }

                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { id -> onDoneListener?.invoke(id, true) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { id -> onDoneListener?.invoke(id, false) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    utteranceId?.let { id -> onDoneListener?.invoke(id, false) }
                }
            })
        }
    }

    /** TTS 引擎异步初始化回调（构造之后才会触发） */
    private fun onTtsInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // 中文环境优先，缺失时退回默认语言
            val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
            Log.i(TAG, "setLanguage(zh-CN) result=$result")
            synchronized(this) {
                isReady = true
                val text = pendingText
                val id = pendingId
                pendingText = null
                pendingId = null
                if (text != null && id != null) speak(text, id)
            }
            onInitListener?.invoke(true)
        } else {
            Log.e(TAG, "TextToSpeech init failed: $status (retryLeft=$initRetryLeft)")
            if (initRetryLeft > 0) {
                initRetryLeft--
                tts?.shutdown()
                tts = null
                // 弱设备上引擎冷启动可能超时，延迟后重试
                mainHandler.postDelayed({ createTts() }, RETRY_DELAY_MS)
            } else {
                onInitListener?.invoke(false)
            }
        }
    }

    override fun setOnDoneListener(listener: ((String, Boolean) -> Unit)?) {
        onDoneListener = listener
    }

    override fun setOnInitListener(listener: ((Boolean) -> Unit)?) {
        onInitListener = listener
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    override fun speak(text: String, utteranceId: String) {
        if (!isReady) {
            // 引擎尚未初始化完成：暂存，就绪后自动播放
            synchronized(this) {
                if (!isReady) {
                    pendingText = text
                    pendingId = utteranceId
                    return
                }
            }
        }
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            ?: TextToSpeech.ERROR
        if (result != TextToSpeech.SUCCESS) {
            // 官方规范：speak() 返回 ERROR 时不会有任何 Utterance 回调（含 onError），
            // 必须主动上报失败，否则上层会永久假死（熔断机制也收不到回调）
            mainHandler.post {
                onDoneListener?.invoke(utteranceId, false)
            }
        }
    }

    override fun stop() {
        // 清空暂存：初始化完成前的暂停不能在引擎就绪后「幽灵起播」
        synchronized(this) {
            pendingText = null
            pendingId = null
        }
        tts?.stop()
    }

    override fun shutdown() {
        isReady = false
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(this) {
            pendingText = null
            pendingId = null
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "SystemTtsEngine"
        private const val MAX_INIT_RETRY = 2
        private const val RETRY_DELAY_MS = 3000L
    }
}
