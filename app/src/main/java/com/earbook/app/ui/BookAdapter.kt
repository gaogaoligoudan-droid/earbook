package com.earbook.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.earbook.app.book.Book
import com.earbook.app.databinding.ItemBookBinding

class BookAdapter(
    private val books: List<Book>,
    private val progressText: (Book) -> String,
    private val onClick: (Book) -> Unit,
    private val onLongClick: (Book) -> Unit
) : RecyclerView.Adapter<BookAdapter.Holder>() {

    class Holder(val binding: ItemBookBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = books.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val book = books[position]
        with(holder.binding) {
            tvTitle.text = book.title
            tvProgress.text = progressText(book)
            root.setOnClickListener { onClick(book) }
            root.setOnLongClickListener {
                onLongClick(book)
                true
            }
        }
    }
}
