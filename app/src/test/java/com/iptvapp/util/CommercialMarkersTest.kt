package com.iptvapp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision rules for commercial markers. These do not decode a recording.
 * The silence fixture is a known pattern, not a measured accuracy on a real show.
 */
class CommercialMarkersTest {
    private val hour = 60 * 60 * 1000L
    private val stamp = CommercialMarkers.FileStamp(1_000L, 50L)
    private val otherFile = CommercialMarkers.FileStamp(2_000L, 80L)

    private fun manual(start: Long, end: Long, file: CommercialMarkers.FileStamp = stamp) =
        CommercialMarkers.manual(start, end, hour, file, 1L)!!

    private fun suggested(start: Long, end: Long, file: CommercialMarkers.FileStamp = stamp) =
        CommercialMarkers.Segment(
            start, end, CommercialMarkers.Origin.AUTOMATIC, CommercialMarkers.Review.SUGGESTED,
            "silence_rhythm", file.size, file.modifiedMs
        )

    private fun tickAt(
        position: Long,
        segments: List<CommercialMarkers.Segment>,
        mode: CommercialMarkers.SkipMode,
        session: CommercialMarkers.Session = CommercialMarkers.Session(),
        playing: Boolean = true,
        scrubbing: Boolean = false,
        seekable: Boolean = true,
        duration: Long = hour
    ) = CommercialMarkers.tick(position, duration, segments, mode, playing, scrubbing, seekable, session)

    @Test
    fun aManualIntervalIsConfirmedAndKept() {
        val made = manual(12 * 60_000L, 15 * 60_000L)
        assertEquals(CommercialMarkers.Review.CONFIRMED, made.review)
        assertEquals(CommercialMarkers.Origin.MANUAL, made.origin)
        assertEquals("12:00–15:00 · Confirmed", CommercialMarkers.label(made))
    }

    @Test
    fun aNegativeTimeIsRejected() {
        assertNull(CommercialMarkers.checked(-1L, 10_000L, hour))
        assertNull(CommercialMarkers.manual(-1L, 10_000L, hour, stamp, 1L))
    }

    @Test
    fun anEndBeforeTheStartIsRejected() {
        assertNull(CommercialMarkers.checked(20_000L, 10_000L, hour))
        assertNull(CommercialMarkers.checked(10_000L, 10_000L, hour))
    }

    @Test
    fun anEndPastTheDurationIsClamped() {
        val span = CommercialMarkers.checked(hour - 5_000L, hour + 30_000L, hour)
        assertEquals(hour - 5_000L, span!!.first)
        assertEquals(hour, span.second)
    }

    @Test
    fun overlappingSuggestionsFollowTheShorterIntervalRule() {
        val kept = manual(100_000L, 200_000L)
        val close = suggested(120_000L, 180_000L)
        val apart = suggested(400_000L, 500_000L)
        assertTrue(CommercialMarkers.sameBreak(kept, close))
        assertFalse(CommercialMarkers.sameBreak(kept, apart))
        val merged = CommercialMarkers.applyAnalysis(listOf(kept), listOf(close, apart), stamp)
        assertEquals(listOf(kept, apart), merged)
    }

    @Test
    fun aDuplicateRangeIsStoredOnce() {
        val one = suggested(10_000L, 80_000L)
        val merged = CommercialMarkers.applyAnalysis(emptyList(), listOf(one, one.copy()), stamp)
        assertEquals(1, merged.size)
    }

    @Test
    fun analysisDoesNotReplaceAConfirmedOrRejectedMarker() {
        val confirmed = manual(100_000L, 200_000L)
        val rejected = CommercialMarkers.reject(suggested(300_000L, 400_000L), 2L)
        val again = suggested(100_000L, 200_000L)
        val rejectedAgain = suggested(300_000L, 400_000L)
        val fresh = suggested(500_000L, 580_000L)
        val merged = CommercialMarkers.applyAnalysis(listOf(confirmed, rejected), listOf(again, rejectedAgain, fresh), stamp)
        assertTrue(merged.contains(confirmed))
        assertTrue(merged.any { it.review == CommercialMarkers.Review.REJECTED && it.startMs == 300_000L })
        assertTrue(merged.any { it.startMs == 500_000L && it.review == CommercialMarkers.Review.SUGGESTED })
        assertFalse(merged.any { it.startMs == 100_000L && it.review == CommercialMarkers.Review.SUGGESTED })
        assertFalse(merged.any { it.startMs == 300_000L && it.review == CommercialMarkers.Review.SUGGESTED })
    }

