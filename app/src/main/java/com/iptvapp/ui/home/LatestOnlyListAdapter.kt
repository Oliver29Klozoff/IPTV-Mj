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
 * A list whose size differs from the one on screen (or on its way) by more than [REPLACE_OVER]
 * is shown at once without a diff: a diff costs about the list size times the number of changes,
 * so going from a few search results to a 176k catalog took minutes, and every list after it
 * waited that long. Scroll position and animations aren't kept for such a change anyway.
 *
 * Main thread only, like ListAdapter. Every commit callback still runs, after the list that
 * replaced its own (if any) is committed.
 */
abstract class LatestOnlyListAdapter<T : Any, VH : RecyclerView.ViewHolder>(
    config: AsyncDifferConfig<T>
) : ListAdapter<T, VH>(config) {

    /** The same config ListAdapter(diffCallback) builds. */
    constructor(diffCallback: DiffUtil.ItemCallback<T>) : this(AsyncDifferConfig.Builder(diffCallback).build())

    companion object {
        const val REPLACE_OVER = 1_000
    }

    // A diff is running (its list and the callback its commit owes are below).
    private var diffRunning = false
    private var inFlight: List<T>? = null
    private var inFlightCallback: Runnable? = null
    // A commit callback is running: a list it submits waits, rather than starting now and being
    // overtaken by an older waiting one.
    private var committing = false
    private var hasWaiting = false
    private var waiting: List<T>? = null
    private val waitingCallbacks = mutableListOf<Runnable>()

    override fun submitList(list: List<T>?) = submitList(list, null)

    override fun submitList(list: List<T>?, commitCallback: Runnable?) {
        val shownNext = if (diffRunning) inFlight else currentList
        if (kotlin.math.abs((list?.size ?: 0) - (shownNext?.size ?: 0)) > REPLACE_OVER) {
            replace(list, commitCallback)
            return
        }
        if (diffRunning || committing) {
            waiting = list
            hasWaiting = true
            commitCallback?.let { waitingCallbacks += it }
            return
        }
        diffRunning = true
        inFlight = list
        inFlightCallback = commitCallback
        super.submitList(list) {
            diffRunning = false
            inFlight = null
            inFlightCallback = null
            committed(listOfNotNull(commitCallback))
        }
    }

    /** After a commit: its callbacks, then the newest list that waited for it. */
    private fun committed(callbacks: List<Runnable>) {
        val outer = committing
        committing = true
        callbacks.forEach { it.run() }
        committing = outer
        if (!committing && !diffRunning && hasWaiting) {
            val next = waiting
            val owed = waitingCallbacks.toList()
            hasWaiting = false
            waiting = null
            waitingCallbacks.clear()
            submitList(next) { owed.forEach { it.run() } }
        }
    }

    /**
     * [list] on screen now, no diff. A diff still running is dropped (AsyncListDiffer discards a
     * result overtaken by a newer list and never calls it back), so the callbacks it and the
     * waiting list owed run here instead, after [list] is committed.
     */
    private fun replace(list: List<T>?, commitCallback: Runnable?) {
        val owed = buildList {
            inFlightCallback?.let { add(it) }
            addAll(waitingCallbacks)
            commitCallback?.let { add(it) }
        }
        diffRunning = false
        inFlight = null
        inFlightCallback = null
        waiting = null
        hasWaiting = false
        waitingCallbacks.clear()
        // From an empty list both commits are immediate, on this thread: nothing is diffed.
        super.submitList(null)
        super.submitList(list) { committed(owed) }
    }
}
