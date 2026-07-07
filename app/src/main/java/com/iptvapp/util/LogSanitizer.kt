package com.iptvapp.util

/** Xtream Codes' API embeds the plaintext account username/password directly in every stream
 * URL's path (e.g. /live/USER/PASS/12345.m3u8) and as query params on API calls
 * (username=USER&password=PASS) — that's the protocol's own design, not something this app
 * controls. Network/player exceptions routinely include the failing URL in their message, so
 * anything that persists or transmits a raw exception (crash logs, debug reports) can leak
 * live credentials unless those URL shapes are redacted first. This works structurally, by
 * matching the URL shape, rather than needing to know the actual stored credential value —
 * it still catches stale/cached/multi-server cases a value-based redaction would miss. */
object LogSanitizer {

    private val pathCredentials = Regex("""(/(?:live|movie|series|timeshift)/)[^/\s]+/[^/\s]+/""")
    private val queryUsername = Regex("""(username=)[^&\s"]+""")
    private val queryPassword = Regex("""(password=)[^&\s"]+""")

    fun redactCredentials(text: String): String {
        var result = pathCredentials.replace(text) { m -> "${m.groupValues[1]}[REDACTED]/[REDACTED]/" }
        result = queryUsername.replace(result) { m -> "${m.groupValues[1]}[REDACTED]" }
        result = queryPassword.replace(result) { m -> "${m.groupValues[1]}[REDACTED]" }
        return result
    }
}
