package com.earbook.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.earbook.app.book.AssetRegistry
import com.earbook.app.book.Book
import com.earbook.app.databinding.ActivityBookManageBinding
import com.earbook.app.playback.ChapterAudioCache
import com.earbook.app.service.RenderService
import com.earbook.app.service.ReadAloudService
import com.earbook.app.store.PlaybackStore
import com.earbook.app.tts.VoicePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书籍管理页（M3，R8c/R8d/R1/R6）：长按书卡进入。
 * 资产明细 + 音色/模式切换 + 缓存/优化删除 + 重新生成 + 移除。
 * 设计原则（R8d 拍板）：无「重新优化」——想重做必须先删除。
 */
class BookManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookManageBinding
    private lateinit var store: PlaybackStore
    private lateinit var cache: ChapterAudioCache
    private lateinit var registry: AssetRegistry
    private lateinit var book: Book

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookManageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = PlaybackStore(this)
        cache = ChapterAudioCache(this)
        registry = AssetRegistry(this)

        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }
        book = store.listBooks().firstOrNull { it.id == bookId } ?: run { finish(); return }

        render()
        bindActions()
    }

    private fun render() {
        val s = registry.bookSummary(book.id)
        binding.tvTitle.text = book.title
        binding.tvMeta.text = buildString {
            append(if (book.format == Book.FORMAT_PDF) "PDF" else "TXT")
            append(" · ")
            if (book.totalChapters > 0) append("${book.totalChapters} 章") else append("章数待首次播放统计")
            if (book.totalChars > 0) append(" · ${book.totalChars} 字")
        }
        binding.tvAssets.text = buildString {
            appendLine("神经缓存：${s.cachedChapters}${if (book.totalChapters > 0) "/${book.totalChapters}" else ""} 章 · ${fmtBytes(s.cacheBytes)}")
            appendLine("AI 优化：${s.optimizedChapters} 章完成" + if (s.failedChapters > 0) " · ${s.failedChapters} 章失败（按原文播）" else "")
            appendLine("音色：${if (book.voice == Book.VOICE_MALE) "男声" else "女声"} · 模式：${if (book.mode == Book.MODE_SYSTEM) "系统语音" else "神经语音"}")
            if (s.cachedVoices.isNotEmpty()) {
                append("缓存音色：${s.cachedVoices.joinToString("、")}")
            }
        }
        binding.btnVoice.text = "切换音色（当前：${if (book.voice == Book.VOICE_MALE) "男声" else "女声"}）"
        binding.btnMode.text = "切换播放模式（当前：${if (book.mode == Book.MODE_SYSTEM) "系统语音" else "神经语音"}）"
    }

    private fun bindActions() {
        // R1 书级音色：换音色 = 清旧音色缓存（重渲），AI 优化保留（与音色无关）
        binding.btnVoice.setOnClickListener {
            val options = arrayOf("女声", "男声")
            val current = if (book.voice == Book.VOICE_MALE) 1 else 0
            AlertDialog.Builder(this)
                .setTitle("选择这本书的音色")
                .setSingleChoiceItems(options, current) { d, which ->
                    val newVoice = if (which == 1) Book.VOICE_MALE else Book.VOICE_FEMALE
                    d.dismiss()
                    if (newVoice != book.voice) {
                        val oldKey = VoicePrefs.cacheKeyFor(book.voice)
                        cache.clearBookVoice(book.id, oldKey)
                        registry.unmarkVoice(book.id, oldKey)
                        book = book.copy(voice = newVoice)
                        store.updateBook(book)
                        render()
                        Toast.makeText(this, "已切音色，下次播放将按新音色重新缓存", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
        }

        // R6 书级模式：系统=即时播放（无缓存等待）；神经=缓存后连续
        binding.btnMode.setOnClickListener {
            val options = arrayOf("神经语音（缓存后连续播放）", "系统语音（点开即听）")
            val current = if (book.mode == Book.MODE_SYSTEM) 1 else 0
            AlertDialog.Builder(this)
                .setTitle("选择这本书的播放模式")
                .setSingleChoiceItems(options, current) { d, which ->
                    d.dismiss()
                    val newMode = if (which == 1) Book.MODE_SYSTEM else Book.MODE_NEURAL
                    if (newMode != book.mode) {
                        book = book.copy(mode = newMode)
                        store.updateBook(book)
                        render()
                        val playing = ReadAloudService.currentBookId == book.id
                        Toast.makeText(
                            this,
                            "已切模式" + if (playing) "，对本书的下次播放生效" else "",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .show()
        }

        // R10：导出音频（M4A 零转码单文件 + 系统分享）——拍板方案见 docs/R10导出方案调研.md
        binding.btnExport.setOnClickListener { startExportFlow() }

        // R8d：重新生成 = 从当前进度起排全书渲染队列（幂等，已缓存秒过）
        binding.btnRegenerate.setOnClickListener {
            val p = store.getProgress(book.id)
            RenderService.start(this, book.id, p.chapterIndex, readyAfter = 0)
            Toast.makeText(this, "已开始后台缓存（见通知栏进度）", Toast.LENGTH_SHORT).show()
        }

        // R8d：删当前音色的神经缓存（登记同步清）
        binding.btnClearCache.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("删除神经缓存？")
                .setMessage("将删除本书当前音色的已缓存音频（${fmtBytes(registry.bookSummary(book.id).cacheBytes)}），释放空间；下次播放需重新缓存。")
                .setPositiveButton("删除") { _, _ ->
                    val key = VoicePrefs.cacheKeyFor(book.voice)
                    cache.clearBookVoice(book.id, key)
                    registry.unmarkVoice(book.id, key)
                    render()
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // R7 语义：删 AI 优化 = 优化标记清零 + 全部缓存作废（优化版本与缓存绑定）
        binding.btnClearOptimize.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("删除 AI 优化？")
                .setMessage("将删除本书全部 AI 优化标记，且已缓存音频随之作废删除；下次播放按原文重新缓存与优化。")
                .setPositiveButton("删除") { _, _ ->
                    cache.clearBook(book.id)
                    registry.clearBook(book.id)
                    render()
                    Toast.makeText(this, "已删除优化与缓存", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 删书（原书架长按删除移入管理页；R11 资产随书清）
        binding.btnRemoveBook.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("移除《${book.title}》？")
                .setMessage("将从书架移除，神经缓存与优化资产一并删除（源文件不受影响）。")
                .setPositiveButton("移除") { _, _ ->
                    if (ReadAloudService.currentBookId == book.id) {
                        ReadAloudService.send(this, ReadAloudService.ACTION_STOP)
                    }
                    store.removeBook(book.id)
                    cache.clearBook(book.id)
                    registry.clearBook(book.id)
                    Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ── R10 导出（M4A 零转码单文件 + 系统分享）─────────────────

    private var exporting = false // 防重复点击

    private fun startExportFlow() {
        if (exporting) return
        lifecycleScope.launch {
            // 1) 导入拿章序（管理页无内存态；流式导入秒级）
            val chapters = withContext(Dispatchers.IO) {
                runCatching {
                    com.earbook.app.book.BookImporter.import(
                        this@BookManageActivity, android.net.Uri.parse(book.uriString)
                    ).second
                }.getOrNull()
            }
            if (chapters == null) {
                Toast.makeText(this@BookManageActivity, "书籍读取失败，无法导出", Toast.LENGTH_LONG).show()
                return@launch
            }
            // 2) 前置检查：逐章缓存状态（R10：未缓存=明确提示先缓存）
            val voiceKey = VoicePrefs.cacheKeyFor(book.voice)
            val cachedFiles = ArrayList<java.io.File>(chapters.size)
            var uncached = 0
            var wavCount = 0
            for (i in chapters.indices) {
                val key = ChapterAudioCache.Key(book.id, i, voiceKey)
                val entry = cache.getEntry(key)
                when {
                    entry == null -> uncached++
                    entry.format != "aac" -> wavCount++
                    else -> cachedFiles.add(entry.file)
                }
            }
            if (uncached > 0) {
                AlertDialog.Builder(this@BookManageActivity)
                    .setTitle("还有 $uncached 章未缓存")
                    .setMessage("导出需要全书缓存完成。可以先在后台缓存剩余章节，完成后再来导出。")
                    .setPositiveButton("先去缓存") { _, _ ->
                        RenderService.start(
                            this@BookManageActivity, book.id, store.getProgress(book.id).chapterIndex, readyAfter = 0
                        )
                        Toast.makeText(this@BookManageActivity, "已开始后台缓存（见通知栏进度）", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }
            if (wavCount > 0) {
                Toast.makeText(
                    this@BookManageActivity,
                    "有 $wavCount 章是降级 WAV 缓存（编码器曾失败），暂不支持导出；可删除缓存后重新生成再试",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            // 3) 体积预估确认
            val totalBytes = cachedFiles.sumOf { it.length() }
            AlertDialog.Builder(this@BookManageActivity)
                .setTitle("导出《${book.title}》")
                .setMessage(
                    String.format(
                        "将封装 %d 章为单个 M4A 文件（约 %.0f MB，零转码音质无损），随后拉起分享面板。",
                        cachedFiles.size, totalBytes / 1024.0 / 1024
                    )
                )
                .setPositiveButton("导出并分享") { _, _ -> doExport(cachedFiles) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun doExport(cachedFiles: List<java.io.File>) {
        exporting = true
        val safeName = book.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
        val outFile = java.io.File(java.io.File(filesDir, "exports"), "$safeName.m4a")
        val dialog = AlertDialog.Builder(this)
            .setTitle("正在导出")
            .setMessage("0 / ${cachedFiles.size} 章")
            .setCancelable(false)
            .create()
        dialog.show()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                com.earbook.app.playback.AacCodec.exportToM4a(
                    cachedFiles, outFile,
                    onProgress = { done, total ->
                        runOnUiThread { dialog.setMessage("$done / $total 章") }
                    },
                    isCancelled = { !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
                )
            }
            exporting = false
            runCatching { dialog.dismiss() }
            if (ok) {
                shareFile(outFile)
            } else {
                Toast.makeText(this@BookManageActivity, "导出失败（缓存可能损坏），请重试或重新生成缓存", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shareFile(file: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, book.title)
            putExtra(Intent.EXTRA_TEXT, "《${book.title}》有声书（EarBook 离线生成）")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "分享《${book.title}》"))
    }

    private fun fmtBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "${bytes}B"
    }

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"

        fun start(context: Context, bookId: String) {
            context.startActivity(
                Intent(context, BookManageActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
            )
        }
    }
}
