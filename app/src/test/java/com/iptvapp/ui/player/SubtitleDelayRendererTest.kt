package com.iptvapp.ui.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

@UnstableApi
class SubtitleDelayRendererTest {
    /** A renderer that only records the positions it is given. */
    private fun recording(log: MutableList<Pair<String, Long>>): Renderer =
        Proxy.newProxyInstance(Renderer::class.java.classLoader, arrayOf(Renderer::class.java)) { _, method, args ->
            when (method.name) {
                "render", "resetPosition" -> { log += method.name to (args[0] as Long); null }
                "getTrackType" -> 3
                "getName" -> "fake"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as Renderer

    @Test
    fun laterSubtitlesSeeAnEarlierClock() {
        val log = mutableListOf<Pair<String, Long>>()
        val state = SubtitleDelayState()
        val r = SubtitleDelayRenderer(recording(log), state)
        state.offsetMs = 1_500
        r.render(10_000_000L, 0L)
        state.offsetMs = -2_000
        r.render(10_000_000L, 0L)
        state.offsetMs = 0
        r.render(10_000_000L, 0L)
        r.resetPosition(4_000_000L, false)
        assertEquals(
            listOf("render" to 8_500_000L, "render" to 12_000_000L, "render" to 10_000_000L, "resetPosition" to 4_000_000L),
            log,
        )
    }

    @Test
    fun aSeekCarriesTheOffset() {
        val log = mutableListOf<Pair<String, Long>>()
        val state = SubtitleDelayState().apply { offsetMs = 500 }
        SubtitleDelayRenderer(recording(log), state).resetPosition(60_000_000L, true)
        assertEquals(listOf("resetPosition" to 59_500_000L), log)
    }
}
