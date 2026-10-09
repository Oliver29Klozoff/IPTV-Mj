package com.iptvapp.util

import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException

sealed class Resource<out T> {
    object Loading : Resource<Nothing>()
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val message: String, val cause: Throwable? = null) : Resource<Nothing>()

    val isLoading get() = this is Loading
    val isSuccess get() = this is Success
    val isError get() = this is Error
}

/** Runs [call], turning a failure (timeout, no network, HTTP error, bad response…) into
 * [Resource.Error]. Cancellation is not a failure: it is rethrown, so a cancelled refresh stops
 * where it is — no error shown, no retry, no write after it — instead of carrying on as if a
 * request had failed (v7.21). Runs on the caller's dispatcher; work that is heavy after the
 * response moves itself off the main thread. */
suspend fun <T> safeApiCall(call: suspend () -> T): Resource<T> {
    return try {
        Resource.Success(call())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Resource.Error(e.localizedMessage ?: "Unknown error", e)
    }
}

/** For loops that turn each provider's failure into a message (one bad provider mustn't stop the
 * rest): call first in their catch. A timeout of that one attempt (withTimeout) is a failure like
 * any other and returns; any other cancellation is the whole job being cancelled and is rethrown. */
fun rethrowIfCancelled(e: Throwable) {
    if (e is CancellationException && e !is TimeoutCancellationException) throw e
}
