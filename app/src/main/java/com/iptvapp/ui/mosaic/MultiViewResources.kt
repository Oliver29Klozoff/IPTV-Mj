package com.iptvapp.ui.mosaic

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import com.iptvapp.data.repository.XtreamRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Conservative ceilings, not a promise that every codec/resolution combination can run. */
object MultiViewPolicy {
    fun tileLimit(lowRam: Boolean, memoryClassMb: Int, decoderInstances: Int): Int = when {
        lowRam || memoryClassMb < 128 || decoderInstances < 2 -> 1
        memoryClassMb < 256 || decoderInstances < 4 -> 2
        else -> 4
    }

    fun providerSlots(max: Int?, active: Int?, local: Int): Int {
        // Unknown panel capacity permits a single explicitly selected feed, never a second.
        if (max == null || active == null || max <= 0) return (1 - local).coerceAtLeast(0)
        return (max - maxOf(active, local)).coerceAtLeast(0)
    }

    fun deviceLimit(context: Context): Int {
        val memory = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val instances = try {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                !it.isEncoder && !it.name.startsWith("OMX.google.") && !it.name.startsWith("c2.android.")
            }
            // Take the weaker of AVC and HEVC; absent capability information means one tile.
            listOf("video/avc", "video/hevc").minOf { mime ->
                codecs.filter { mime in it.supportedTypes }.maxOfOrNull {
                    it.getCapabilitiesForType(mime).maxSupportedInstances
                } ?: 1
            }
        } catch (_: Exception) { 1 }
        return tileLimit(memory.isLowRamDevice, memory.memoryClass, instances)
    }
}

/** Serializes admission across this screen's tiles, including asynchronous URL/backup work. */
class MultiViewConnections(private val limitsFor: suspend (Int) -> XtreamRepository.AccountLimits?) {
    constructor(repository: XtreamRepository) : this({ repository.accountLimits(it, forceRefresh = true) })
    private val lock = Mutex()
    private val held = mutableMapOf<Int, Int>()
    private val external = mutableMapOf<Int, Int>()
    private var generation = 0

    suspend fun acquire(tile: Int, server: Int): Boolean = lock.withLock {
        val ticket = generation
        if (held[tile] == server) return@withLock true // replacement/retry owns the same slot
        val local = held.values.count { it == server }
        val limits = limitsFor(server)
        currentCoroutineContext().ensureActive()
        if (ticket != generation) return@withLock false
        if (limits != null && !limits.isActive) return@withLock false
        val outside = maxOf(external[server] ?: 0, ((limits?.activeConnections ?: 0) - local).coerceAtLeast(0))
        external[server] = outside
        if (MultiViewPolicy.providerSlots(limits?.maxConnections, limits?.activeConnections?.let { outside + local }, local) < 1) return@withLock false
        held[tile] = server
        true
    }

    fun release(tile: Int) { held.remove(tile); generation++ }
    fun clear() { held.clear(); external.clear(); generation++ }
}

/** Mute before unmuting, including when moving backwards through the tile list. */
object MultiViewAudio {
    fun select(players: List<androidx.media3.common.Player>, selected: Int, muted: Boolean = false) {
        players.forEach { it.volume = 0f }
        if (!muted) players.getOrNull(selected)?.volume = 1f
    }
}
