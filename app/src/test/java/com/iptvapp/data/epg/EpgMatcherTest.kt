package com.iptvapp.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guide matching: what counts as the same channel, and how sure a suggestion may be. */
class EpgMatcherTest {
    private val now = 1_800_000_000L
    private val later = now + 6 * 3600

    private fun guide(id: String, name: String, source: String = "prov", stop: Long = later) =
        EpgGuideChannel(-1, source, id, name, programCount = 10, lastStop = stop, sample = "")

    private fun local(name: String, id: String? = null, stream: Int = 1, server: Int = -1) = EpgLocalChannel(server, stream, name, id)

    private fun index(vararg g: EpgGuideChannel, rank: Map<String, Int> = mapOf("prov" to 0, "custom" to 1, "public" to 2)) =
        EpgMatchIndex(g.toList()) { rank[it] ?: 9 }

    // 1. Exact EPG id
    @Test
    fun exactGuideIdWins() {
        val idx = index(guide("AMC.us", "AMC"), guide("amc.east", "AMC East"))
        val r = EpgMatcher.suggest(local("Some odd name", "amc.US"), idx, now)
        assertEquals(MatchState.EXACT, r.state)
        assertEquals("AMC.us", r.best!!.guide.xmltvId)
        assertTrue(r.reason.startsWith("Exact guide ID"))
    }

    // 2. Normalized name
    @Test
    fun normalizedNameMatchesAcrossPrefixesAndPunctuation() {
        val idx = index(guide("cnn.us", "CNN"))
        val r = EpgMatcher.suggest(local("US: C.N.N."), idx, now)
        // "C.N.N." is c n n, not cnn: punctuation splits words, so this is not a match…
        assertEquals(MatchState.NONE, r.state)
        val r2 = EpgMatcher.suggest(local("US| CNN"), idx, now)
        assertEquals(MatchState.HIGH, r2.state)
        assertEquals("cnn.us", r2.best!!.guide.xmltvId)
    }

    // 3. HD / FHD suffixes
    @Test
    fun qualityTagsAreIgnored() {
        val idx = index(guide("espn.us", "ESPN"), guide("amc.us", "AMC"), guide("hbo.east", "HBO East"))
        assertEquals("espn.us", EpgMatcher.suggest(local("ESPN HD"), idx, now).best!!.guide.xmltvId)
        assertEquals("amc.us", EpgMatcher.suggest(local("AMC FHD"), idx, now).best!!.guide.xmltvId)
        val hbo = EpgMatcher.suggest(local("HBO East HD"), idx, now)
        assertEquals(MatchState.HIGH, hbo.state)
        assertEquals("hbo.east", hbo.best!!.guide.xmltvId)
    }

    // 4. ESPN vs ESPN2
    @Test
    fun espnAndEspn2StayApart() {
        val idx = index(guide("espn2.us", "ESPN2"))
        val r = EpgMatcher.suggest(local("ESPN HD"), idx, now)
        assertTrue(r.candidates.none { it.guide.xmltvId == "espn2.us" })
        val idx2 = index(guide("espn.us", "ESPN"))
        val r2 = EpgMatcher.suggest(local("ESPN 2 HD"), idx2, now)
        assertTrue(r2.candidates.none { it.guide.xmltvId == "espn.us" })
        // ESPN+ is not ESPN either.
        assertTrue(EpgMatcher.suggest(local("ESPN+"), idx2, now).candidates.none { it.guide.xmltvId == "espn.us" })
    }

    // 5. East vs West
    @Test
    fun eastAndWestFeedsStayApart() {
        val idx = index(guide("hbo.west", "HBO West"))
        val r = EpgMatcher.suggest(local("HBO East"), idx, now)
        assertNotEquals(MatchState.HIGH, r.state)
        assertNotEquals(MatchState.EXACT, r.state)
        assertFalse(EpgMatcher.safeToApply(r, local("HBO East"), now) { null })
        // The id's feed counts even when the display name doesn't say it.
        val idx2 = index(guide("HBO.Pacific.us", "HBO"))
        assertNotEquals(MatchState.HIGH, EpgMatcher.suggest(local("HBO East"), idx2, now).state)
    }

