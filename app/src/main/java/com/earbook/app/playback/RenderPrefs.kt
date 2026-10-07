package com.earbook.app.playback

import android.content.Context

/**
 * R8b 后台缓存授权（用户拍板 2026-10-07）：
 * - battery   = 允许后台跑（耗电）→ RenderService 前台服务随时渲染
 * - charging  = 仅插电时 → WorkManager（charging+idle 约束，M1 既有路径）
 * - ask       = 未问过（首触后台缓存时 MainActivity 弹授权并记住）
 *
 * 边界约定（M2 工程决策）：授权只管「后台推进队列」（听书时渲后续章）；
 * 「当前章立即渲染」（起播门槛 R9 / 追上等待）是用户当下的明确意图，
 * 走 RenderService 前台服务，不受授权限制。
 */
object RenderPrefs {
    private const val PREFS = "earbook"
    private const val KEY = "render_auth"

    const val ASK = "ask"
    const val BATTERY = "battery"
    const val CHARGING = "charging"

    fun auth(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, ASK) ?: ASK

    fun setAuth(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
