package com.iptvapp.data.epg

import com.iptvapp.data.local.dao.EpgCoverageRow
import com.iptvapp.data.local.entities.EpgGuideSourceEntity
import com.iptvapp.data.local.entities.EpgOverrideEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** The health scan's classification, over plain data. */
class EpgHealthScannerTest {
    /** A source time moved by [minutes], as the device shows it in [zone] (its own daylight-saving rules). */
    private fun displayed(startSec: Long, minutes: Int, zone: ZoneId) =
        java.time.Instant.ofEpochSecond(startSec + EpgTimeOffset.seconds(minutes)).atZone(zone).toLocalDateTime()

    private val nowMs = 1_800_000_000_000L
    private val now = nowMs / 1000
    private val provider = "pkey"

    private fun guide(id: String, name: String, source: String = "prov", stop: Long = now + 3 * 86_400) =
        EpgGuideChannel(-1, source, id, name, 10, stop, "")

    private fun record(key: String, result: EpgSourceResult = EpgSourceResult.OK, success: Long = nowMs - 3600_000, horizon: Long = now + 3 * 86_400) =
        EpgGuideSourceEntity(-1, key, "PROVIDER", "Provider guide", 0, success, success, result.name, 10, 100, 0, 0, horizon)

    private fun input(
        channels: List<EpgLocalChannel>,
        guides: List<EpgGuideChannel>,
        coverage: Map<Int, Long> = emptyMap(),
        rowIds: Map<Int, String> = emptyMap(),
        records: List<EpgGuideSourceEntity> = listOf(record("prov")),
        overrides: List<EpgOverrideEntity> = emptyList(),
        sources: List<String> = listOf("prov"),
        evidence: Map<Int, Pair<List<EpgSamples.Listing>, List<EpgSamples.Listing>>> = emptyMap(),
    ) = EpgScanInput(
        -1, "Main", provider, sources.map { Triple(it, EpgSourceKind.PROVIDER, it) }, channels, guides,
        coverage.map { (s, stop) -> EpgCoverageRow(s, stop, 5) }, rowIds, records, overrides, evidence
    )

    private fun ch(id: Int, name: String, epg: String? = null) = EpgLocalChannel(-1, id, name, epg)

    private fun scan(vararg inputs: EpgScanInput) = runBlocking { EpgHealthScanner.scan(inputs.toList(), nowMs, nowMs - 7200_000) }

    // 10. Missing listings: fixable vs genuinely unavailable
    @Test
    fun missingListingsAreSplitIntoFixableAndUnavailable() {
        val r = scan(input(listOf(ch(1, "AMC HD"), ch(2, "Obscure Local 9")), listOf(guide("amc.us", "AMC"))))
        val amc = r.issues.single { it.channel.streamId == 1 }
        assertEquals(EpgIssueKind.MISSING_FIXABLE, amc.kind)
        assertTrue(amc.safe)
        val other = r.issues.single { it.channel.streamId == 2 }
        assertEquals(EpgIssueKind.MISSING_UNAVAILABLE, other.kind)
        assertEquals("No configured guide lists this channel", other.detail)
        assertEquals(2, r.totals.checked)
        assertEquals(0, r.totals.withListings)
        assertEquals(1, r.totals.safeSuggestions)
    }

    // 11. Stale: listings ran out, or end soon while the guide covers days
    @Test
    fun staleListingsAreReportedButNotForAnEmptyMoment() {
        val r = scan(
            input(
                listOf(ch(1, "News"), ch(2, "Sports"), ch(3, "Movies")),
                listOf(guide("news", "News"), guide("sports", "Sports"), guide("movies", "Movies")),
                coverage = mapOf(1 to now - 7200, 2 to now + 1800, 3 to now + 2 * 86_400),
            )
        )
        assertEquals(EpgIssueKind.STALE, r.issues.single { it.channel.streamId == 1 }.kind)
        assertTrue(r.issues.single { it.channel.streamId == 2 }.detail.startsWith("Listings end in"))
        // Listings for the next two days are fine, whatever is on at this second.
        assertTrue(r.issues.none { it.channel.streamId == 3 })
        assertEquals(2, r.totals.stale)
    }

    // 12. A valid but empty source
    @Test
    fun anEmptySourceIsReportedAsSuch() {
        val r = scan(input(listOf(ch(1, "AMC")), emptyList(), records = listOf(record("prov", EpgSourceResult.EMPTY))))
        val issue = r.issues.single()
        assertEquals(EpgIssueKind.MISSING_UNAVAILABLE, issue.kind)
        assertEquals("The guide source returned no guide entries at the last refresh", issue.detail)
        assertTrue(r.sources.single().notes.contains("Source returned no guide entries"))
    }

    @Test
    fun aSourceNotRefreshedForDaysGetsANote() {
        val notes = EpgHealthScanner.sourceNotes(record("prov", success = nowMs - 3 * 86_400_000L, horizon = now - 60), EpgSourceKind.PROVIDER, nowMs)
        assertTrue(notes.contains("Last successful refresh was 3 days ago"))
        assertTrue(notes.contains("No future listings available"))
    }

