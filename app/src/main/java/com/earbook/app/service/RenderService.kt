package com.earbook.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.earbook.app.MainActivity
import com.earbook.app.R
import com.earbook.app.playback.PrerenderManager
import com.earbook.app.tts.VoicePrefs

/**
 * 前台渲染服务（M2，R8a 通知栏进度 + R6「当前章优先→后续章队列」）：
 * - 触发方：ReadAloudService（起播门槛 R9 / 追上等待 / 边听边缓存）
 * - 队列语义：从 fromChapter 起逐章渲染到全书末尾（幂等，已缓存跳过）
 *   当前章优先 = fromChapter 就是当前播放章；后续章自然跟队
 * - readyAfter>0：渲完前 N 章（或全书）广播 STARTUP_READY——起播门槛的唤醒信号
 * - 每章完成广播 CHAPTER_RENDERED：追上等待的自动续播信号 + 回切提示的素材
 * - 通知：进度条 + 「停止缓存」action（用户可随时叫停）
 *
 * 渲染线程单 worker 串行；新 RENDER 指令 → 停旧队列起新队列（换书/换音色场景）
 */
/** 渲染事件（同进程直连，替代已废弃的 LocalBroadcastManager）——顶层对象，跨服务可达 */
object RenderEvents {
    interface Listener {
        fun onRenderEvent(e: Event)
    }
    sealed class Event {
        data class ChapterDone(val bookId: String, val chapter: Int) : Event()
        data class StartupReady(val bookId: String, val chapters: Int) : Event()
        data class Failed(val bookId: String, val chapter: Int, val msg: String) : Event()
        object Cancelled : Event()
    }
    @Volatile var serviceListener: Listener? = null
    @Volatile var uiListener: Listener? = null
    fun emit(e: Event) {
        serviceListener?.onRenderEvent(e)
        uiListener?.onRenderEvent(e)
    }
}

class RenderService : Service() {

    companion object {
        private const val TAG = "RenderService"
        private const val CHANNEL_ID = "render"
        private const val NOTIFICATION_ID = 2

        const val ACTION_RENDER = "com.earbook.app.action.RENDER"
        const val ACTION_CANCEL = "com.earbook.app.action.CANCEL"
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_FROM = "from"
        const val EXTRA_READY_AFTER = "readyAfter" // 渲完前 N 章广播 STARTUP_READY；0=不广播


        /** 当前渲染上下文（Service/UI 查询进度显示用） */
        @Volatile var activeBookId: String? = null
            private set
        @Volatile var activeChapter: Int = -1
            private set

        fun start(context: Context, bookId: String, fromChapter: Int, readyAfter: Int = 0) {
            val intent = Intent(context, RenderService::class.java)
                .setAction(ACTION_RENDER)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_FROM, fromChapter)
                .putExtra(EXTRA_READY_AFTER, readyAfter)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, RenderService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private var worker: Thread? = null
    @Volatile private var cancelled = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Android 9：startForegroundService 后 5s 内必须 startForeground
        startForeground(NOTIFICATION_ID, buildNotification("准备缓存…", indeterminate = true))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled = true
                RenderEvents.emit(RenderEvents.Event.Cancelled)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RENDER -> {
                val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: return START_NOT_STICKY
                val from = intent.getIntExtra(EXTRA_FROM, 0)
                val readyAfter = intent.getIntExtra(EXTRA_READY_AFTER, 0)
                relaunchWorker(bookId, from, readyAfter)
            }
        }
        return START_NOT_STICKY
    }

    /** 新渲染指令：停旧队列（等当前章落盘的原子间隙）→ 起新队列 */
    private fun relaunchWorker(bookId: String, from: Int, readyAfter: Int) {
        cancelled = true
        worker?.let { w ->
            runCatching { w.join(3000) }
            if (w.isAlive) Log.w(TAG, "旧渲染线程未退出（仍在长合成），并行兜底放行")
        }
        cancelled = false
        activeBookId = bookId
        activeChapter = from
        worker = Thread({
            renderQueue(bookId, from, readyAfter)
        }, "render-queue").apply { start() }
    }

    private fun renderQueue(bookId: String, from: Int, readyAfter: Int) {
        val voice = VoicePrefs.cacheKey(this)
        // 全书章数：导入一次拿边界（队列终止条件）。
        // renderChapter 内部仍按 M1 既有行为逐章重导入（幂等闸门已验证，M2 不动它）
        val total = runCatching {
            com.earbook.app.book.BookImporter.import(this, android.net.Uri.parse(bookId)).second.size
        }.getOrElse {
            Log.e(TAG, "书籍读取失败: ${it.message}")
            RenderEvents.emit(RenderEvents.Event.Failed(bookId, from, "书籍读取失败"))
            return
        }
        // 起播门槛（readyAfter>0）：只渲前 N 章即收兵；QUEUE 模式：渲到全书末尾
        val end = if (readyAfter > 0) minOf(from + readyAfter, total) else total
        var done = 0
        var chapter = from
        while (!cancelled && chapter < end) {
            activeChapter = chapter
            updateNotification("缓存中 第${chapter + 1}章（已缓存 $done 章）", true)
            val ok = try {
                PrerenderManager.renderChapter(this, bookId, chapter, voice) { cancelled }
            } catch (e: Exception) {
                Log.e(TAG, "渲染章 $chapter 失败", e)
                false
            }
            if (cancelled) break
            if (ok) {
                done++
                RenderEvents.emit(RenderEvents.Event.ChapterDone(bookId, chapter))
                chapter++
            } else {
                // 渲染失败（章损坏/模型未就绪）：不无限重试，广播失败停队——由触发方决定降级
                RenderEvents.emit(RenderEvents.Event.Failed(bookId, chapter, "渲染失败"))
                break
            }
        }
        if (!cancelled && readyAfter > 0 && done >= readyAfter) {
            RenderEvents.emit(RenderEvents.Event.StartupReady(bookId, done))
        }
    }

    override fun onDestroy() {
        cancelled = true
        activeBookId = null
        activeChapter = -1
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 通知 ─────────────────────────────────────────────────

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "离线缓存", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String, indeterminate: Boolean): Notification {
        val stopPi = PendingIntent.getService(
            this, ACTION_CANCEL.hashCode(),
            Intent(this, RenderService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_add)
            .setContentTitle("EarBook 离线缓存")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止缓存", stopPi)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    private fun updateNotification(text: String, indeterminate: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text, indeterminate))
    }
}
