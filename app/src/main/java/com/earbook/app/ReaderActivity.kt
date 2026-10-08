package com.earbook.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.earbook.app.book.BookImporter
import com.earbook.app.databinding.ActivityReaderBinding
import com.earbook.app.playback.PlaybackEvents
import com.earbook.app.service.ReadAloudService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 原文阅读面板（M4/R12，用户 2026-10-08 需求）：
 * - 按句展示当前章原文；当前朗读句高亮 + 自动跟随滚动
 * - 点任意句子跳读（未播放时点句 = 从该句起播）
 * - 上一章/下一章、播放/暂停
 * 数据：进页导入全书（IO），服务句级事件直连驱动 UI。
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var store: com.earbook.app.store.PlaybackStore

    private var bookId: String? = null
    private var chapters: List<com.earbook.app.book.Chapter> = emptyList()
    private var chapterIndex = 0
    private var currentSentence = -1
    private var playing = false
    private var sentences = ArrayList<String>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private lateinit var adapter: SentenceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "阅读"
        store = com.earbook.app.store.PlaybackStore(this)
        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: ReadAloudService.currentBookId

        adapter = SentenceAdapter { pos -> jumpTo(pos) }
        binding.recyclerSentences.layoutManager = LinearLayoutManager(this)
        binding.recyclerSentences.adapter = adapter

        binding.btnPrevChapter.setOnClickListener {
            ReadAloudService.send(this, ReadAloudService.ACTION_PREVIOUS)
        }
        binding.btnNextChapter.setOnClickListener {
            ReadAloudService.send(this, ReadAloudService.ACTION_NEXT)
        }
        binding.btnPlayPause.setOnClickListener {
            ReadAloudService.send(
                this,
                if (playing) ReadAloudService.ACTION_PAUSE else ReadAloudService.ACTION_RESUME
            )
        }

        loadText()
    }

    private fun loadText() {
        val bid = bookId
        if (bid == null) {
            binding.tvChapterTitle.text = "当前没有正在播放的书"
            return
        }
        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    BookImporter.import(this@ReaderActivity, android.net.Uri.parse(bid))
                }.getOrNull()
            }
            if (imported == null) {
                binding.tvChapterTitle.text = "书籍读取失败"
                return@launch
            }
            bookId = imported.first.id
            val saved = store.getProgress(imported.first.id)
            chapterIndex = saved.chapterIndex
            chapters = imported.second
            showChapter()
        }
    }

    private fun showChapter() {
        val ch = chapters.getOrNull(chapterIndex)
        if (ch == null) return
        sentences = ArrayList(ch.sentences)
        binding.tvChapterTitle.text = ch.title.ifBlank { "第 ${chapterIndex + 1} 章" }
        adapter.submit(sentences)
        syncHighlight()
    }

    private fun jumpTo(sentence: Int) {
        val bid = bookId ?: return
        currentSentence = sentence
        syncHighlight()
        // 未播放 → 以该句为起点起播；播放中 → 直接跳
        ReadAloudService.send(this, ReadAloudService.ACTION_SEEK, bid, chapterIndex, sentence)
    }

    private fun syncHighlight() {
        adapter.highlight(currentSentence)
        if (currentSentence >= 0) {
            val lm = binding.recyclerSentences.layoutManager as? LinearLayoutManager ?: return
            val target = (currentSentence - 3).coerceAtLeast(0)
            lm.scrollToPositionWithOffset(target, 0)
        }
        binding.btnPlayPause.text = if (playing) "暂停" else "播放"
    }

    override fun onResume() {
        super.onResume()
        PlaybackEvents.listener = object : PlaybackEvents.Listener {
            override fun onPlaybackEvent(e: PlaybackEvents.Event) {
                if (e is PlaybackEvents.Event.Position && e.bookId == bookId) {
                    mainHandler.post {
                        val chapterChanged = e.chapter != chapterIndex
                        currentSentence = e.sentence
                        playing = e.playing
                        if (chapterChanged && e.chapter in chapters.indices) {
                            chapterIndex = e.chapter
                            showChapter()
                        } else {
                            syncHighlight()
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        PlaybackEvents.listener = null
        super.onPause()
    }

    private class SentenceAdapter(
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<SentenceAdapter.Holder>() {

        private var items: List<String> = emptyList()
        private var highlighted = -1

        class Holder(val tv: TextView) : RecyclerView.ViewHolder(tv)

        fun submit(list: List<String>) {
            items = list
            highlighted = -1
            notifyDataSetChanged()
        }

        fun highlight(pos: Int) {
            val old = highlighted
            highlighted = pos
            if (old != pos) {
                if (old in items.indices) notifyItemChanged(old)
                if (pos in items.indices) notifyItemChanged(pos)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_sentence, parent, false) as TextView)

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.tv.text = items[position]
            holder.tv.setBackgroundColor(
                if (position == highlighted) 0x3334C759.toInt() else 0x00000000
            )
            holder.tv.setTextColor(
                if (position == highlighted) 0xFF1D6F42.toInt() else 0xFF1C1C1E.toInt()
            )
            holder.tv.setOnClickListener { onClick(position) }
        }
    }

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"

        fun start(context: Context, bookId: String) {
            context.startActivity(
                Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
            )
        }
    }
}
