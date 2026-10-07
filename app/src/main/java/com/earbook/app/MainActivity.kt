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
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        // 模型就绪即隐藏入口（下载完成/已就绪两种情况）
        binding.btnVoiceModel.visibility =
            if (com.earbook.app.tts.ModelManager.isReady(this)) View.GONE else View.VISIBLE
        // M2：追上二选一——服务发了提示而用户此刻回到前台，改用 dialog 承接
        maybeShowCacheChoiceDialog()
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
                val book = withContext(Dispatchers.IO) {
                    BookImporter.buildBookMeta(this@MainActivity, uri)
                }
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
        adapter = BookAdapter(
            books = books,
            progressText = { book ->
                val p = store.getProgress(book.id)
                "进度：第 ${p.chapterIndex + 1} 章 · 第 ${p.sentenceIndex + 1} 句"
            },
            onClick = { book -> startPlayback(book.id) },
            onLongClick = { book -> confirmRemove(book) }
        )
        binding.recyclerBooks.layoutManager = LinearLayoutManager(this)
        binding.recyclerBooks.adapter = adapter
        binding.emptyHint.visibility = if (books.isEmpty()) android.view.View.VISIBLE
        else android.view.View.GONE
    }

    /** R8b：首触后台缓存先问授权（记住选择），随后交给服务 */
    private fun startPlayback(bookId: String) {
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

    private fun confirmRemove(book: com.earbook.app.book.Book) {
        AlertDialog.Builder(this)
            .setTitle("移除《${book.title}》？")
            .setMessage("将从书架移除并删除进度记录（不会删除源文件）")
            .setPositiveButton("移除") { _, _ ->
                // 只有删的书正在播时才停服务，删除其他书不误杀当前播放
                if (ReadAloudService.currentBookId == book.id) {
                    ReadAloudService.send(this, ReadAloudService.ACTION_STOP)
                }
                store.removeBook(book.id)
                refreshList()
            }
            .setNegativeButton("取消", null)
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
