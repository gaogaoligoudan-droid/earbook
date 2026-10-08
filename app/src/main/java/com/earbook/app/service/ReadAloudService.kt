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
import com.earbook.app.R
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

        // M2 追上缓存二选一（R9）：通知 action / MainActivity dialog 都汇到这里
        const val ACTION_CHOICE_WAIT = "com.earbook.app.action.CHOICE_WAIT"
        const val ACTION_CHOICE_SYSTEM = "com.earbook.app.action.CHOICE_SYSTEM"
        // M3 R11 存储预估确认
        const val ACTION_ESTIMATE_ACCEPT = "com.earbook.app.action.ESTIMATE_ACCEPT"
        const val ACTION_ESTIMATE_DECLINE = "com.earbook.app.action.ESTIMATE_DECLINE"
        const val EXTRA_BOOK_ID = "bookId"

        /** 追上弹窗待处理标记（MainActivity 前台时读走并弹 dialog） */
        @Volatile var pendingCacheChoice = false

        /** R11 存储预估待确认（估算字节 to 可用字节；MainActivity 前台读走弹 dialog） */
        @Volatile var pendingStorageEstimate: Pair<Long, Long>? = null

        /** R9 起播门槛：先缓存前 N 章再起播 */
        private const val STARTUP_GATE_CHAPTERS = 3

        /** 追上弹窗通知 id（与播放通知/渲染通知分离） */
        private const val PROMPT_NOTIFICATION_ID = 2002

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

    // ── M2 调度层状态 ──────────────────────────────────────
    private val cache by lazy { com.earbook.app.playback.ChapterAudioCache(this) }
    private val chapterPlayer by lazy { com.earbook.app.playback.ChapterPlayer(cache) }
    @Volatile private var chapterMode = false      // 当前章走章级单轨（缓存命中路径）
    @Volatile private var fallbackToSystem = false // 神经引擎已临时切系统语音（追上时用户选择）
    @Volatile private var sessionChoice: String? = null // 追上二选一，会话内记住（R9）
    @Volatile private var startupGate = false      // R9 起播门槛等待中（缓存前 N 章）
    private var waitingForChapter = -1             // WAIT 模式等待渲染的章（-1=无）
    private var sherpaEngine: com.earbook.app.engine.SherpaReadAloudEngine? = null

    // 追上二选一选项值（sessionChoice 的取值）
    private val CHOICE_WAIT = "wait"
    private val CHOICE_SYSTEM = "system"

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause(autoResumeAfterFocus = false)
            // 来电等长打断：暂停，焦点归还后自动续播
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedByTransientFocus = isSpeaking
                pause(autoResumeAfterFocus = true)
            }
            // 短提示音等瞬时降焦：压低音量继续播（不中断）
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                engine.setVolume(DUCK_VOLUME)
                chapterPlayer.setVolume(DUCK_VOLUME) // 章级单轨同压（M2）
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                engine.setVolume(1f)
                chapterPlayer.setVolume(1f)
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
            com.earbook.app.engine.SherpaReadAloudEngine(this).also {
                // 真机验证修复：必须触发异步初始化，否则 core 永远为 null，speak 秒败熔断
                it.ensureModelLoadedAsync()
                sherpaEngine = it
            }
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
        // M2：RenderService 渲染事件（同进程直连）→ 服务侧分流（WAIT 续播/回切提示/门槛唤醒）
        RenderEvents.serviceListener = object : RenderEvents.Listener {
            override fun onRenderEvent(e: RenderEvents.Event) {
                scope.launch { onRenderEvent(e) }
            }
        }
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
            // M2 追上二选一（通知 action / MainActivity dialog 汇入口）
            ACTION_CHOICE_WAIT -> {
                sessionChoice = CHOICE_WAIT
                cancelChoicePrompt()
                startWaitRender()
            }
            ACTION_CHOICE_SYSTEM -> {
                sessionChoice = CHOICE_SYSTEM
                cancelChoicePrompt()
                if (!isSpeaking) resumeOrPlay()
            }
            // M3 R11 存储预估确认
            ACTION_ESTIMATE_ACCEPT -> {
                pendingStorageEstimate = null
                val b = book ?: return START_NOT_STICKY
                startGateRender(b)
            }
            ACTION_ESTIMATE_DECLINE -> {
                pendingStorageEstimate = null
                startupGate = false
                Toast.makeText(this, "已取消缓存", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        saveProgress()
        currentBookId = null
        unregisterReceiver(noisyReceiver)
        mediaSession.isActive = false
        mediaSession.release()
        if (chapterMode) { chapterMode = false; chapterPlayer.stop() }
        RenderEvents.serviceListener = null
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
                // M2 会话状态重置（换书=新会话，R9 弹窗选择不跨书）
                sessionChoice = null
                startupGate = false
                waitingForChapter = -1
                fallbackToSystem = false
                // M3 R6 书级模式：切换引擎到该书模式（静默，无 Toast）
                if (target.mode == com.earbook.app.book.Book.MODE_SYSTEM && sherpaEngine != null) {
                    switchToSystem(notify = false)
                } else if (target.mode == com.earbook.app.book.Book.MODE_NEURAL && sherpaEngine == null &&
                    com.earbook.app.tts.ModelManager.isReady(this@ReadAloudService)
                ) {
                    switchToSherpa(notify = false)
                }
                // 磁盘缓存命中路径：注入书上下文（M3 R1 书级音色随动）
                (engine as? com.earbook.app.engine.SherpaReadAloudEngine)?.setBookContext(
                    target.id, com.earbook.app.tts.VoicePrefs.cacheKeyFor(target.voice)
                )
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
                // M3：预估元数据统计写回（旧书首次播放补齐；徽标/预估的分母）
                if (target.totalChapters == 0) {
                    val chars = chapters.sumOf { c -> c.sentences.sumOf { it.length.toLong() } }
                    store.updateBook(
                        target.copy(totalChars = chars, totalChapters = chapters.size)
                    )
                    book = book?.copy(totalChars = chars, totalChapters = chapters.size)
                }
                updateMetadata()
                if (target.mode == com.earbook.app.book.Book.MODE_SYSTEM) {
                    // R6 系统模式：点开即出声，无缓存/门槛/预估
                    playCurrent()
                    return@launch
                }
                // M3 R11 缓存前预估：全书估算 vs 可用空间，超出则请用户决定
                val estimate = com.earbook.app.book.StorageEstimator.estimateBookBytes(target.totalChars)
                val stat = android.os.StatFs(filesDir.absolutePath)
                val available = stat.availableBytes
                if (target.totalChars > 0 && estimate > available) {
                    pendingStorageEstimate = estimate to available
                    Toast.makeText(
                        this@ReadAloudService, "存储空间需要确认，请查看提示", Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                startGateRender(target)
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
        if (startupGate) {
            // R9 起播门槛进行中：等 StartupReady 唤醒，不抢跑
            Toast.makeText(this, "正在缓存，完成后自动开始", Toast.LENGTH_SHORT).show()
            return
        }
        // ── M2 章级路径（R6：缓存命中整章一条轨）与追上检测（R9）──
        if (useNeural()) {
            if (isChapterCached(chapterIndex)) {
                if (fallbackToSystem) switchToSherpa(notify = true) // 缓存跟上，边界自然回切
                if (tryPlayChapterMonolithic()) return
                // 单轨启动失败 → 落逐句兜底
            } else if (handleCacheCaughtUp()) {
                return // 追上流程已处理（暂停等渲 / 切系统语音后由 choice 分流）
            }
            // 未命中且 choice=system：已在 switchToSystem 换引擎，继续逐句
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

    // ── M2/M3 调度层：章级播放 / 追上检测（R9）/ 授权映射（R8b）/ 书级音色模式（R1/R6）──

    private fun bookVoiceKey(): String =
        book?.let { com.earbook.app.tts.VoicePrefs.cacheKeyFor(it.voice) }
            ?: com.earbook.app.tts.VoicePrefs.cacheKey(this)

    /** R6 书级模式：本书是否走神经路径（章级缓存/门槛/追上流程） */
    private fun useNeural(): Boolean =
        sherpaEngine != null && book?.mode != com.earbook.app.book.Book.MODE_SYSTEM

    /** R9 起播门槛：当前章未缓存 → 先渲 min(3, 全书) 章再自动起播（进度见渲染通知） */
    private fun startGateRender(target: com.earbook.app.book.Book) {
        if (isChapterCached(chapterIndex)) {
            startBackgroundQueue(chapterIndex + 1)
            playCurrent()
            return
        }
        startupGate = true
        val n = minOf(STARTUP_GATE_CHAPTERS, chapters.size)
        RenderService.start(this, target.id, chapterIndex, readyAfter = n)
        Toast.makeText(
            this,
            "首次播放：先缓存 $n 章后自动开始（进度见通知栏）",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun isChapterCached(idx: Int): Boolean {
        val bid = book?.id ?: return false
        return cache.has(com.earbook.app.playback.ChapterAudioCache.Key(bid, idx, bookVoiceKey()))
    }

    /** R8b 后台推进队列的授权映射：电池=前台服务随时渲；仅插电/未问=WorkManager（charging） */
    private fun startBackgroundQueue(from: Int) {
        val bid = book?.id ?: return
        when (com.earbook.app.playback.RenderPrefs.auth(this)) {
            com.earbook.app.playback.RenderPrefs.BATTERY ->
                RenderService.start(this, bid, from, readyAfter = 0)
            else ->
                com.earbook.app.playback.PrerenderManager.enqueue(this, bid, from)
        }
    }

    /** 章级单轨播放（R6）：当前章已缓存时整章一条 AudioTrack 播到底。返回 false=启动失败走逐句 */
    private fun tryPlayChapterMonolithic(): Boolean {
        val bid = book?.id ?: return false
        val key = com.earbook.app.playback.ChapterAudioCache.Key(bid, chapterIndex, bookVoiceKey())
        requestFocus() || return false
        chapterPlayer.play(key, sentenceIndex, object : com.earbook.app.playback.ChapterPlayer.Callbacks {
            override fun onSentenceEnter(sentence: Int) {
                scope.launch {
                    if (!chapterMode) return@launch
                    sentenceIndex = sentence
                    saveProgress()
                    updateState()
                }
            }

            override fun onChapterDone() {
                scope.launch {
                    if (!chapterMode) return@launch
                    chapterMode = false
                    nextChapter()
                }
            }

            override fun onError(msg: String) {
                scope.launch {
                    if (!chapterMode) return@launch
                    Log.w(TAG, "章级单轨播放失败: $msg")
                    chapterMode = false
                    runCatching { cache.remove(key) } // 缓存条目损坏：清除，幂等重渲
                    playCurrent() // 重新判定（无缓存 → 追上流程 / 逐句兜底）
                }
            }
        })
        chapterMode = true
        isSpeaking = true
        updateState()
        updateNotification()
        return true
    }

    /**
     * 追上缓存（R9）：当前章未命中时的分流。
     * @return true=已处理（暂停等渲/弹窗），调用方不得再走 engine.speak
     */
    private fun handleCacheCaughtUp(): Boolean {
        when (sessionChoice) {
            CHOICE_SYSTEM -> { switchToSystem(); return false } // 系统语音逐句继续
            CHOICE_WAIT -> { startWaitRender(); return true }
        }
        // 本会话未选过：暂停 + 二选一（前台 dialog / 通知双 action，R9）
        pause(autoResumeAfterFocus = false)
        promptCacheChoice()
        return true
    }

    /** WAIT 路径：暂停起渲当前章（用户明确要等它，不受授权限制），ChapterDone 自动续播 */
    private fun startWaitRender() {
        val bid = book?.id ?: return
        if (waitingForChapter >= 0) return // 已在等待
        waitingForChapter = chapterIndex
        isSpeaking = false
        saveProgress()
        abandonFocus()
        updateState()
        updateNotification()
        RenderService.start(this, bid, chapterIndex, readyAfter = 0)
        Toast.makeText(this, "本章未缓存，渲染完成后自动继续", Toast.LENGTH_SHORT).show()
    }

    /** 追上二选一提示：高优先级通知（灭屏可用）；MainActivity 前台时改为弹 dialog */
    private fun promptCacheChoice() {
        pendingCacheChoice = true
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val waitPi = PendingIntent.getService(
            this, ACTION_CHOICE_WAIT.hashCode(),
            Intent(this, ReadAloudService::class.java).setAction(ACTION_CHOICE_WAIT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sysPi = PendingIntent.getService(
            this, ACTION_CHOICE_SYSTEM.hashCode(),
            Intent(this, ReadAloudService::class.java).setAction(ACTION_CHOICE_SYSTEM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_add)
            .setContentTitle("播放追上缓存")
            .setContentText("本章还没缓存好，怎么继续？")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(android.R.drawable.ic_media_pause, "等缓存好再播", waitPi)
            .addAction(android.R.drawable.ic_media_play, "用系统语音继续", sysPi)
            .build()
        runCatching { nm.notify(PROMPT_NOTIFICATION_ID, n) }
    }

    private fun cancelChoicePrompt() {
        pendingCacheChoice = false
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(PROMPT_NOTIFICATION_ID) }
    }

    /** 切系统语音（追上时「继续听」/ 书级系统模式）：释放神经引擎省内存，回切冷加载（章边界可接受） */
    private fun switchToSystem(notify: Boolean = true) {
        if (fallbackToSystem) return
        fallbackToSystem = true
        runCatching { engine.stop() }
        sherpaEngine?.shutdown()
        sherpaEngine = null
        engine = SystemReadAloudEngine(this).also { e ->
            e.setOnDoneListener { id, ok -> scope.launch { onSentenceDone(id, ok) } }
            e.setOnInitListener { ok ->
                scope.launch {
                    if (!ok) {
                        Toast.makeText(this@ReadAloudService, "系统 TTS 初始化失败", Toast.LENGTH_SHORT).show()
                        pause()
                    }
                }
            }
        }
        if (notify) Toast.makeText(this, "已切系统语音，缓存跟上后自动切回", Toast.LENGTH_LONG).show()
    }

    /** 缓存跟上后回切神经语音（边界自然切换；模型异步加载，章级单轨播放不依赖它就绪） */
    private fun switchToSherpa(notify: Boolean = true) {
        if (!fallbackToSystem && sherpaEngine != null) return
        fallbackToSystem = false
        runCatching { engine.stop() }
        engine = (com.earbook.app.engine.SherpaReadAloudEngine(this).also { se ->
            sherpaEngine = se
            se.ensureModelLoadedAsync()
            se.setOnDoneListener { id, ok -> scope.launch { onSentenceDone(id, ok) } }
            se.setOnInitListener { ok ->
                scope.launch {
                    if (!ok) {
                        Toast.makeText(this@ReadAloudService, "神经引擎初始化失败", Toast.LENGTH_SHORT).show()
                        pause()
                    }
                }
            }
            book?.id?.let { se.setBookContext(it, bookVoiceKey()) }
        })
        if (notify) Toast.makeText(this, "缓存已跟上，切回神经语音", Toast.LENGTH_SHORT).show()
    }

    /** RenderService 事件分流（WAIT 续播 / 门槛唤醒 / 回切提示 / 失败降级） */
    private fun onRenderEvent(e: RenderEvents.Event) {
        val bid = book?.id ?: return
        when (e) {
            is RenderEvents.Event.ChapterDone -> {
                if (e.bookId != bid) return
                when {
                    e.chapter == waitingForChapter -> {
                        waitingForChapter = -1
                        if (!isSpeaking) resumeOrPlay()
                    }
                    fallbackToSystem && e.chapter == chapterIndex ->
                        Toast.makeText(this, "本章神经语音已就绪，播完自动切回", Toast.LENGTH_SHORT).show()
                }
            }
            is RenderEvents.Event.StartupReady -> {
                if (e.bookId == bid && startupGate) {
                    startupGate = false
                    startBackgroundQueue(chapterIndex + e.chapters)
                    resumeOrPlay()
                }
            }
            is RenderEvents.Event.Failed -> {
                if (e.bookId != bid) return
                when {
                    e.chapter == waitingForChapter -> {
                        waitingForChapter = -1
                        Toast.makeText(this, "缓存渲染失败，改用逐句合成播放", Toast.LENGTH_LONG).show()
                        resumeOrPlay()
                    }
                    startupGate -> {
                        startupGate = false
                        Toast.makeText(this, "预渲染失败，直接起播", Toast.LENGTH_SHORT).show()
                        playCurrent()
                    }
                }
            }
            is RenderEvents.Event.Cancelled -> {
                if (waitingForChapter >= 0 || startupGate) {
                    waitingForChapter = -1
                    startupGate = false
                    sessionChoice = null // 用户主动叫停缓存：会话选择一并作废，下次重新问
                    resumeOrPlay()
                }
            }
        }
    }

    private fun pause(autoResumeAfterFocus: Boolean = false) {
        if (!isSpeaking) return
        isSpeaking = false
        if (chapterMode) {
            // 章级单轨：停轨（续播从当前句重放，与逐句路径语义一致）
            chapterMode = false
            chapterPlayer.stop()
        }
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
