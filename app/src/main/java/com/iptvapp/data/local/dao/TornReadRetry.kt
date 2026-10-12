package com.iptvapp.data.local.dao

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/**
 * Re-runs a large catalog query whose read was torn by a concurrent write.
 *
 * A result this size spans several cursor windows, and Android re-runs the query for each window.
 * When a refresh deletes rows between two windows, the later window comes back short and the read
 * throws "Couldn't read row N, col M from CursorWindow" — which crashed the app from whichever
 * screen was collecting the list (a 100k-movie refresh that removed titles did it every time).
 * Reading again once the writes settle gives a consistent list. A read transaction would also
 * prevent the tear, but with the framework SQLite driver it holds the one primary connection and
 * stalls the refresh's writes for the length of every 100k-row read.
 *
 * Only that exact error is retried, a bounded number of times; anything else, or a cursor that
 * keeps failing (a single row too large for a window would fail the same way every time), is
 * rethrown as before.
 */
internal fun <T> Flow<T>.retryOnTornRead(maxAttempts: Long = 8): Flow<T> = retryWhen { cause, attempt ->
    if (attempt >= maxAttempts || !isTornRead(cause)) return@retryWhen false
    delay(minOf(4_000L, 250L shl attempt.toInt()))
    true
}

internal fun isTornRead(e: Throwable): Boolean =
    e is IllegalStateException && e.message?.contains("from CursorWindow") == true
