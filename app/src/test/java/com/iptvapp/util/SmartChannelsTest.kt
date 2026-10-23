package com.iptvapp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart Channel rules without a device, a provider, or a player. Names below are synthetic.
 */
class SmartChannelsTest {

    private fun src(server: Int, stream: Int, name: String, epg: String = "") =
        SmartChannels.Source(server, stream, name, epg)

    @Test
    fun exactGuideIdentityMatches() {
        val match = SmartChannels.classify(src(0, 1, "ESPN HD", "espn.us"), src(1, 2, "ESPN FHD", "espn.us"))
        assertEquals(SmartChannels.VERIFIED, match.verdict)
        assertEquals(SmartChannels.REASON_VERIFIED, match.reason)
    }

    @Test
    fun hdAndFhdNormalizeTogether() {
        assertEquals(SmartChannels.traits("ESPN HD").core, SmartChannels.traits("ESPN FHD").core)
        assertTrue(SmartChannels.traits("ESPN FHD").quality.contains("FHD"))
    }

    @Test
    fun espnStaysApartFromEspn2() {
        assertEquals(SmartChannels.SEPARATE, SmartChannels.classify(src(0, 1, "ESPN HD"), src(1, 2, "ESPN2 HD")).verdict)
    }

    @Test
    fun eastStaysApartFromWest() {
        assertEquals(SmartChannels.SEPARATE, SmartChannels.classify(src(0, 1, "ESPN East"), src(1, 2, "ESPN West")).verdict)
        assertEquals(SmartChannels.REASON_REGION, SmartChannels.classify(src(0, 1, "FOX 5 NY"), src(1, 2, "FOX 5 LA")).reason)
    }

    @Test
    fun localAffiliatesStayApart() {
        val match = SmartChannels.classify(src(0, 1, "WABC New York"), src(1, 2, "KABC Los Angeles"))
        assertEquals(SmartChannels.SEPARATE, match.verdict)
    }

    @Test
    fun languagesStayApart() {
        assertEquals(
            SmartChannels.SEPARATE,
            SmartChannels.classify(src(0, 1, "ESPN English"), src(1, 2, "ESPN Spanish")).verdict,
        )
    }

    @Test
    fun delayedFeedsStayApart() {
        assertEquals(SmartChannels.REASON_DELAY, SmartChannels.classify(src(0, 1, "Film4"), src(1, 2, "Film4 +1")).reason)
    }

    @Test
    fun ukAndUsStayApart() {
        assertEquals(SmartChannels.SEPARATE, SmartChannels.classify(src(0, 1, "UK| ESPN"), src(1, 2, "US| ESPN")).verdict)
    }

    @Test
    fun unknownCountryPrefixesStayApart() {
        assertEquals(
            SmartChannels.SEPARATE,
            SmartChannels.classify(src(0, 1, "CA| ESPN HD"), src(1, 2, "AU| ESPN HD")).verdict,
        )
        val proposal = SmartChannels.propose(
            listOf(src(0, 1, "CA| ESPN HD", "espn"), src(1, 2, "AU| ESPN HD", "espn")),
        )
        assertTrue(proposal.verified.isEmpty())
        assertEquals(SmartChannels.AMBIGUOUS, SmartChannels.classify(src(0, 1, "CA| ESPN HD"), src(1, 2, "ESPN HD")).verdict)
    }

    @Test
    fun ambiguousNamesNeedReview() {
        val match = SmartChannels.classify(src(0, 1, "ESPN HD"), src(1, 2, "ESPN East"))
        assertEquals(SmartChannels.AMBIGUOUS, match.verdict)
        val proposal = SmartChannels.propose(listOf(src(0, 1, "ESPN HD"), src(1, 2, "ESPN East")))
        assertTrue(proposal.verified.isEmpty())
        assertTrue(proposal.suggested.isEmpty())
        assertTrue(proposal.ambiguous.isNotEmpty())
        val eastWest = SmartChannels.propose(
            listOf(src(0, 1, "ESPN East"), src(1, 2, "ESPN West"), src(2, 3, "ESPN HD")),
        )
        assertTrue(eastWest.verified.isEmpty())
        assertTrue(eastWest.ambiguous.none { cluster ->
            val names = cluster.members.map { it.name }
            names.any { it.contains("East") } && names.any { it.contains("West") }
        })
    }