    @Test
    fun aMismatchedFeedIsSuspected() {
        val r = scan(
            input(
                listOf(ch(1, "HBO East")),
                listOf(guide("hbo.west", "HBO West"), guide("hbo.east", "HBO East")),
                coverage = mapOf(1 to now + 86_400),
                rowIds = mapOf(1 to "x_hbo.west_${now}"),
            )
        )
        val issue = r.issues.single()
        assertEquals(EpgIssueKind.SUSPECT, issue.kind)
        assertEquals("hbo.east", issue.match!!.best!!.guide.xmltvId)
        assertFalse(issue.safe)
    }

    @Test
    fun aSharedGuideIdIsFixableButNotSafe() {
        val r = scan(
            input(
                listOf(ch(1, "AMC", "amc.us"), ch(2, "AMC Backup", "amc.us")),
                listOf(guide("amc.us", "AMC")),
                coverage = mapOf(1 to now + 86_400),
                rowIds = mapOf(1 to "x_amc.us_${now}"),
            )
        )
        val issue = r.issues.single { it.channel.streamId == 2 }
        assertEquals(EpgIssueKind.MISSING_FIXABLE, issue.kind)
        assertTrue(issue.detail.startsWith("Shares its guide ID"))
        assertFalse(issue.safe)
    }

    @Test
    fun savedCorrectionsAreListedAndBrokenOnesFlagged() {
        val ok = EpgOverrideEntity(-1, 1, provider, "MAP", "prov", "amc.us", "AMC", "AMC", 0, 0, 0)
        val gone = EpgOverrideEntity(-1, 2, provider, "MAP", "prov", "vanished.us", "Gone", "Gone", 0, 0, 0)
        val r = scan(input(listOf(ch(1, "AMC HD"), ch(2, "Gone")), listOf(guide("amc.us", "AMC")), coverage = mapOf(1 to now + 3600 * 10), rowIds = mapOf(1 to "x_amc.us_1_${now}"), overrides = listOf(ok, gone)))
        assertEquals(EpgIssueKind.CORRECTED, r.issues.single { it.channel.streamId == 1 }.kind)
        assertEquals(EpgIssueKind.CORRECTION_BROKEN, r.issues.single { it.channel.streamId == 2 }.kind)
        assertEquals(1, r.totals.corrections)
        assertEquals(1, r.totals.brokenCorrections)
    }

    @Test
    fun aGuideIdEndingInTheStreamIdIsResolvedFromTheGuide() {
        // Guide channel "station_7" feeds main-provider stream 7 by name (plain row id): not "station".
        val r = scan(
            input(
                listOf(ch(7, "Station West")),
                listOf(guide("station_7", "Station East"), guide("station", "Station West")),
                coverage = mapOf(7 to now + 86_400),
                rowIds = mapOf(7 to "x_station_7_${now}"),
            )
        )
        assertEquals("station_7", r.issues.single().current!!.xmltvId)
        assertEquals(EpgIssueKind.SUSPECT, r.issues.single().kind)
    }

    @Test
    fun aCorrectionWhoseListingsDidNotLoadIsNotReportedAsWorking() {
        val ok = EpgOverrideEntity(-1, 1, provider, "MAP", "prov", "amc.west", "AMC West", "AMC", 0, 0, 0)
        val r = scan(
            input(
                listOf(ch(1, "AMC HD")), listOf(guide("amc.east", "AMC East"), guide("amc.west", "AMC West")),
                coverage = mapOf(1 to now + 86_400), rowIds = mapOf(1 to "x_amc.east_${now}"), overrides = listOf(ok),
            )
        )
        val issue = r.issues.single()
        assertEquals(EpgIssueKind.CORRECTION_BROKEN, issue.kind)
        assertTrue(issue.detail.contains("still shows its previous listings"))
    }

    // 16. Provider deletion safety: a correction saved for another provider is kept, not used.
    @Test
    fun aCorrectionForAnotherProviderIsKeptButNotUsed() {
        val foreign = EpgOverrideEntity(-1, 1, "someone-else", "MAP", "prov", "amc.us", "AMC", "AMC", 0, 0, 0)
        val r = scan(input(listOf(ch(1, "AMC HD")), listOf(guide("amc.us", "AMC")), overrides = listOf(foreign)))
        assertEquals(listOf(foreign), r.unusedCorrections)
        assertEquals(EpgIssueKind.MISSING_FIXABLE, r.issues.single().kind)
    }

    // 17. Source deletion safety: a list left from a removed source is never suggested from.
    @Test
    fun aRemovedSourcesChannelsAreIgnored() {
        val r = scan(input(listOf(ch(1, "AMC HD")), listOf(guide("amc.us", "AMC", source = "removed")), sources = listOf("prov")))
        assertEquals(EpgIssueKind.MISSING_UNAVAILABLE, r.issues.single().kind)
    }

