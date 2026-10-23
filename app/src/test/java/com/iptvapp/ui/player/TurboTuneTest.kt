package com.iptvapp.ui.player

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class TurboTuneTest {
    @Test fun selectionsCancelledBeforeResolutionAreExcludedFromFailures() {
        val output = mutableListOf<String>()
        val trace = TuneTrace({ 100L }, output::add)
        trace.select("automatic")
        trace.select("automatic")
        trace.cancel()
        trace.cancel()
        assertEquals(1, output.count { "outcome=superseded" in it })
        assertEquals(1, output.count { "outcome=cancelled" in it })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancelledResolverCannotReopenOldChannel() = runTest {
        val played = mutableListOf<String>()
        val old = launch {
            val url = TurboTune.resolveLatest {
                try {
                    delay(500)
                } catch (_: CancellationException) {
                    // Model a provider resolver that catches cancellation and returns anyway.
                }
                "old"
            }
            played += url
        }
        runCurrent()
        old.cancel()
        launch { played += TurboTune.resolveLatest { "final" } }
        advanceUntilIdle()
        assertEquals(listOf("final"), played)
    }

    @Test fun resolverFailureStillReachesRecoveryCaller() = runTest {
        var recovered = false
        try {
            TurboTune.resolveLatest<String> { throw java.io.IOException("unavailable") }
        } catch (_: java.io.IOException) { recovered = true }
        assertTrue(recovered)
    }
    @Test fun offRetainsStartupAndSurfTiming() {
        assertEquals(5000, TurboTune.startupBufferMs("off", 5000, false))
        assertEquals(0L, TurboTune.debounceMs("off", 0))
    }
    @Test fun protectedRecoveryProfilesArePreserved() {
        assertEquals(10000, TurboTune.startupBufferMs("performance", 10000, true))
        assertEquals(500, TurboTune.startupBufferMs("automatic", 5000, false))
        assertEquals(300L, TurboTune.debounceMs("automatic", 300))
    }
    @Test fun speculativeStreamsRequirePerformanceAndMemory() {
        assertFalse(TurboTune.allowMediaPreload("automatic", false, false))
        assertFalse(TurboTune.allowMediaPreload("off", false, false))
        assertFalse(TurboTune.allowMediaPreload("performance", true, false))
        assertFalse(TurboTune.allowMediaPreload("performance", false, true))
        assertTrue(TurboTune.allowMediaPreload("performance", false, false))
    }
    @Test fun oldAndCancelledEventsCannotClaimSuccessfulTune() {
        var now = 100L
        val output = mutableListOf<String>()
        val trace = TuneTrace({ now }, output::add)
        trace.select("off")
        trace.commit("old")
        trace.select("automatic")
        trace.event("old", "video")
        trace.commit("new")
        now = 230
        trace.event("new", "ready")
        assertFalse(output.any { "event=video" in it })
        trace.event("new", "video")
        trace.event("new", "video")
        assertEquals(1, output.count { "event=video" in it })
        assertTrue(output.last().endsWith("elapsedMs=130"))
        trace.cancel()
        trace.event("new", "audio")
        assertFalse(output.any { "event=audio" in it })
    }
}