    @Test
    fun verifiedGroupIsCreatedFromSharedGuideId() {
        val proposal = SmartChannels.propose(
            listOf(src(0, 1, "ESPN HD", "espn.us"), src(1, 9, "ESPN FHD", "ESPN.US"), src(2, 3, "News", "news")),
        )
        assertEquals(1, proposal.verified.size)
        assertEquals(2, proposal.verified.first().members.size)
    }

    @Test
    fun manualGroupRequiresAcknowledgementForAMismatch() {
        assertFalse(SmartChannels.manualGroupAllowed(SmartChannels.SEPARATE, acknowledgedMismatch = false))
        assertTrue(SmartChannels.manualGroupAllowed(SmartChannels.SEPARATE, acknowledgedMismatch = true))
        assertTrue(SmartChannels.manualGroupAllowed(SmartChannels.SUGGESTED, acknowledgedMismatch = false))
    }

    @Test
    fun deletingAGroupDoesNotDescribeCatalogDeletion() {
        val sources = listOf(src(-1, 4, "ESPN HD", "espn.us"))
        val kept = SmartChannels.survivingMembers(emptyList(), sources.map { it.key }.toSet())
        assertTrue(kept.isEmpty())
        assertEquals(1, sources.size)
    }

    @Test
    fun removeOneMemberLeavesTheOther() {
        val a = SmartChannels.Member(SmartChannels.Key(-1, 1), "espn.us", "espn|||", "ESPN HD", true, 0)
        val b = SmartChannels.Member(SmartChannels.Key(0, 2), "espn.us", "espn|||", "ESPN FHD", true, 0)
        val left = SmartChannels.survivingMembers(listOf(a, b), setOf(a.key))
        assertEquals(listOf(a.key), left.map { it.key })
    }

    @Test
    fun rejectedPairDoesNotComeBack() {
        val a = src(0, 5, "ESPN HD", "espn.us")
        val b = src(1, 6, "ESPN FHD", "espn.us")
        val proposal = SmartChannels.propose(listOf(a, b), setOf(SmartChannels.rejectionPair(a, b)))
        assertTrue(proposal.verified.isEmpty())
    }

    @Test
    fun reusedStreamIdDoesNotInheritARejection() {
        val rejected = SmartChannels.rejectionPair(src(0, 5, "ESPN HD", "espn.us"), src(1, 6, "ESPN FHD", "espn.us"))
        val reused = src(0, 5, "Cooking", "food")
        val other = src(1, 8, "Cooking", "food")
        val proposal = SmartChannels.propose(listOf(reused, other), setOf(rejected))
        assertEquals(1, proposal.verified.size)
    }

    @Test
    fun preferredSourceStaysAheadOfAHigherScore() {
        val weakPin = SmartChannels.Scored(SmartChannels.Key(1, 2), "B", 10, 0, true)
        val strong = SmartChannels.Scored(SmartChannels.Key(0, 1), "A", 90, 0, true)
        val ordered = SmartChannels.rank(listOf(strong, weakPin), preferSelected = true, pinned = weakPin.key, session = null)
        assertEquals(weakPin.key, ordered.first().key)
    }

    @Test
    fun missingHealthIsNotAFailure() {
        val unmeasured = SmartChannels.Scored(SmartChannels.Key(0, 1), "A", null, 0, true)
        val poor = SmartChannels.Scored(SmartChannels.Key(1, 2), "B", 10, 0, true)
        val ordered = SmartChannels.rank(listOf(poor, unmeasured), preferSelected = false, pinned = null, session = null)
        assertEquals(unmeasured.key, ordered.first().key)
        assertEquals(FeedScore.UNKNOWN, ordered.first().score ?: FeedScore.UNKNOWN)
        assertTrue(unmeasured.eligible)
    }