    // 6. Regional affiliates
    @Test
    fun localAffiliatesStayApart() {
        val idx = index(guide("kttv.us", "FOX (KTTV) Los Angeles"), guide("wnyw.us", "FOX (WNYW) New York"))
        val la = EpgMatcher.suggest(local("FOX (KTTV) Los Angeles HD"), idx, now)
        assertEquals(MatchState.HIGH, la.state)
        assertEquals("kttv.us", la.best!!.guide.xmltvId)
        // A plain "FOX" could be either: the user picks.
        val plain = EpgMatcher.suggest(local("FOX"), idx, now)
        assertTrue(plain.state == MatchState.POSSIBLE || plain.state == MatchState.AMBIGUOUS)
        assertFalse(EpgMatcher.safeToApply(plain, local("FOX"), now) { null })
        // A different station never matches.
        val ny = EpgMatcher.suggest(local("FOX (KTTV)"), index(guide("wnyw.us", "FOX (WNYW)")), now)
        assertNotEquals(MatchState.HIGH, ny.state)
    }

    // 7. Duplicate stream ids across providers
    @Test
    fun sameStreamIdOnAnotherProviderIsAnotherChannel() {
        val idx = index(guide("amc.us", "AMC"))
        val claimed = { ref: GuideRef -> if (ref.xmltvIdLower == "amc.us") 5 else null }
        // Provider -1's stream 5 already has AMC; provider 0's stream 5 is a different channel,
        // but the claim map is per provider, so it is passed per provider by the scan.
        val other = local("AMC", stream = 5, server = 0)
        val r = EpgMatcher.suggest(other, idx, now) { null }
        assertTrue(EpgMatcher.safeToApply(r, other, now) { null })
        // On the same provider, stream 6 asking for the guide channel stream 5 has is not safe.
        val same = local("AMC", stream = 6)
        assertFalse(EpgMatcher.safeToApply(EpgMatcher.suggest(same, idx, now, claimed), same, now, claimed))
    }

    // 8. Exact before fuzzy
    @Test
    fun guideIdBeatsAName() {
        val idx = index(guide("bravo.us", "Bravo"), guide("e.us", "E!"))
        val r = EpgMatcher.suggest(local("Bravo", "e.us"), idx, now)
        assertEquals(MatchState.EXACT, r.state)
        assertEquals("e.us", r.best!!.guide.xmltvId)
    }

    // 9. Ambiguous needs the user
    @Test
    fun severalFeedsAreAmbiguous() {
        val idx = index(guide("hbo.east", "HBO East"), guide("hbo.west", "HBO West"))
        val r = EpgMatcher.suggest(local("HBO"), idx, now)
        assertEquals(MatchState.AMBIGUOUS, r.state)
        assertEquals("Channel name matches, but multiple regional feeds exist", r.reason)
        assertEquals(2, r.candidates.size)
        assertFalse(EpgMatcher.safeToApply(r, local("HBO"), now) { null })
        // Two same-named channels in one guide: ambiguous too.
        val twice = index(guide("amc.a", "AMC"), guide("amc.b", "AMC"))
        assertEquals(MatchState.AMBIGUOUS, EpgMatcher.suggest(local("AMC"), twice, now).state)
    }

    @Test
    fun aNameThatSaysItsFeedIsNotUnsettledByOtherFeeds() {
        val idx = index(guide("hbo.east", "HBO East"), guide("hbo.west", "HBO West"))
        val r = EpgMatcher.suggest(local("HBO East"), idx, now)
        assertEquals(MatchState.HIGH, r.state)
        assertEquals("hbo.east", r.best!!.guide.xmltvId)
        assertTrue(r.candidates.none { it.guide.xmltvId == "hbo.west" })
    }