    @Test
    fun aDecisionFromThePreviousFileDoesNotHideANewSuggestion() {
        val previous = manual(100_000L, 200_000L, otherFile)
        val rejected = CommercialMarkers.reject(suggested(400_000L, 480_000L, otherFile), 2L)
        val retimed = suggested(300_000L, 380_000L, otherFile).copy(origin = CommercialMarkers.Origin.MANUAL)
        val again = suggested(100_000L, 200_000L)
        val rejectedAgain = suggested(400_000L, 480_000L)
        val sameRetimedRange = suggested(300_000L, 380_000L)
        val merged = CommercialMarkers.applyAnalysis(
            listOf(previous, rejected, retimed),
            listOf(again, rejectedAgain, sameRetimedRange),
            stamp
        )
        assertTrue(merged.contains(previous))
        assertTrue(merged.contains(rejected))
        assertTrue(merged.contains(retimed))
        assertTrue(merged.any { it.fileSize == stamp.size && it.startMs == 100_000L && it.review == CommercialMarkers.Review.SUGGESTED })
        assertTrue(merged.any { it.fileSize == stamp.size && it.startMs == 400_000L && it.review == CommercialMarkers.Review.SUGGESTED })
        assertTrue(merged.any { it.fileSize == stamp.size && it.startMs == 300_000L && it.origin == CommercialMarkers.Origin.AUTOMATIC })
    }

    @Test
    fun aRetimedSuggestionSurvivesTheNextAnalysis() {
        val edited = suggested(12_000L, 90_000L).copy(origin = CommercialMarkers.Origin.MANUAL)
        val again = suggested(10_000L, 80_000L)
        val fresh = suggested(200_000L, 280_000L)
        val merged = CommercialMarkers.applyAnalysis(listOf(edited), listOf(again, fresh), stamp)
        assertTrue(merged.contains(edited))
        assertTrue(merged.any { it.startMs == 200_000L && it.origin == CommercialMarkers.Origin.AUTOMATIC })
        assertFalse(merged.any { it.origin == CommercialMarkers.Origin.AUTOMATIC && it.startMs == 10_000L })
        assertEquals(listOf(edited), CommercialMarkers.dropSuggestions(merged))
    }

    @Test
    fun clearingSuggestionsKeepsTheUserDecision() {
        val confirmed = manual(1_000L, 20_000L)
        val rejected = CommercialMarkers.reject(suggested(30_000L, 90_000L), 2L)
        val kept = CommercialMarkers.dropSuggestions(listOf(confirmed, suggested(100_000L, 180_000L), rejected))
        assertEquals(listOf(confirmed, rejected), kept)
    }

    @Test
    fun aDifferentFileDoesNotUseTheseMarkers() {
        val row = manual(10_000L, 40_000L)
        assertTrue(CommercialMarkers.forFile(listOf(row), otherFile).isEmpty())
        assertEquals(listOf(row), CommercialMarkers.forFile(listOf(row), stamp))
    }

    @Test
    fun anUnknownFileShowsNothing() {
        val row = manual(10_000L, 40_000L, CommercialMarkers.FileStamp(0L, 0L))
        assertTrue(CommercialMarkers.forFile(listOf(row), CommercialMarkers.FileStamp(0L, 0L)).isEmpty())
    }

    @Test
    fun twoRecordingIdsDoNotShareAWorkNameOrAMarkerList() {
        assertEquals("ad-breaks-4", CommercialMarkers.analysisWorkName(4))
        assertTrue(CommercialMarkers.analysisWorkName(4) != CommercialMarkers.analysisWorkName(9))
        val library = mapOf(4 to listOf(manual(1_000L, 20_000L)), 9 to emptyList<CommercialMarkers.Segment>())
        assertTrue(library.getValue(9).isEmpty())
        assertEquals(1, (library - 4).size)
    }

    @Test
    fun outsideAConfirmedIntervalThereIsNoSkip() {
        val (action, _) = tickAt(1_000L, listOf(manual(60_000L, 120_000L)), CommercialMarkers.SkipMode.BUTTON)
        assertTrue(action is CommercialMarkers.Action.None)
        assertNull(CommercialMarkers.buttonTarget(1_000L, listOf(manual(60_000L, 120_000L))))
    }