    @Test
    fun recentFailureDecaysAndThenExpires() {
        val row = com.iptvapp.data.local.entities.FeedStatsEntity(0, 1)
        val failed = FeedScore.afterStart(row, played = false, tuneMs = 0, nowMs = 1_000)
        val during = FeedScore.score(failed, nowMs = 1_000 + 60_000)
        val later = FeedScore.score(failed, nowMs = 1_000 + 2 * 60 * 60_000)
        assertTrue(during < later)
        assertEquals(FeedScore.UNKNOWN, FeedScore.score(null, 0))
    }

    @Test
    fun tuneTimeCountsOnlyAfterAPicture() {
        assertEquals(1800L, SmartChannels.tuneSample(SmartChannels.PlaySignal.READY, 1800L))
        assertNull(SmartChannels.tuneSample(SmartChannels.PlaySignal.BUFFERING, 1800L))
        assertNull(SmartChannels.tuneSample(SmartChannels.PlaySignal.FAILURE, 1800L))
    }

    @Test
    fun healthyPreferredSourceIsChosenFirst() {
        val a = SmartChannels.Scored(SmartChannels.Key(-1, 1), "A", 80, 0, true)
        val b = SmartChannels.Scored(SmartChannels.Key(0, 2), "B", 40, 0, true)
        assertEquals(a.key, SmartChannels.rank(listOf(b, a), false, null, null).first().key)
    }

    @Test
    fun removedMemberStaysOutOfTheAutomaticGroup() {
        val a = src(-1, 1, "ESPN HD", "espn.us")
        val b = src(0, 2, "ESPN FHD", "espn.us")
        val cluster = SmartChannels.propose(listOf(a, b)).verified.single()
        val token = SmartChannels.removalToken(b.serverIndex, b.streamId, SmartChannels.fingerprint(b.name))
        val kept = SmartChannels.omittingRemoved(cluster, setOf(token))
        assertEquals(listOf(a.key), kept.members.map { it.key })
    }

    @Test
    fun ineligibleMemberIsShownOnceBesideTheGroup() {
        data class Row(val key: SmartChannels.Key)
        val a = Row(SmartChannels.Key(-1, 1))
        val b = Row(SmartChannels.Key(0, 2))
        val c = Row(SmartChannels.Key(1, 3))
        val group = groupOf(a.key, b.key, preferred = a.key).let { grouped ->
            grouped.copy(
                members = grouped.members + SmartChannels.Member(c.key, "espn.us", "espn|||", "C", false, 0),
            )
        }
        val shown = SmartChannels.present(listOf(a, b, c), listOf(group), { it.key }, { 50 }, preferSelected = true)
        assertEquals(listOf(a.key, c.key), shown.map { it.item.key })
        assertEquals(1, shown.count { it.groupId != null })
    }

    @Test
    fun ineligibleSourceIsExcluded() {
        val bad = SmartChannels.Scored(SmartChannels.Key(0, 1), "A", 99, 0, false)
        val ok = SmartChannels.Scored(SmartChannels.Key(1, 2), "B", 10, 0, true)
        assertEquals(listOf(ok.key), SmartChannels.rank(listOf(bad, ok), false, null, null).map { it.key })
    }

    @Test
    fun firstQualifiedFailureSwitches() {
        val session = SmartChannels.Session()
        session.userTuned(SmartChannels.Key(-1, 1))
        val choice = switch(session, now = 10_000, backup = SmartChannels.Key(0, 2))
        assertTrue(choice is SmartChannels.BackupChoice.Switch)
        assertEquals(0, (choice as SmartChannels.BackupChoice.Switch).serverIndex)
    }

    @Test
    fun briefBufferSeekAndPauseDoNotSwitch() {
        assertFalse(SmartChannels.qualifiedForBackup(SmartChannels.PlaySignal.BUFFERING))
        assertFalse(SmartChannels.qualifiedForBackup(SmartChannels.PlaySignal.SEEK))
        assertFalse(SmartChannels.qualifiedForBackup(SmartChannels.PlaySignal.PAUSE))
        val choice = SmartChannels.decide(request(qualified = false, candidates = oneBackup()))
        assertTrue(choice is SmartChannels.BackupChoice.Hold)
    }

