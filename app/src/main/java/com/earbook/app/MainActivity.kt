package com.earbook.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
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

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
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
            onClick = { book -> ReadAloudService.start(this, book.id) },
            onLongClick = { book -> confirmRemove(book) }
        )
        binding.recyclerBooks.layoutManager = LinearLayoutManager(this)
        binding.recyclerBooks.adapter = adapter
        binding.emptyHint.visibility = if (books.isEmpty()) android.view.View.VISIBLE
        else android.view.View.GONE
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
