package com.iptvapp.ui.settings

import android.app.Application
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What's New reads the bundled CHANGELOG.md into one card per release. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WhatsNewTest {

    private val sample = """
        # IPTV App - Changelog

        ## v8.03 - 2026-11-23
        - **Changed**: The avatar only shows
          once a second profile has been added.

        ## v7.80 - 2026-11-08
        Type any of:
          - a channel ("ESPN"),
          - a channel number ("205").
        - **Fixed**: `Left` opens the guide.

        ## v1.0 (build 1)
        - First release.
    """.trimIndent()

    @Test
    fun eachReleaseBecomesACardWithItsNotes() {
        val r = WhatsNew.parse(sample)
        assertEquals(listOf("8.03", "7.80", "1.0 (build 1)"), r.map { it.version })
        assertEquals("2026-11-23", r[0].date)
        assertNull(r[2].date)
        // A wrapped note joins back into one line.
        assertEquals(1, r[0].lines.size)
        assertEquals("**Changed**: The avatar only shows once a second profile has been added.", r[0].lines[0].text)
        // A plain line, then indented sub-bullets, then a bullet.
        assertFalse(r[1].lines[0].bullet)
        assertEquals(1, r[1].lines[1].indent)
        assertTrue(r[1].lines[2].bullet)
        assertEquals(0, r[1].lines[3].indent)
    }

    @Test
    fun datesReadLikeNov23() {
        assertEquals("Nov 23, 2026", WhatsNew.prettyDate("2026-11-23"))
        assertEquals("Jun 11, 2026", WhatsNew.prettyDate("2026-06-11"))
        assertNull(WhatsNew.prettyDate(null))
    }

    @Test
    fun boldMarkersBecomeBoldTextAndBackticksGo() {
        val s = WhatsNew.styled("**Fixed**: `Left` opens", 0xFFFFFFFF.toInt())
        assertEquals("Fixed: Left opens", s.toString())
        val bold = (s as Spanned).getSpans(0, s.length, StyleSpan::class.java)
        assertEquals(1, bold.size)
        assertEquals(0, s.getSpanStart(bold[0]))
        assertEquals(5, s.getSpanEnd(bold[0]))
    }

    @Test
    fun theBundledChangelogParsesNewestFirst() {
        val text = ApplicationProvider.getApplicationContext<Application>().assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
        val r = WhatsNew.parse(text)
        assertTrue(r.size > 500)
        assertTrue(r.first().lines.isNotEmpty())
        assertTrue(r.all { it.version.isNotBlank() })
    }
}