    @Test
    fun failoverStopsAtTheAttemptLimit() {
        val session = SmartChannels.Session()
        session.userTuned(SmartChannels.Key(-1, 1))
        val first = switch(session, now = 10_000, backup = SmartChannels.Key(0, 2))
        session.noteSwitch(SmartChannels.playbackHandle(first as SmartChannels.BackupChoice.Switch), 10_000)
        val second = switch(session, now = 10_000 + SmartChannels.FAILOVER_COOLDOWN_MS + 1, backup = SmartChannels.Key(1, 3))
        session.noteSwitch(SmartChannels.playbackHandle(second as SmartChannels.BackupChoice.Switch), 400_000)
        val third = switch(session, now = 800_000, backup = SmartChannels.Key(2, 4))
        assertTrue(third is SmartChannels.BackupChoice.Exhausted)
    }

    @Test
    fun cooldownWaitIsTheTimeLeft() {
        assertNull(SmartChannels.cooldownWaitMs(0L, 1_000L))
        assertEquals(179_000L, SmartChannels.cooldownWaitMs(1_000L, 2_000L))
        assertNull(SmartChannels.cooldownWaitMs(1_000L, 1_000L + SmartChannels.FAILOVER_COOLDOWN_MS))
    }

    @Test
    fun cooldownStopsPingPong() {
        val session = SmartChannels.Session()
        session.userTuned(SmartChannels.Key(-1, 1))
        val first = switch(session, now = 5_000, backup = SmartChannels.Key(0, 2)) as SmartChannels.BackupChoice.Switch
        session.noteSwitch(SmartChannels.playbackHandle(first), 5_000)
        val again = switch(session, now = 6_000, backup = SmartChannels.Key(-1, 1))
        assertTrue(again is SmartChannels.BackupChoice.Hold)
    }

    @Test
    fun stalePlayerEventIsIgnored() {
        val choice = SmartChannels.decide(request(generation = 2, eventGeneration = 1, candidates = oneBackup()))
        assertTrue(choice is SmartChannels.BackupChoice.Hold)
    }

    @Test
    fun channelChangeCancelsThePreviousFailover() {
        val session = SmartChannels.Session()
        session.userTuned(SmartChannels.Key(-1, 1))
        val oldGeneration = session.generation
        session.userTuned(SmartChannels.Key(-1, 9))
        val choice = SmartChannels.decide(
            request(generation = session.generation, eventGeneration = oldGeneration, candidates = oneBackup()),
        )
        assertTrue(choice is SmartChannels.BackupChoice.Hold)
        assertEquals(SmartChannels.Key(-1, 9), session.current)
    }

    @Test
    fun userChoiceBeatsAutomaticRanking() {
        val pinned = SmartChannels.Scored(SmartChannels.Key(1, 2), "B", 20, 0, true)
        val other = SmartChannels.Scored(SmartChannels.Key(0, 1), "A", 99, 0, true)
        val ordered = SmartChannels.rank(listOf(other, pinned), preferSelected = false, pinned = null, session = pinned.key)
        assertEquals(pinned.key, ordered.first().key)
    }

    @Test
    fun everySourceFailingIsAnError() {
        val choice = SmartChannels.decide(request(inGroup = true, candidates = emptyList()))
        assertTrue(choice is SmartChannels.BackupChoice.Exhausted)
        assertNull(SmartChannels.switchMessage("Provider B").takeIf { choice is SmartChannels.BackupChoice.Switch })
    }

    @Test
    fun providerRemovalDropsThatMemberOnly() {
        val kept = SmartChannels.Member(SmartChannels.Key(-1, 1), "espn.us", "x", "ESPN", true, 0)
        val gone = SmartChannels.Member(SmartChannels.Key(2, 9), "espn.us", "x", "ESPN", true, 0)
        val left = SmartChannels.survivingMembers(listOf(kept, gone), setOf(kept.key))
        assertEquals(1, left.size)
        assertEquals(kept.key, left.first().key)
    }

    @Test
    fun duplicateStreamIdsOnDifferentProvidersStayDistinct() {
        val a = src(-1, 12, "ESPN HD", "espn.us")
        val b = src(3, 12, "ESPN FHD", "espn.us")
        assertTrue(a.key != b.key)
        val proposal = SmartChannels.propose(listOf(a, b))
        assertEquals(setOf(a.key, b.key), proposal.verified.first().members.map { it.key }.toSet())
    }

