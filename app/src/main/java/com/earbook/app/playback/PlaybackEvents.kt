package com.earbook.app.playback

/**
 * 播放状态事件（M4/R12）：ReadAloudService → ReaderActivity 的同进程直连通道。
 * 句级位置 + 播放态，供原文面板高亮当前句/跟随滚动/点选跳句。
 */
object PlaybackEvents {
    interface Listener {
        fun onPlaybackEvent(e: Event)
    }

    sealed class Event {
        data class Position(
            val bookId: String,
            val chapter: Int,
            val sentence: Int,
            val playing: Boolean,
        ) : Event()
    }

    @Volatile var listener: Listener? = null

    fun emit(e: Event) {
        listener?.onPlaybackEvent(e)
    }
}