    @Test
    fun insideAConfirmedIntervalTheSkipButtonIsOffered() {
        val segment = manual(60_000L, 180_000L)
        val (action, _) = tickAt(70_000L, listOf(segment), CommercialMarkers.SkipMode.BUTTON)
        val shown = action as CommercialMarkers.Action.ShowSkip
        assertEquals(segment, shown.segment)
        assertEquals("1:50", CommercialMarkers.remainingLabel(shown.remainingMs))
        assertEquals(180_000L, CommercialMarkers.skipTarget(segment, hour))
    }

    @Test
    fun aSuggestionIsNotSkippedAutomatically() {
        val (action, _) = tickAt(70_000L, listOf(suggested(60_000L, 180_000L)), CommercialMarkers.SkipMode.AUTOMATIC)
        assertTrue(action is CommercialMarkers.Action.None)
    }

    @Test
    fun automaticSkippingIsNotTheDefault() {
        assertEquals(CommercialMarkers.DetectionMode.ON_DEMAND, CommercialMarkers.detectionModeOf(null))
        assertEquals(CommercialMarkers.SkipMode.BUTTON, CommercialMarkers.skipModeOf(null))
        assertTrue(CommercialMarkers.skipModeOf(null) != CommercialMarkers.SkipMode.AUTOMATIC)
    }

    @Test
    fun automaticSkipHappensOnceThenUndoHoldsThroughTheSeekBack() {
        val segment = manual(60_000L, 180_000L)
        val (first, afterSkip) = tickAt(70_000L, listOf(segment), CommercialMarkers.SkipMode.AUTOMATIC)
        val skip = first as CommercialMarkers.Action.AutoSkip
        assertEquals(180_000L, skip.toMs)
        val (second, _) = tickAt(70_000L, listOf(segment), CommercialMarkers.SkipMode.AUTOMATIC, afterSkip)
        assertFalse(second is CommercialMarkers.Action.AutoSkip)
        val undone = CommercialMarkers.undo(afterSkip)!!
        assertEquals(70_000L, undone.first)
        val (whileSeeking, held) = tickAt(180_000L, listOf(segment), CommercialMarkers.SkipMode.AUTOMATIC, undone.second)
        assertFalse(whileSeeking is CommercialMarkers.Action.AutoSkip)
        val (backInside, stillHeld) = tickAt(70_000L, listOf(segment), CommercialMarkers.SkipMode.AUTOMATIC, held)
        assertFalse(backInside is CommercialMarkers.Action.AutoSkip)
        assertEquals(segment.key(), stillHeld.undoHold)
        val (left, released) = tickAt(200_000L, listOf(segment), CommercialMarkers.SkipMode.AUTOMATIC, stillHeld)
        assertTrue(left is CommercialMarkers.Action.None)
        assertNull(released.undoHold)
    }

    @Test
    fun scrubbingDoesNotSkipOrForgetAHandledInterval() {
        val segment = manual(60_000L, 180_000L)
        val (_, handled) = tickAt(70_000L, listOf(segment), CommercialMarkers.SkipMode.AUTOMATIC)
        val (whileScrubbing, same) = tickAt(
            80_000L, listOf(segment), CommercialMarkers.SkipMode.AUTOMATIC, handled, scrubbing = true
        )
        assertTrue(whileScrubbing is CommercialMarkers.Action.ShowSkip)
        assertEquals(handled.handled, same.handled)
    }

    @Test
    fun pausedPlaybackDoesNotSkipItself() {
        val segment = manual(60_000L, 180_000L)
        val (action, _) = tickAt(70_000L, listOf(segment), CommercialMarkers.SkipMode.AUTOMATIC, playing = false)
        assertTrue(action is CommercialMarkers.Action.ShowSkip)
    }

    @Test
    fun anUnseekableTimelineDoesNotSkip() {
        val segment = manual(60_000L, 180_000L)
        val (unseekable, _) = tickAt(70_000L, listOf(segment), CommercialMarkers.SkipMode.AUTOMATIC, seekable = false)
        assertTrue(unseekable is CommercialMarkers.Action.None)
        val (unknown, _) = tickAt(70_000L, listOf(segment), CommercialMarkers.SkipMode.AUTOMATIC, duration = 0L)
        assertTrue(unknown is CommercialMarkers.Action.None)
    }

