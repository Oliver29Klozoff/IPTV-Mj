package com.iptvapp.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/** Failures become Resource.Error; cancellation stays cancellation (v7.21). */
class SafeApiCallTest {

    // ── Ordinary failures still become errors ────────────────────────────────

    @Test fun successIsReturned() = runBlocking {
        assertEquals(Resource.Success(42), safeApiCall { 42 })
    }

    @Test fun networkFailuresBecomeErrors() = runBlocking {
        val failures = listOf(
            SocketTimeoutException("timeout"),                 // request timeout
            UnknownHostException("no.such.host"),              // DNS failure
            IOException("Connection reset"),                   // connection reset
            Exception("Server returned 401"),                  // HTTP / authentication failure
            IllegalStateException("Expected BEGIN_ARRAY but was STRING"), // malformed response
            Exception("Empty response from server")            // empty response
        )
        for (failure in failures) {
            val result = safeApiCall<Int> { throw failure }
            assertTrue("${failure.javaClass.simpleName} -> $result", result is Resource.Error)
            result as Resource.Error
            assertEquals(failure.message, result.message)
            assertSame(failure, result.cause)
        }
    }

    // ── Cancellation is not an error ─────────────────────────────────────────

    @Test fun cancellationThrownInsideIsRethrown() = runBlocking {
        val cancel = CancellationException("cancelled")
        try {
            safeApiCall<Int> { throw cancel }
            fail("cancellation must not become a Resource")
        } catch (e: CancellationException) {
            assertSame(cancel, e)
        }
    }

    @Test fun cancellingTheCallerStopsItWithoutAnErrorOrLaterWork() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        var result: Resource<Int>? = null
        var ranAfterTheCall = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            result = safeApiCall {
                requestStarted.complete(Unit)
                CompletableDeferred<Int>().await() // a request that never answers
            }
            ranAfterTheCall = true // e.g. a database write after the fetch
        }
        requestStarted.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("no Resource.Error for a cancelled call", null, result)
        assertFalse("nothing after the call runs once cancelled", ranAfterTheCall)
    }

    @Test fun aCallerTimeoutPropagatesAsTimeoutNotError() = runBlocking {
        try {
            withTimeout(50) { safeApiCall { CompletableDeferred<Int>().await() } }
            fail("the timeout must reach the caller")
        } catch (e: TimeoutCancellationException) {
            // expected
        }
    }

    // ── Per-provider loops: rethrowIfCancelled ───────────────────────────────

    @Test fun perProviderTimeoutIsAFailureButJobCancellationIsNot() {
        rethrowIfCancelled(IOException("down"))                 // ordinary failure: returns
        try {
            runBlocking { withTimeout(1) { CompletableDeferred<Unit>().await() } }
        } catch (e: TimeoutCancellationException) {
            rethrowIfCancelled(e)                               // its own timeout: returns
        }
        try {
            rethrowIfCancelled(CancellationException("job cancelled"))
            fail("job cancellation must be rethrown")
        } catch (_: CancellationException) {
        }
    }

    @Test fun aCancelledMultiProviderRefreshDoesNotRecordFailuresOrWrite() = runBlocking {
        // The shape of refreshMergedChannels/Vod/Series: each provider's failure becomes a message,
        // then the results are written. Cancelling the refresh must skip both.
        val errors = mutableMapOf<Int, String>()
        var wrote = false
        val started = CompletableDeferred<Unit>()
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineScope {
                (0 until 3).map { provider ->
                    async {
                        try {
                            if (provider == 0) started.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        } catch (e: Exception) {
                            rethrowIfCancelled(e)
                            errors[provider] = e.message ?: "error"
                        }
                    }
                }.awaitAll()
            }
            wrote = true
        }
        started.await()
        yield()
        refresh.cancel()
        refresh.join()
        assertTrue(refresh.isCancelled)
        assertTrue("no provider recorded as failed: $errors", errors.isEmpty())
        assertFalse("no write after cancellation", wrote)
    }
}
