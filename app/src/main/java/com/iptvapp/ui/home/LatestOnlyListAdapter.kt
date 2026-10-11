package com.iptvapp.ui.home

import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * A ListAdapter that diffs one list at a time. While a diff is running, a newer list waits, and a
 * list that is replaced before its turn is never diffed at all — only the newest is.
 *
 * Plain ListAdapter queues every submitted list. A movie or series refresh commits its rows in
 * chunks and the catalog re-emits after each one, so a 100k-title grid received a new full list
 * every few hundred milliseconds while one diff of it took far longer. The queued diffs each held
 * two full lists: memory climbed to the heap limit (OutOfMemoryError on a 100k catalog) and the
 * diff threads kept the CPU busy for minutes after the refresh ended.
 *
 * Main thread only, like ListAdapter. Every commit callback still runs, after the list that
 * replaced its own (if any) is committed.
 */
abstract class LatestOnlyListAdapter<T : Any, VH : RecyclerView.ViewHolder>(
    config: AsyncDifferConfig<T>
) : ListAdapter<T, VH>(config) {

    /** The same config ListAdapter(diffCallback) builds. */
    constructor(diffCallback: DiffUtil.ItemCallback<T>) : this(AsyncDifferConfig.Builder(diffCallback).build())

    private var diffing = false
    private var hasWaiting = false
    private var waiting: List<T>? = null
    private val waitingCallbacks = mutableListOf<Runnable>()

    override fun submitList(list: List<T>?) = submitList(list, null)

    override fun submitList(list: List<T>?, commitCallback: Runnable?) {
        if (diffing) {
            waiting = list
            hasWaiting = true
            commitCallback?.let { waitingCallbacks += it }
            return
        }
        diffing = true
        super.submitList(list) {
            diffing = false
            commitCallback?.run()
            if (hasWaiting) {
                val next = waiting
                val callbacks = waitingCallbacks.toList()
                hasWaiting = false
                waiting = null
                waitingCallbacks.clear()
                submitList(next) { callbacks.forEach { it.run() } }
            }
        }
    }
}