    @Test
    fun repeatedPositionUpdatesDoNotSkipAgain() {
        val segment = manual(60_000L, 180_000L)
        var session = CommercialMarkers.Session()
        repeat(30) { step ->
            val (action, next) = tickAt(70_000L + step, listOf(segment), CommercialMarkers.SkipMode.AUTOMATIC, session)
            session = next
            if (step == 0) assertTrue(action is CommercialMarkers.Action.AutoSkip)
            else assertFalse(action is CommercialMarkers.Action.AutoSkip)
        }
    }

    @Test
    fun aBackwardPositionIsStillANormalTick() {
        val segment = manual(60_000L, 180_000L)
        val (_, after) = tickAt(70_000L, listOf(segment), CommercialMarkers.SkipMode.BUTTON)
        val (back, _) = tickAt(5_000L, listOf(segment), CommercialMarkers.SkipMode.BUTTON, after)
        assertTrue(back is CommercialMarkers.Action.None)
    }

    @Test
    fun theLastFewSecondsOfABreakDoNotShowTheButton() {
        val segment = manual(60_000L, 180_000L)
        assertNull(CommercialMarkers.buttonTarget(178_000L, listOf(segment)))
        assertEquals(segment, CommercialMarkers.buttonTarget(176_000L, listOf(segment)))
    }

    @Test
    fun markerFractionsMatchThePlayerTimeline() {
        val duration = 60 * 60_000L
        assertEquals(0f, CommercialMarkers.fraction(0L, duration))
        assertEquals(0.25f, CommercialMarkers.fraction(15 * 60_000L, duration))
        assertEquals(1f, CommercialMarkers.fraction(duration + 5_000L, duration))
        assertEquals(0f, CommercialMarkers.fraction(10_000L, 0L))
    }

    @Test
    fun silenceOnAnAdRhythmIsOnlyASuggestion() {
        val start = 12 * 60_000L
        val silences = listOf(start, start + 30_000L, start + 60_000L)
        val found = CommercialMarkers.suggestionsFromSilences(silences, stamp, 1L)
        assertEquals(1, found.size)
        assertEquals(start, found[0].startMs)
        assertEquals(start + 60_000L, found[0].endMs)
        assertEquals(CommercialMarkers.Review.SUGGESTED, found[0].review)
        assertEquals("silence_rhythm", found[0].evidence)
    }

    @Test
    fun quietMomentsOffThatRhythmAreNotCommercials() {
        assertTrue(CommercialMarkers.breaksFromSilences(listOf(1_000L, 6_000L, 9_000L)).isEmpty())
        assertTrue(CommercialMarkers.suggestionsFromSilences(emptyList(), stamp, 1L).isEmpty())
    }

    @Test
    fun aGapInTheAudioIsNotSilence() {
        val levels = FloatArray(20) { 1_000f }
        levels[5] = Float.NaN
        levels[6] = Float.NaN
        levels[7] = 10f
        levels[8] = 10f
        val marks = CommercialMarkers.silenceMarks(levels, 100L)
        assertTrue(marks.none { it == 550L || it == 650L })
    }

    @Test
    fun aBreakLongerThanEightMinutesIsNotInventedAsOneInterval() {
        val silences = (0..20).map { it * 30_000L }
        assertTrue(CommercialMarkers.breaksFromSilences(silences).isEmpty())
    }

    @Test
    fun theLibraryLinePrefersASuggestionOverAFailedNote() {
        assertEquals(
            "Review Suggested Markers",
            CommercialMarkers.libraryStatus(true, analyzed = false, analyzing = false, failed = true, unsupported = false, listOf(suggested(1_000L, 20_000L)))
        )
        assertEquals("Not Analyzed", CommercialMarkers.libraryStatus(true, false, false, false, false, emptyList()))
        assertEquals("", CommercialMarkers.libraryStatus(false, true, false, false, false, emptyList()))
        assertEquals("No Commercials Detected", CommercialMarkers.libraryStatus(true, true, false, false, false, emptyList()))
    }

    @Test
    fun anEditorSaveKeepsSuggestionsThatArrivedWhileItWasOpen() {
        val shown = listOf(suggested(10_000L, 80_000L))
        val confirmed = shown[0].copy(review = CommercialMarkers.Review.CONFIRMED)
        val arrived = suggested(200_000L, 280_000L)
        val merged = CommercialMarkers.applyEdit(listOf(arrived), shown, listOf(confirmed))
        assertEquals(listOf(confirmed, arrived), merged)
    }