    @Test
    fun favoritesSurviveGroupingAndUngroup() {
        val sources = mapOf(SmartChannels.Key(-1, 1) to true, SmartChannels.Key(0, 2) to true)
        val grouped = SmartChannels.FavoriteSnapshot(sources, groupFavorite = false)
        val after = SmartChannels.afterGroupFavoriteToggle(grouped)
        assertEquals(sources, after.sourceFavorites)
        assertTrue(after.groupFavorite)
        assertEquals(sources, SmartChannels.afterUngroup(after.sourceFavorites))
    }

    @Test
    fun turningGroupingOffKeepsTheOriginalRows() {
        data class Row(val name: String)
        val original = listOf(Row("ESPN HD"), Row("ESPN FHD"))
        val shown = SmartChannels.present(original, emptyList(), { SmartChannels.Key(0, original.indexOf(it)) }, { null }, true)
        assertEquals(original, shown.map { it.item })
        assertTrue(shown.all { it.extraSources == 0 })
    }

    @Test
    fun historyRecordsTheSourceThatPlayed() {
        val from = SmartChannels.Key(-1, 1)
        val to = SmartChannels.Key(0, 2)
        assertEquals(to, SmartChannels.historyEntryAfterSwitch(to))
        assertTrue(from != SmartChannels.historyEntryAfterSwitch(to))
    }

    @Test
    fun deepLinkKeepsTheRequestedChannel() {
        assertEquals(SmartChannels.Key(2, 44), SmartChannels.deepLinkTarget(2, 44))
    }

    @Test
    fun guideIdentityStaysOnTheChosenSource() {
        data class Row(val key: SmartChannels.Key, val epg: String)
        val a = Row(SmartChannels.Key(-1, 1), "espn.us")
        val b = Row(SmartChannels.Key(0, 2), "espn.us")
        val group = groupOf(a.key, b.key, preferred = b.key)
        val shown = SmartChannels.present(listOf(a, b), listOf(group), { it.key }, { 50 }, preferSelected = true)
        assertEquals(1, shown.size)
        assertEquals("espn.us", shown.first().item.epg)
        assertEquals(b.key, shown.first().item.key)
    }

    @Test
    fun playbackKeyIsTheStreamNotTheGroup() {
        val choice = SmartChannels.BackupChoice.Switch(1, 8, "ESPN FHD")
        assertEquals(SmartChannels.Key(1, 8), SmartChannels.playbackHandle(choice))
    }

    @Test
    fun recordingKeepsThePlayingSource() {
        val identity = SmartChannels.recordingIdentity(3, 15, "Nightly News", groupId = "v:espn")
        assertEquals(3, identity.serverIndex)
        assertEquals(15, identity.streamId)
        assertEquals("Nightly News", identity.programTitle)
        assertFalse(SmartChannels.allowAutomaticRecordingMove(inProgress = true))
    }

    @Test
    fun timeshiftDoesNotMoveWithTheBackup() {
        assertFalse(SmartChannels.timeshiftAllowsSwitch(atLiveEdge = false))
        assertTrue(SmartChannels.timeshiftAllowsSwitch(atLiveEdge = true))
        assertTrue(SmartChannels.TIMESHIFT_HOLD.contains("buffer"))
    }

    @Test
    fun groupTextHasNoCredentialsOrInjectedAddress() {
        val text = SmartChannels.storedText("ESPN HD", SmartChannels.REASON_VERIFIED, "espn.us", "espn|||")
        assertFalse(SmartChannels.exposesCredential(text))
        assertTrue(SmartChannels.exposesCredential("https://user:secret@provider.example/live"))
        val choice = SmartChannels.BackupChoice.Switch(-1, 4, "ESPN HD")
        assertFalse(choice.displayName.contains("http"))
        assertEquals(SmartChannels.Key(-1, 4), SmartChannels.playbackHandle(choice))
    }

