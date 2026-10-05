package com.earbook.app.playback

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.earbook.app.R

/**
 * 预渲染 Worker（M3-2）：charging+idle 约束下逐章渲染（PrerenderManager 渲染逻辑）。
 * 完成通知：验收标准「《X》已缓存 N 章」——下载管理器式的确定性反馈。
 */
class PrerenderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_BOOK = "book"
        const val KEY_FROM = "from"
        const val KEY_COUNT = "count"
        private const val TAG = "PrerenderWorker"
        private const val CHANNEL = "prerender"
    }

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK) ?: return Result.failure()
        val from = inputData.getInt(KEY_FROM, 0)
        val count = inputData.getInt(KEY_COUNT, 3)

        var done = 0
        for (i in from until from + count) {
            if (isStopped) break
            val ok = runCatching {
                PrerenderManager.renderChapter(applicationContext, bookId, i)
            }.getOrDefault(false)
            if (ok) done++
            Log.i(TAG, "预渲染 章$i → $ok")
        }

        if (done > 0) notifyDone(done)
        return Result.success()
    }

    private fun notifyDone(chapters: Int) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL, "预渲染完成", android.app.NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_add)
            .setContentTitle("EarBook")
            .setContentText("已离线缓存 $chapters 章（充电时自动完成）")
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(2001, n) }
    }
}
