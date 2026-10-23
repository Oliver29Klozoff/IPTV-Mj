package com.iptvapp.ui.player

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Pure policy: Automatic never opens a speculative provider connection. */
object TurboTune {
    /** A resolver may swallow cancellation; never commit its late result to the player. */
    suspend fun <T> resolveLatest(resolve: suspend () -> T): T {
        val result = resolve()
        currentCoroutineContext().ensureActive()
        return result
    }
    const val OFF = "off"
    const val AUTOMATIC = "automatic"
    const val PERFORMANCE = "performance"

    fun debounceMs(mode: String, configured: Int): Long =
        if (mode == OFF) configured.coerceAtLeast(0).toLong()
        else maxOf(configured, 100).toLong()

    fun allowMediaPreload(mode: String, lowMemory: Boolean, pressure: Boolean): Boolean =
        mode == PERFORMANCE && !lowMemory && !pressure

    fun startupBufferMs(mode: String, original: Int, protectedProfile: Boolean): Int =
        if (mode == OFF || protectedProfile) original else minOf(original, 500)
}
