package com.iptvapp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Logins in the URL shapes MKTV handles never reach a log line. */
class LogSanitizerTest {

    @Test fun xtreamPathLoginsAreRedacted() {
        val out = LogSanitizer.redactCredentials("--> GET http://p.example:8080/live/alice/s3cret/123.m3u8")
        assertFalse(out.contains("alice") || out.contains("s3cret"))
        assertEquals("--> GET http://p.example:8080/live/[REDACTED]/[REDACTED]/123.m3u8", out)
        for (kind in listOf("movie", "series", "timeshift")) {
            val r = LogSanitizer.redactCredentials("http://p.example/$kind/alice/s3cret/9.mkv")
            assertFalse(kind, r.contains("alice") || r.contains("s3cret"))
        }
    }

    @Test fun queryLoginsAndTokensAreRedacted() {
        val out = LogSanitizer.redactCredentials(
            "<-- 200 http://p.example/player_api.php?username=alice&password=s3cret&action=get_live_streams " +
                "https://cdn.example/a.m3u8?token=abc123&x=1 https://cdn.example/b.ts?Signature=zz9&Key=k1"
        )
        for (secret in listOf("alice", "s3cret", "abc123", "zz9", "k1")) assertFalse(secret, out.contains(secret))
        assertEquals(true, out.contains("action=get_live_streams") && out.contains("x=1"))
    }
}