    @Test
    fun theProvidersOwnGuideSuggestsAnOffset() {
        val ref = (0 until 5).map { EpgSamples.Listing(now + it * 3600, now + it * 3600 + 3600, "Show $it") }
        val xml = ref.map { it.copy(startSec = it.startSec - 3600, stopSec = it.stopSec - 3600) }
        val r = scan(input(listOf(ch(1, "News")), listOf(guide("news", "News")), coverage = mapOf(1 to now + 3 * 86_400), evidence = mapOf(1 to (ref to xml))))
        val issue = r.issues.single()
        assertEquals(EpgIssueKind.OFFSET_HINT, issue.kind)
        assertEquals(60, issue.offsetHint)
    }

    // 24. A cancelled scan stops and changes nothing.
    @Test
    fun aCancelledScanStops() = runBlocking {
        val big = (0 until 5_000).map { ch(it, "Channel $it") }
        val job = Job()
        job.cancel()
        try {
            withContext(job) { EpgHealthScanner.scan(listOf(input(big, emptyList())), nowMs, 0) }
            fail("expected cancellation")
        } catch (_: CancellationException) {}
    }

    // 18–20. Offsets, daylight saving, and display time
    @Test
    fun offsetsAreQuarterHoursWithinTwelveHours() {
        assertTrue(EpgTimeOffset.isValid(-120))
        assertTrue(EpgTimeOffset.isValid(45))
        assertFalse(EpgTimeOffset.isValid(10))
        assertFalse(EpgTimeOffset.isValid(13 * 60))
        assertEquals(3600L, EpgTimeOffset.seconds(60))
        assertEquals("+1 h 30 min", EpgTimeOffset.label(90))
        assertEquals("−30 min", EpgTimeOffset.label(-30))
    }

    @Test
    fun daylightSavingComesFromTheZoneNotAFixedHour() {
        val ny = ZoneId.of("America/New_York")
        // 2026-11-01 01:30 happens twice in New York; XMLTV times carry their own offset.
        val beforeEnd = java.time.OffsetDateTime.parse("2026-11-01T01:30:00-04:00").toEpochSecond()
        val afterEnd = java.time.OffsetDateTime.parse("2026-11-01T01:30:00-05:00").toEpochSecond()
        assertEquals(3600L, afterEnd - beforeEnd)
        assertEquals(LocalDateTime.parse("2026-11-01T01:30"), displayed(beforeEnd, 0, ny))
        assertEquals(LocalDateTime.parse("2026-11-01T01:30"), displayed(afterEnd, 0, ny))
        // An offset moves the instant; the zone's rules still decide the wall clock.
        assertEquals(LocalDateTime.parse("2026-11-01T01:30"), displayed(beforeEnd, 60, ny))
        // Spring forward: 02:30 doesn't exist; one hour after 01:30 EST is 03:30 EDT.
        val spring = java.time.OffsetDateTime.parse("2026-03-08T01:30:00-05:00").toEpochSecond()
        assertEquals(LocalDateTime.parse("2026-03-08T03:30"), displayed(spring, 60, ny))
    }

    @Test
    fun oneOddListingSuggestsNoOffset() {
        val ref = (0 until 6).map { EpgSamples.Listing(now + it * 3600, now + it * 3600 + 3600, "Show $it") }
        val xml = ref.mapIndexed { i, l -> if (i == 0) l.copy(startSec = l.startSec - 3600) else l }
        assertEquals(null, EpgTimeOffset.suggest(ref, xml))
        assertEquals(null, EpgTimeOffset.suggest(ref.take(2), ref.take(2).map { it.copy(startSec = it.startSec - 3600) }))
    }

    @Test
    fun samplesRoundTrip() {
        val l = listOf(EpgSamples.Listing(1, 2, "A\tB\nC"), EpgSamples.Listing(3, 4, "D"))
        assertEquals(listOf(EpgSamples.Listing(1, 2, "A B C"), EpgSamples.Listing(3, 4, "D")), EpgSamples.decode(EpgSamples.encode(l)))
    }

    @Test
    fun sourceKeysHoldNoAddressOrCredential() {
        val url = "http://provider.example:8080/xmltv.php?username=alice&password=s3cret"
        val key = EpgSourceKeys.sourceKey(url, "salt")
        assertFalse(key.contains("alice") || key.contains("s3cret") || key.contains("provider"))
        assertEquals(key, EpgSourceKeys.sourceKey(url, "salt"))
        assertFalse(key == EpgSourceKeys.sourceKey(url, "other-salt"))
        assertEquals("Custom guide (epg.example.com)", EpgSourceKeys.label(EpgSourceKind.CUSTOM, "https://epg.example.com/guide.xml?token=abc", null))
        assertFalse(EpgSourceKeys.providerKey("http://p.example", "alice", "salt").contains("alice"))
    }
}
