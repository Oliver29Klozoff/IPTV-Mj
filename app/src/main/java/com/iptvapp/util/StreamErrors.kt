package com.iptvapp.util

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/** Classifies player errors into causes worth telling the user about by name, rather than a
 * generic "stream unavailable". */
object StreamErrors {
    /** Xtream panels answer with these non-standard statuses when the account's connection
     * limit is already used up — 460 is what shop4uu sends when another device is watching on
     * the same login. Unlike 403/429 (also used for expired accounts, geo blocks, rate limits),
     * they mean "account busy" and nothing else, so it's safe to say so outright. */
    private val ACCOUNT_IN_USE_CODES = setOf(458, 460, 461)

    const val ACCOUNT_IN_USE_MESSAGE =
        "Account in use — your provider allows one stream at a time and another device is watching. Stop it there to watch here."

    fun httpStatus(error: PlaybackException): Int? {
        if (error.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return null
        return (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
    }

    fun isAccountInUse(error: PlaybackException): Boolean = httpStatus(error) in ACCOUNT_IN_USE_CODES
}
