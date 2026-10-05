package com.earbook.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.earbook.app.MainActivity
import com.earbook.app.book.Book
import com.earbook.app.book.Chapter
import com.earbook.app.book.Progress
import com.earbook.app.book.BookImporter
import com.earbook.app.engine.ReadAloudEngine
import com.earbook.app.engine.SystemReadAloudEngine
import com.earbook.app.store.PlaybackStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 朗读前台服务——M1 核心。
 *
 * 耳机线控链路：MediaSession（激活 + 音频焦点）→ 系统把媒体按键路由到
 * sessionCallback → onPlay / onPause / onSkipToNext / onSkipToPrevious。
 * 拔出耳机：ACTION_AUDIO_BECOMING_NOISY 广播 → 自动暂停。
 */
class ReadAloudService : Service() {

    companion object {
        private const val TAG = "ReadAloudService"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1

        /** 当前正在播放/已加载的书（供书架删除时判断是否误杀） */
        @Volatile
        var currentBookId: String? = null
            private set

        const val ACTION_START = "com.earbook.app.action.START"
        const val ACTION_PAUSE = "com.earbook.app.action.PAUSE"
        const val ACTION_RESUME = "com.earbook.app.action.RESUME"
        const val ACTION_STOP = "com.earbook.app.action.STOP"
        const val ACTION_NEXT = "com.earbook.app.action.NEXT"
        const val ACTION_PREVIOUS = "com.earbook.app.action.PREVIOUS"
        const val EXTRA_BOOK_ID = "bookId"

        /** 瞬时降焦（提示音等）时压低的朗读音量 */
        private const val DUCK_VOLUME = 0.2f

        /** 连续合成失败熔断阈值 */
        private const val MAX_CONSECUTIVE_FAILURES = 3

        fun start(context: Context, bookId: String) {
            val intent = Intent(context, ReadAloudService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BOOK_ID, bookId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun send(context: Context, action: String) {
            context.startService(
                Intent(context, ReadAloudService::class.java).setAction(action)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: PlaybackStore
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var engine: ReadAloudEngine

    private var book: Book? = null
    private var chapters: List<Chapter> = emptyList()
    private var chapterIndex = 0
    private var sentenceIndex = 0
    private var isSpeaking = false

    private var focusRequest: AudioFocusRequest? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private var pausedByTransientFocus = false

    /** 连续合成失败计数（熔断用） */
    private var consecutiveFailures = 0

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause(autoResumeAfterFocus = false)
            // 来电等长打断：暂停，焦点归还后自动续播
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedByTransientFocus = isSpeaking
                pause(autoResumeAfterFocus = true)
            }
            // 短提示音等瞬时降焦：压低音量继续播（不中断）
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                engine.setVolume(DUCK_VOLUME)
            AudioManager.AUDIOFOCUS_GAIN -> {
                engine.setVolume(1f)
                if (pausedByTransientFocus) {
                    pausedByTransientFocus = false
                    playCurrent()
                }
            }
        }
    }

    // ── 生命周期 ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        store = PlaybackStore(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 引擎选择（M2）：本地 sherpa 模型就绪 → 离线引擎；否则回落系统 TTS
        engine = if (com.earbook.app.tts.ModelManager.isReady(this)) {
            com.earbook.app.engine.SherpaReadAloudEngine(this)
        } else {
            SystemReadAloudEngine(this)
        }
        engine.setOnDoneListener { utteranceId, success ->
            scope.launch { onSentenceDone(utteranceId, success) }
        }
        engine.setOnInitListener { success ->
            scope.launch {
                if (!success) {
                    Toast.makeText(
                        this@ReadAloudService,
                        "TTS 引擎初始化失败，请检查系统「文字转语音」设置",
                        Toast.LENGTH_LONG
                    ).show()
                    stopSelf()
                }
            }
        }

        mediaSession = MediaSessionCompat(this, "EarBook").apply {
            setCallback(sessionCallback)
            setSessionActivity(
                PendingIntent.getActivity(
                    this@ReadAloudService, 0,
                    Intent(this@ReadAloudService, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            isActive = true
        }

        // Android 14（targetSdk 34）要求运行时注册 receiver 必须声明导出标志；
        // ACTION_AUDIO_BECOMING_NOISY 是受保护系统广播，NOT_EXPORTED 下仍可收到
        ContextCompat.registerReceiver(
            this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: return START_NOT_STICKY
                loadAndPlay(bookId)
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resumeOrPlay()
            ACTION_NEXT -> nextChapter()
            ACTION_PREVIOUS -> previousChapter()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        saveProgress()
        currentBookId = null
        unregisterReceiver(noisyReceiver)
        mediaSession.isActive = false
        mediaSession.release()
        engine.shutdown()
        abandonFocus()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 播放控制 ──────────────────────────────────────────────

    private var loadJob: kotlinx.coroutines.Job? = null

    private fun loadAndPlay(bookId: String) {
        // 同书重开防抖：正在播/暂停的书再次点击 → 直接续播，不重新 I/O 全书
        if (this.book?.id == bookId && chapters.isNotEmpty()) {
            resumeOrPlay()
            return
        }
        // 立即停掉当前播放并保存旧书进度，防止切书期间旧句回调污染新书状态
        pause(autoResumeAfterFocus = false)
        abandonFocus()
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val target = store.listBooks().firstOrNull { it.id == bookId } ?: return@launch
                currentBookId = target.id
                val imported = withContext(Dispatchers.IO) {
                    BookImporter.import(
                        this@ReadAloudService,
                        android.net.Uri.parse(target.uriString)
                    )
                }
                book = imported.first
                chapters = imported.second
                val saved = store.getProgress(bookId)
                chapterIndex = saved.chapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
                sentenceIndex = saved.sentenceIndex.coerceIn(0, (chapters[chapterIndex].sentenceCount - 1).coerceAtLeast(0))
                consecutiveFailures = 0
                updateMetadata()
                playCurrent()
            } catch (e: Exception) {
                Log.e(TAG, "导入失败: ${e.message}", e)
                // 用户必须感知失败原因（扫描版 PDF、文件损坏等），不能静默
                Toast.makeText(this@ReadAloudService, "无法朗读：${e.message}", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }
    }

    private fun playCurrent() {
        if (book == null || chapters.isEmpty()) return
        val chapter = chapters.getOrNull(chapterIndex) ?: run { stopSelf(); return }
        val sentence = chapter.sentences.getOrNull(sentenceIndex)
        if (sentence == null) {
            // 本章播完 → 下一章
            nextChapter()
            return
        }
        requestFocus() || return
        isSpeaking = true
        engine.speak(sentence, utteranceId(chapterIndex, sentenceIndex))
        // M2 句级流水：预取下一句（含跨章首句），引擎支持时句间零等待
        nextSentenceAfter(chapterIndex, sentenceIndex)?.let { (nc, ns, text) ->
            engine.prefetch(text, utteranceId(nc, ns))
        }
        updateState()
        updateNotification()
    }

    /** 当前句之后的一句（跨章边界）：返回 (章idx, 句idx, 文本) 或 null */
    private fun nextSentenceAfter(cIdx: Int, sIdx: Int): Triple<Int, Int, String>? {
        val cur = chapters.getOrNull(cIdx) ?: return null
        cur.sentences.getOrNull(sIdx + 1)?.let { return Triple(cIdx, sIdx + 1, it) }
        val next = chapters.getOrNull(cIdx + 1) ?: return null
        return next.sentences.firstOrNull()?.let { Triple(cIdx + 1, 0, it) }
    }

    private fun pause(autoResumeAfterFocus: Boolean = false) {
        if (!isSpeaking) return
        isSpeaking = false
        engine.stop()
        saveProgress()
        // 暂停即释放焦点（来电等需自动续播的场景除外——放弃焦点将收不到 GAIN 回调）
        if (!autoResumeAfterFocus) abandonFocus()
        updateState()
        updateNotification()
        if (!autoResumeAfterFocus) pausedByTransientFocus = false
    }

    /** 外部播放请求（耳机键/通知/焦点恢复）：正在播则忽略，避免当前句被重读 */
    private fun resumeOrPlay() {
        if (isSpeaking) return
        playCurrent()
    }

    private fun onSentenceDone(utteranceId: String, success: Boolean) {
        if (!isSpeaking) return // 被暂停打断的回调，忽略
        val (chapter, sentence) = parseUtteranceId(utteranceId)
        if (chapter != chapterIndex || sentence != sentenceIndex) return // 过期回调

        if (!success) {
            consecutiveFailures++
            Log.w(TAG, "句子合成失败($consecutiveFailures)：$utteranceId")
            // 熔断：连续失败说明引擎/语音包异常，继续推进只会瞬间跳完整本书
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                consecutiveFailures = 0
                Toast.makeText(
                    this,
                    "语音引擎连续合成失败，已暂停。请检查系统 TTS 的中文语音包",
                    Toast.LENGTH_LONG
                ).show()
                pause()
                return
            }
        } else {
            consecutiveFailures = 0
        }
        advance()
    }

    private fun advance() {
        val chapter = chapters.getOrNull(chapterIndex) ?: return
        sentenceIndex++
        // 逐句落盘：进程被杀最多丢一句，不会回退整章
        saveProgress()
        if (sentenceIndex >= chapter.sentenceCount) {
            nextChapter()
        } else {
            playCurrent()
        }
    }

    private fun nextChapter() {
        if (chapterIndex + 1 >= chapters.size) {
            // 全书读完：进度归零（否则下次打开直接落在结尾，永远无法重播）+ 告知用户
            chapterIndex = 0
            sentenceIndex = 0
            saveProgress()
            Toast.makeText(this, "《${book?.title ?: "本书"}》已读完，下次播放将从头开始", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        chapterIndex++
        sentenceIndex = 0
        saveProgress()
        updateMetadata()
        playCurrent()
    }

    private fun previousChapter() {
        if (chapterIndex > 0) chapterIndex--
        sentenceIndex = 0
        saveProgress()
        updateMetadata()
        playCurrent()
    }

    // ── MediaSession：耳机线控入口 ────────────────────────────

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = resumeOrPlay()
        override fun onPause() = pause()
        override fun onSkipToNext() = nextChapter()
        override fun onSkipToPrevious() = previousChapter()
        override fun onStop() = stopSelf()
    }

    /** 焦点只在「非播放 → 播放」转变时申请一次；申请被拒（通话中等）时不出声 */
    private fun requestFocus(): Boolean {
        if (focusRequest != null) return true
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        val result = audioManager.requestAudioFocus(req)
        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = req
            true
        } else {
            Log.w(TAG, "音频焦点申请被拒（result=$result），本次不出声")
            false
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // ── 状态同步（Session / 通知） ───────────────────────────

    private fun updateMetadata() {
        val b = book ?: return
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, b.title)
                .putString(
                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                    chapters.getOrNull(chapterIndex)?.title ?: ""
                )
                .build()
        )
    }

    private fun updateState() {
        val state = if (isSpeaking) PlaybackStateCompat.STATE_PLAYING
        else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )
    }

    // ── 通知 ─────────────────────────────────────────────────

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "朗读", NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val b = book
        val chapterTitle = chapters.getOrNull(chapterIndex)?.title ?: ""
        // 通知栏切章：PendingIntent 指向服务自身的 action
        fun serviceAction(action: String): PendingIntent =
            PendingIntent.getService(
                this, action.hashCode(),
                Intent(this, ReadAloudService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(b?.title ?: "EarBook")
            .setContentText(chapterTitle)
            .setOngoing(isSpeaking)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "上一章", serviceAction(ACTION_PREVIOUS))
            .addAction(
                if (isSpeaking) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isSpeaking) "暂停" else "播放",
                serviceAction(if (isSpeaking) ACTION_PAUSE else ACTION_RESUME)
            )
            .addAction(android.R.drawable.ic_media_next, "下一章", serviceAction(ACTION_NEXT))
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    // ── 工具 ─────────────────────────────────────────────────

    private fun saveProgress() {
        val id = book?.id ?: return
        if (chapters.isEmpty()) return
        store.saveProgress(id, Progress(chapterIndex, sentenceIndex))
    }

    private fun utteranceId(chapter: Int, sentence: Int) = "c$chapter:s$sentence"

    private fun parseUtteranceId(id: String): Pair<Int, Int> {
        val m = Regex("""c(\d+):s(\d+)""").find(id) ?: return -1 to -1
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }
}