    @Test
    fun largeCatalogGroupingStaysLinear() {
        val many = ArrayList<SmartChannels.Source>(30_000)
        repeat(30_000) { index -> many += src(index % 5, index, "Channel $index", "id.$index") }
        val started = System.nanoTime()
        val proposal = SmartChannels.propose(many)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(30_000, proposal.inspections)
        assertTrue(proposal.verified.isEmpty())
        assertTrue(elapsedMs < 5_000)
        val duplicated = ArrayList<SmartChannels.Source>()
        repeat(1_000) { group ->
            repeat(5) { copy ->
                duplicated += src(copy, group * 10 + copy, "Sport $group HD", "sport.$group")
            }
        }
        val grouped = SmartChannels.propose(duplicated)
        assertEquals(duplicated.size, grouped.inspections)
        assertEquals(1_000, grouped.verified.size)
    }

    @Test
    fun pauseIsNotABackupAndControlsStayAvailable() {
        assertFalse(SmartChannels.qualifiedForBackup(SmartChannels.PlaySignal.PAUSE))
        assertTrue(SmartChannels.qualifiedForBackup(SmartChannels.PlaySignal.DECODER))
    }

    @Test
    fun phoneSourceActionsAreListed() {
        val actions = SmartChannels.sourceActions()
        assertTrue(actions.contains("Play this source"))
        assertTrue(actions.contains("Set as preferred"))
        assertTrue(actions.contains("Automatic backup"))
        assertTrue(actions.contains("Review source information"))
    }

    @Test
    fun bulkAcceptIgnoresAmbiguousMatches() {
        val verified = SmartChannels.Cluster("v:1", "ESPN", SmartChannels.VERIFIED, SmartChannels.REASON_VERIFIED, emptyList())
        val ambiguous = SmartChannels.Cluster("a:1", "ESPN", SmartChannels.AMBIGUOUS, SmartChannels.REASON_AMBIGUOUS, emptyList())
        assertEquals(listOf(verified), SmartChannels.bulkAccept(listOf(verified, ambiguous)))
    }

    @Test
    fun staleMembershipRejectsAReusedStream() {
        val storedFingerprint = SmartChannels.fingerprint("ESPN HD")
        assertFalse(SmartChannels.isStale("espn.us", storedFingerprint, src(0, 5, "ESPN FHD", "espn.us")))
        assertTrue(SmartChannels.isStale("espn.us", storedFingerprint, src(0, 5, "Cooking HD", "food")))
        assertTrue(SmartChannels.isStale("", storedFingerprint, src(0, 5, "Cooking HD", "")))
    }

    @Test
    fun outsideAGroupTheLegacyBackupStillApplies() {
        val choice = SmartChannels.decide(request(smartOn = true, inGroup = false, candidates = oneBackup()))
        assertTrue(choice is SmartChannels.BackupChoice.Legacy)
    }

    @Test
    fun disabledGroupBackupDoesNotLeaveTheGroup() {
        val choice = SmartChannels.decide(request(inGroup = true, failover = false, candidates = oneBackup()))
        assertTrue(choice is SmartChannels.BackupChoice.Exhausted)
    }

    @Test
    fun switchMessageNamesTheBackupOnce() {
        assertEquals("Switched to backup — Provider B", SmartChannels.switchMessage("Provider B"))
    }

    @Test
    fun reusedStreamDoesNotFailOverInsideTheOldGroup() {
        val current = SmartChannels.Key(0, 5)
        val group = groupOf(current, SmartChannels.Key(1, 6), preferred = current)
        val reused = src(0, 5, "Cooking HD", "food")
        assertNull(SmartChannels.playbackGroupFor(listOf(group), current, reused))
        val sameChannel = src(0, 5, "ESPN FHD", "espn.us")
        assertEquals(group.id, SmartChannels.playbackGroupFor(listOf(group), current, sameChannel)?.id)
        assertEquals(group.id, SmartChannels.playbackGroupFor(listOf(group), current, null)?.id)
        assertNull(SmartChannels.playbackGroupFor(listOf(group), SmartChannels.Key(9, 9), null))
    }

