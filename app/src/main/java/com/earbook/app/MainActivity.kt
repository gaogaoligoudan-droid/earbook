package com.earbook.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.earbook.app.book.Book
import com.earbook.app.book.BookImporter
import com.earbook.app.databinding.ActivityMainBinding
import com.earbook.app.service.ReadAloudService
import com.earbook.app.store.PlaybackStore
import com.earbook.app.ui.BookAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: PlaybackStore
    private var adapter: BookAdapter? = null

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importBook(it) }
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果不阻塞使用 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = PlaybackStore(this)

        if (Build.VERSION.SDK_INT >= 33) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.fabImport.setOnClickListener {
            openDocument.launch(arrayOf("text/*", "application/pdf", "application/octet-stream"))
        }

        // M2：离线语音模型入口（就绪后隐藏）+ 设置入口
        binding.btnVoiceModel.setOnClickListener { installVoiceModel() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        refreshList()

        // M3 R6：首次启动单一向导（欢迎/模式/音色/授权+存储）
        if (!getSharedPreferences("earbook", MODE_PRIVATE)
                .getBoolean("wizard_done", false)
        ) {
            WizardActivity.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        // 模型就绪即隐藏入口（下载完成/已就绪两种情况）
        binding.btnVoiceModel.visibility =
            if (com.earbook.app.tts.ModelManager.isReady(this)) View.GONE else View.VISIBLE
        // M2：追上二选一——服务发了提示而用户此刻回到前台，改用 dialog 承接
        maybeShowCacheChoiceDialog()
        // M3 R11：存储预估确认（Service 在起播门槛前挂起等待）
        maybeShowStorageEstimateDialog()
    }

    private fun maybeShowStorageEstimateDialog() {
        val est = ReadAloudService.pendingStorageEstimate ?: return
        ReadAloudService.pendingStorageEstimate = null
        val mb = est.first / (1024.0 * 1024)
        val availMb = est.second / (1024.0 * 1024)
        AlertDialog.Builder(this)
            .setTitle("存储空间确认")
            .setMessage(
                String.format(
                    "离线缓存预计需要约 %.0f MB，设备当前可用 %.0f MB。\n\n" +
                        "继续将开始缓存音频（可随时在存储管理页清理）。",
                    mb, availMb
                )
            )
            .setPositiveButton("继续缓存") { _, _ ->
                ReadAloudService.send(this, ReadAloudService.ACTION_ESTIMATE_ACCEPT)
            }
            .setNegativeButton("先不了") { _, _ ->
                ReadAloudService.send(this, ReadAloudService.ACTION_ESTIMATE_DECLINE)
            }
            .setCancelable(false)
            .show()
    }

    private var voiceModelDialog: AlertDialog? = null

    private fun installVoiceModel() {
        if (voiceModelDialog != null) return // 防重复点击
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.voice_model_download)
            .setMessage(getString(R.string.voice_model_downloading, 0))
            .setCancelable(false)
            .show()
        voiceModelDialog = dialog
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    com.earbook.app.tts.ModelManager.installIfNeeded(this@MainActivity) { p ->
                        runOnUiThread {
                            dialog.setMessage(
                                getString(R.string.voice_model_downloading, (p * 100).toInt())
                            )
                        }
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            voiceModelDialog = null
            dialog.dismiss()
            Toast.makeText(
                this@MainActivity,
                if (ok) R.string.voice_model_ready else R.string.voice_model_failed,
                Toast.LENGTH_LONG
            ).show()
            if (ok) binding.btnVoiceModel.visibility = View.GONE
        }
    }

    private fun importBook(uri: Uri) {
        // 持久化 URI 权限，重启后仍可读取
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // 部分文件管理器不给持久化权限，播放当次仍可用
        }

        lifecycleScope.launch {
            try {
                // 书架导入只取元数据；全书解析推迟到播放时（含错误 Toast 反馈）
                val meta = withContext(Dispatchers.IO) {
                    BookImporter.buildBookMeta(this@MainActivity, uri)
                }
                // M3 R1/R6：新书落向导默认值（书级音色/模式）
                val prefs = getSharedPreferences("earbook", MODE_PRIVATE)
                val book = meta.copy(
                    voice = prefs.getString("default_voice", Book.VOICE_FEMALE) ?: Book.VOICE_FEMALE,
                    mode = prefs.getString("default_mode", Book.MODE_NEURAL) ?: Book.MODE_NEURAL
                )
                if (store.addBook(book)) {
                    Toast.makeText(this@MainActivity, "已导入：${book.title}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "书已在书架中", Toast.LENGTH_SHORT).show()
                }
                refreshList()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshList() {
        val books = store.listBooks()
        val registry = com.earbook.app.book.AssetRegistry(this)
        adapter = BookAdapter(
            books = books,
            progressText = { book ->
                val p = store.getProgress(book.id)
                "进度：第 ${p.chapterIndex + 1} 章 · 第 ${p.sentenceIndex + 1} 句"
            },
            badgeText = { book ->
                // R8c：⚡已缓存 N/M · ✨优化 N（M=章数未知时只显示 N）；⚠优化失败 K
                val s = registry.bookSummary(book.id)
                if (s.cachedChapters == 0 && s.optimizedChapters == 0 && s.failedChapters == 0) null
                else buildString {
                    append("⚡ 已缓存 ${s.cachedChapters}")
                    if (book.totalChapters > 0) append("/${book.totalChapters}")
                    if (s.optimizedChapters > 0) append(" · ✨ AI 优化 ${s.optimizedChapters}")
                    if (s.failedChapters > 0) append(" · ⚠ ${s.failedChapters} 章优化失败")
                }
            },
            onClick = { book -> startPlayback(book.id) },
            onLongClick = { book -> BookManageActivity.start(this@MainActivity, book.id) }
        )
        binding.recyclerBooks.layoutManager = LinearLayoutManager(this)
        binding.recyclerBooks.adapter = adapter
        binding.emptyHint.visibility = if (books.isEmpty()) android.view.View.VISIBLE
        else android.view.View.GONE
    }

    /** R8b：首触后台缓存先问授权（记住选择），随后交给服务；R11 预估前置检查 */
    private fun startPlayback(bookId: String) {
        val rp = com.earbook.app.playback.RenderPrefs
        // M3 R11：缓存前预估（点书时书元数据在手，直接问，避免 Service 侧挂起等待）
        val b = store.listBooks().firstOrNull { it.id == bookId }
        if (b != null && b.mode == Book.MODE_NEURAL && b.totalChars > 0) {
            val estimate = com.earbook.app.book.StorageEstimator.estimateBookBytes(b.totalChars)
            val available = android.os.StatFs(filesDir.absolutePath).availableBytes
            if (estimate > available) {
                AlertDialog.Builder(this)
                    .setTitle("存储空间确认")
                    .setMessage(
                        String.format(
                            "《%s》离线缓存预计约 %.0f MB，设备可用 %.0f MB。\n\n继续将开始缓存（可随时在存储管理页清理）。",
                            b.title, estimate / 1024.0 / 1024, available / 1024.0 / 1024
                        )
                    )
                    .setPositiveButton("继续缓存") { _, _ ->
                        launchAfterChecks(bookId)
                    }
                    .setNegativeButton("先不了", null)
                    .setCancelable(false)
                    .show()
                return
            }
        }
        launchAfterChecks(bookId)
    }

    private fun launchAfterChecks(bookId: String) {
        val rp = com.earbook.app.playback.RenderPrefs
        if (rp.auth(this) == rp.ASK) {
            AlertDialog.Builder(this)
                .setTitle("后台离线缓存")
                .setMessage(
                    "边听边缓存需要后台渲染音频：\n\n" +
                        "· 允许后台跑——随时缓存，稍耗电\n" +
                        "· 仅充电时——更省电，插上电源才缓存"
                )
                .setPositiveButton("允许后台跑") { _, _ ->
                    rp.setAuth(this, rp.BATTERY)
                    ReadAloudService.start(this, bookId)
                }
                .setNegativeButton("仅充电时") { _, _ ->
                    rp.setAuth(this, rp.CHARGING)
                    ReadAloudService.start(this, bookId)
                }
                .setCancelable(false)
                .show()
            return
        }
        ReadAloudService.start(this, bookId)
    }

    /** R9 追上二选一（服务把 pendingCacheChoice 置位时，前台 dialog 承接） */
    private fun maybeShowCacheChoiceDialog() {
        if (!ReadAloudService.pendingCacheChoice) return
        ReadAloudService.pendingCacheChoice = false // 读走；通知由服务在选择后撤销
        AlertDialog.Builder(this)
            .setTitle("播放追上缓存")
            .setMessage(
                "本章还没缓存好：\n\n" +
                    "· 等缓存好再播——保持离线神经语音\n" +
                    "· 用系统语音继续——立即可听，缓存跟上后自动切回"
            )
            .setPositiveButton("等缓存好再播") { _, _ ->
                ReadAloudService.send(this, ReadAloudService.ACTION_CHOICE_WAIT)
            }
            .setNegativeButton("用系统语音继续") { _, _ ->
                ReadAloudService.send(this, ReadAloudService.ACTION_CHOICE_SYSTEM)
            }
            .setCancelable(false)
            .show()
    }

    @Suppress("unused")
    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return null
    }
}
