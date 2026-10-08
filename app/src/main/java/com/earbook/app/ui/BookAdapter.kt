package com.earbook.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.earbook.app.book.AssetRegistry
import com.earbook.app.book.Book
import com.earbook.app.databinding.ItemBookBinding

/** 书架行数据（文本已预取，bind 零磁盘 I/O） */
data class BookRow(
    val book: Book,
    val progressText: String,
    val badgeText: String?,
)

class BookAdapter(
    private val rows: List<BookRow>,
    private val onClick: (Book) -> Unit,
    private val onLongClick: (Book) -> Unit
) : RecyclerView.Adapter<BookAdapter.Holder>() {

    class Holder(val binding: ItemBookBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val book = rows[position].book
        with(holder.binding) {
            tvTitle.text = book.title
            tvProgress.text = rows[position].progressText
            // R8c 资产徽标（⚡缓存/✨优化/⚠失败）；无登记则不显示，老书架保持干净
            val badge = rows[position].badgeText
            if (badge != null) {
                tvBadges.text = badge
                tvBadges.visibility = View.VISIBLE
            } else {
                tvBadges.visibility = View.GONE
            }
            root.setOnClickListener { onClick(book) }
            root.setOnLongClickListener {
                onLongClick(book)
                true
            }
        }
    }
}