    @Test
    fun aSameNameWithOtherFeedsListedIsOnlyPossible() {
        val idx = index(guide("hbo.us", "HBO"), guide("hbo.west", "HBO West"))
        val r = EpgMatcher.suggest(local("HBO"), idx, now)
        assertEquals(MatchState.POSSIBLE, r.state)
        assertFalse(EpgMatcher.safeToApply(r, local("HBO"), now) { null })
    }

    @Test
    fun countriesMustAgreeWhenBothSaySo() {
        val idx = index(guide("skynews.uk", "Sky News UK"))
        assertNotEquals(MatchState.HIGH, EpgMatcher.suggest(local("US: Sky News"), idx, now).state)
        assertEquals(MatchState.HIGH, EpgMatcher.suggest(local("Sky News"), idx, now).state)
    }

    @Test
    fun aGuideChannelWithoutListingsIsNotSafe() {
        val idx = index(guide("amc.us", "AMC", stop = now - 60))
        val r = EpgMatcher.suggest(local("AMC"), idx, now)
        assertEquals(MatchState.POSSIBLE, r.state)
        assertFalse(EpgMatcher.safeToApply(r, local("AMC"), now) { null })
    }

    @Test
    fun aSourcesOrderDecidesBetweenEqualMatches() {
        val idx = index(guide("amc.pub", "AMC", source = "public"), guide("amc.prov", "AMC", source = "prov"))
        val r = EpgMatcher.suggest(local("AMC"), idx, now)
        assertEquals(MatchState.HIGH, r.state)
        assertEquals("prov", r.best!!.guide.sourceKey)
    }

    @Test
    fun aWrongFeedIsSuspected() {
        val idx = index(guide("hbo.west", "HBO West"), guide("espn.us", "ESPN"))
        assertTrue(EpgMatcher.suspicion(local("HBO East"), guide("hbo.west", "HBO West"), idx)!!.contains("Different feed"))
        assertTrue(EpgMatcher.suspicion(local("ESPN2"), guide("espn.us", "ESPN"), idx)!!.contains("number"))
        assertNull(EpgMatcher.suspicion(local("ESPN HD"), guide("espn.us", "ESPN"), idx))
    }

    // 25. Large catalogs stay bounded: indexed lookups, not every channel against every guide channel.
    @Test
    fun thirtyThousandChannelsMatchQuickly() {
        val guides = (0 until 40_000).map { i -> guide("g$i.us", "Network $i ${if (i % 2 == 0) "East" else "West"}") }
        val idx = EpgMatchIndex(guides) { 0 }
        val locals = (0 until 30_000).map { i -> local("Network $i East HD", stream = i) }
        val start = System.nanoTime()
        var high = 0
        for (l in locals) if (EpgMatcher.suggest(l, idx, now).state == MatchState.HIGH) high++
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(15_000, high) // only the even (East) ones have an East guide channel
        assertTrue("took ${ms}ms", ms < 20_000)
        // A very common first word is capped, so a similar-name search never scans everything.
        assertTrue(idx.byFirst.values.all { it.size <= EpgMatchIndex.MAX_PER_FIRST_WORD })
    }

    @Test
    fun namesKeepWhatDecidesTheChannel() {
        assertEquals(EpgNames.parse("ESPN 2 HD").key, EpgNames.parse("ESPN2").key)
        val a = EpgNames.parse("UK: Film4 +1 HD")
        assertEquals("uk", a.country)
        assertEquals("film4 plus1", a.key)
        val b = EpgNames.parse("HBO East FHD")
        assertEquals(setOf("east"), b.feeds)
        assertEquals("hbo", b.baseKey)
        val c = EpgNames.parse("FOX (KTTV) Los Angeles")
        assertEquals(setOf("kttv"), c.callSigns)
        assertEquals(EpgNames.parse("Télé-Québec").key, EpgNames.parse("TELE QUEBEC").key)
    }
}
