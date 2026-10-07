package com.earbook.app.tts

import android.content.Context

/**
 * 音色偏好（2026-10-07 真机试听拍板：7 号女声 zf sid=30 / 10 号男声 zm sid=70）。
 * 存 SharedPreferences("earbook")，键 "voice"：female（默认）/ male。
 * 引擎侧 speak 时即时读取（切音色下一句生效）；缓存键随动（换音色=换缓存命名空间）。
 */
object VoicePrefs {
    private const val PREFS = "earbook"
    private const val KEY = "voice"

    fun isMale(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "female") == "male"

    /** male=70（zm_男B）/ female=30（zf_女G） */
    fun sid(context: Context): Int =
        if (isMale(context)) SherpaTtsEngine.SPEAKER_MALE else SherpaTtsEngine.SPEAKER_FEMALE

    /** 磁盘缓存键：与 sid 一一对应（kokoro30/kokoro70） */
    fun cacheKey(context: Context): String = "kokoro${sid(context)}"
}