    @Test
    fun reconfirmingAMarkerStoresItsNewFileStamp() {
        val old = manual(10_000L, 80_000L, CommercialMarkers.FileStamp(10L, 10L))
        val renewed = old.copy(fileSize = 20L, fileModifiedMs = 20L, updatedAtMs = 5L)
        val arrived = suggested(200_000L, 280_000L)
        val merged = CommercialMarkers.applyEdit(listOf(old, arrived), listOf(old), listOf(renewed))
        assertEquals(listOf(renewed, arrived), merged)
        val untouched = old.copy(fileSize = 99L, fileModifiedMs = 99L)
        val kept = CommercialMarkers.applyEdit(listOf(untouched, arrived), listOf(old), listOf(old))
        assertEquals(listOf(untouched, arrived), kept)
    }

    @Test
    fun stampingLegacyMarkersLeavesRowsThatAlreadyNameAFile() {
        val legacy = suggested(10_000L, 80_000L).copy(fileSize = 0L, fileModifiedMs = 0L)
        val fresh = suggested(200_000L, 280_000L)
        val stamped = CommercialMarkers.stampMissingFiles(listOf(legacy, fresh), stamp)
        assertEquals(stamp.size, stamped[0].fileSize)
        assertEquals(stamp.modifiedMs, stamped[0].fileModifiedMs)
        assertEquals(fresh, stamped[1])
        assertEquals(listOf(legacy, fresh), CommercialMarkers.stampMissingFiles(listOf(legacy, fresh), CommercialMarkers.FileStamp(0L, 0L)))
    }

    @Test
    fun aMarkerReadDropsARevisionThatChangedWhileTheRowsWereLoading() {
        assertEquals("rev-1", CommercialMarkers.cacheKeyIfUnchanged("rev-1", "rev-1"))
        assertEquals("", CommercialMarkers.cacheKeyIfUnchanged("", ""))
        assertNull(CommercialMarkers.cacheKeyIfUnchanged("rev-1", "rev-2"))
    }

    @Test
    fun aFinishedAnalysisWithNoBreaksIsNotLeftUnanalyzed() {
        assertEquals("NONE", CommercialMarkers.statusAfterAnalysis(emptyList()))
        assertEquals("REVIEW", CommercialMarkers.statusAfterAnalysis(listOf(suggested(10_000L, 80_000L))))
        assertEquals("FOUND", CommercialMarkers.statusAfterAnalysis(listOf(manual(10_000L, 80_000L))))
        assertEquals(
            "REVIEW",
            CommercialMarkers.statusAfterAnalysis(listOf(manual(10_000L, 80_000L), suggested(200_000L, 280_000L)))
        )
    }

    @Test
    fun removingTheOnlyManualMarkerDoesNotClaimTheRecordingWasAnalyzed() {
        val made = manual(10_000L, 80_000L)
        assertEquals("FOUND", CommercialMarkers.statusAfterMarkerEdit(listOf(made), analyzed = false, previousStatus = null))
        assertEquals("", CommercialMarkers.statusAfterMarkerEdit(emptyList(), analyzed = false, previousStatus = "FOUND"))
        assertEquals("NONE", CommercialMarkers.statusAfterMarkerEdit(emptyList(), analyzed = true, previousStatus = "FOUND"))
        assertEquals("FAILED", CommercialMarkers.statusAfterMarkerEdit(emptyList(), analyzed = false, previousStatus = "FAILED"))
        assertEquals("UNSUPPORTED", CommercialMarkers.statusAfterMarkerEdit(emptyList(), analyzed = false, previousStatus = "UNSUPPORTED"))
    }

    @Test
    fun aStoppedAnalysisDoesNotStayOnAnalyzing() {
        val confirmed = listOf(manual(10_000L, 80_000L))
        assertEquals("NONE", CommercialMarkers.statusAfterStoppedAnalysis("NONE", "ANALYZING", emptyList()))
        assertEquals("FOUND", CommercialMarkers.statusAfterStoppedAnalysis("ANALYZING", "ANALYZING", confirmed))
        assertEquals("REVIEW", CommercialMarkers.statusAfterStoppedAnalysis(null, "ANALYZING", listOf(suggested(10_000L, 80_000L))))
        assertNull(CommercialMarkers.statusAfterStoppedAnalysis("ANALYZING", "ANALYZING", emptyList()))
        assertNull(CommercialMarkers.statusAfterStoppedAnalysis(null, "ANALYZING", emptyList()))
    }
}