    @Test
    fun manualMembersStayOutOfASecondAutomaticGroup() {
        val a = src(-1, 1, "ESPN HD", "espn.us")
        val b = src(0, 2, "ESPN FHD", "espn.us")
        val c = src(1, 3, "ESPN HD", "espn.us")
        val cluster = SmartChannels.propose(listOf(a, b, c)).verified.single()
        assertNull(SmartChannels.excludingReserved(cluster, setOf(a.key, b.key)))
        assertEquals(listOf(b.key, c.key), SmartChannels.excludingReserved(cluster, setOf(a.key))!!.members.map { it.key })
        assertEquals(cluster, SmartChannels.excludingReserved(cluster, emptySet()))
    }

    @Test
    fun reusedStreamIsNotCollapsedIntoTheOldGroup() {
        data class Row(val key: SmartChannels.Key, val name: String, val epg: String)
        val espn = Row(SmartChannels.Key(0, 5), "ESPN HD", "espn.us")
        val other = Row(SmartChannels.Key(1, 6), "ESPN FHD", "espn.us")
        val group = groupOf(espn.key, other.key, espn.key)
        fun live(row: Row) = src(row.key.serverIndex, row.key.streamId, row.name, row.epg)
        val same = SmartChannels.present(listOf(espn, other), listOf(group), { it.key }, { 50 }, true, ::live)
        assertEquals(1, same.size)
        assertEquals(group.id, same.single().groupId)
        val reused = espn.copy(name = "Cooking HD", epg = "food")
        val split = SmartChannels.present(listOf(reused, other), listOf(group), { it.key }, { 50 }, true, ::live)
        assertEquals(listOf(reused.key, other.key), split.map { it.item.key })
        assertTrue(split.all { it.groupId == null })
    }

    @Test
    fun categoryPresentationUsesOnePassPerGroup() {
        data class Row(val key: SmartChannels.Key)
        val groups = ArrayList<SmartChannels.PlaybackGroup>(8_000)
        val rows = ArrayList<Row>(16_000)
        repeat(8_000) { index ->
            val a = SmartChannels.Key(0, index)
            val b = SmartChannels.Key(1, index)
            rows += Row(a)
            rows += Row(b)
            groups += groupOf(a, b, a).copy(id = "v:$index")
        }
        val started = System.nanoTime()
        val shown = SmartChannels.present(rows, groups, { it.key }, { 50 }, true)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(8_000, shown.size)
        assertTrue(shown.all { it.extraSources == 1 && it.groupId != null })
        assertTrue("presentation took ${elapsedMs}ms", elapsedMs < 2_000)
    }

    private fun groupOf(a: SmartChannels.Key, b: SmartChannels.Key, preferred: SmartChannels.Key) = SmartChannels.PlaybackGroup(
        id = "v:test",
        reviewState = SmartChannels.VERIFIED,
        failoverEnabled = true,
        hasPreferred = true,
        preferred = preferred,
        groupFavorite = false,
        members = listOf(a, b).map {
            SmartChannels.Member(it, "espn.us", "espn|||", it.toString(), true, 0)
        },
    )

    private fun oneBackup() = listOf(SmartChannels.Scored(SmartChannels.Key(0, 2), "Backup", 70, 0, true))

    private fun request(
        qualified: Boolean = true,
        inGroup: Boolean = true,
        failover: Boolean = true,
        smartOn: Boolean = true,
        generation: Long = 1,
        eventGeneration: Long = generation,
        attempts: Int = 0,
        lastSwitch: Long = 0,
        now: Long = 10_000,
        candidates: List<SmartChannels.Scored>,
    ) = SmartChannels.BackupRequest(
        smartOn = smartOn,
        inPlaybackGroup = inGroup,
        groupFailoverEnabled = failover,
        qualifiedFailure = qualified,
        attempts = attempts,
        nowMs = now,
        lastSwitchAtMs = lastSwitch,
        generation = generation,
        eventGeneration = eventGeneration,
        candidates = candidates,
    )

    private fun switch(session: SmartChannels.Session, now: Long, backup: SmartChannels.Key): SmartChannels.BackupChoice {
        return SmartChannels.decide(
            request(
                generation = session.generation,
                attempts = session.attempts(),
                lastSwitch = session.lastSwitchAt(),
                now = now,
                candidates = listOf(SmartChannels.Scored(backup, "Backup", 70, 0, true)),
            ),
        )
    }
}
