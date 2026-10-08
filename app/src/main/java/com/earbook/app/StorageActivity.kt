package com.earbook.app

import android.os.Bundle
import android.os.StatFs
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.earbook.app.book.AssetRegistry
import com.earbook.app.databinding.ActivityStorageBinding
import com.earbook.app.databinding.ItemStorageBinding
import com.earbook.app.playback.ChapterAudioCache
import com.earbook.app.service.ReadAloudService
import com.earbook.app.store.PlaybackStore

/**
 * 存储管理页（M3，R11）：每书缓存占用+明细+逐书清理；总占用+设备可用+低空间提醒。
 * R11 拍板：不设上限不自动淘汰，管理交给用户——本页即「管理」本体。
 */
class StorageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageBinding
    private lateinit var store: PlaybackStore
    private lateinit var cache: ChapterAudioCache
    private lateinit var registry: AssetRegistry
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "存储管理"
        store = PlaybackStore(this)
        cache = ChapterAudioCache(this)
        registry = AssetRegistry(this)

        binding.recyclerStorage.layoutManager = LinearLayoutManager(this)
        adapter = Adapter()
        binding.recyclerStorage.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val books = store.listBooks()
        val rows = books.map { b ->
            Row(b, registry.bookSummary(b.id))
        }
        val total = rows.sumOf { it.summary.cacheBytes }
        val available = StatFs(filesDir.absolutePath).availableBytes
        binding.tvTotal.text =
            "离线缓存共 ${fmt(total)} · 设备可用 ${fmt(available)}"
        binding.tvLowSpace.visibility =
            if (available < LOW_SPACE_THRESHOLD) android.view.View.VISIBLE else android.view.View.GONE
        adapter.submit(rows)
    }

    private fun fmt(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private data class Row(val book: com.earbook.app.book.Book, val summary: AssetRegistry.BookSummary)

    private class Holder(val binding: ItemStorageBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class Adapter : RecyclerView.Adapter<StorageActivity.Holder>() {
        private var rows: List<Row> = emptyList()

        fun submit(rows: List<Row>) {
            this.rows = rows
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemStorageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            with(holder.binding) {
                tvBookTitle.text = row.book.title
                tvBookDetail.text = getString(
                    R.string.storage_row_detail,
                    fmt(row.summary.cacheBytes),
                    row.summary.cachedChapters,
                    row.summary.optimizedChapters
                )
                btnCleanup.setOnClickListener {
                    AlertDialog.Builder(this@StorageActivity)
                        .setTitle("清理《${row.book.title}》的离线缓存？")
                        .setMessage(
                            "删除后下次播放会重新渲染（AI 优化保留）。" +
                                if (ReadAloudService.currentBookId == row.book.id)
                                    "\n\n注意：本书正在播放，清理将先停止播放。"
                                else ""
                        )
                        .setPositiveButton("清理") { _, _ ->
                            if (ReadAloudService.currentBookId == row.book.id) {
                                ReadAloudService.send(this@StorageActivity, ReadAloudService.ACTION_STOP)
                            }
                            // 只清缓存文件；登记里的 cachedVoices 随 unmarkVoice 清（优化标记保留）
                            cache.clearBookVoice(row.book.id, com.earbook.app.tts.VoicePrefs.cacheKeyFor(row.book.voice))
                            registry.unmarkVoice(row.book.id, com.earbook.app.tts.VoicePrefs.cacheKeyFor(row.book.voice))
                            Toast.makeText(this@StorageActivity, "已清理", Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                root.setOnClickListener {
                    BookManageActivity.start(this@StorageActivity, row.book.id)
                }
            }
        }
    }

    companion object {
        private const val LOW_SPACE_THRESHOLD = 1024L * 1024 * 1024 // 1GB
    }
}
