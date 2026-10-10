package com.iptvapp.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one rule every trigger and recovery path follows. Plain JVM, epoch milliseconds. */
class RecordingPlanTest {

    private val now = 1_760_000_000_000L
    private val hour = 3_600_000L

    @Test fun aFutureRecordingWaitsForItsStart() {
        assertEquals(RecordingPlan.Alarm(now + hour), RecordingPlan.decide("SCHEDULED", now + hour, hour, now))
    }

    @Test fun oneThatShouldHaveStartedTwoMinutesAgoRecordsWhatIsLeft() {
        val start = now - 2 * 60_000L
        assertEquals(
            RecordingPlan.StartNow(remainingMs = hour - 2 * 60_000L, resume = false),
            RecordingPlan.decide("SCHEDULED", start, hour, now)
        )
    }

    @Test fun oneThatEndedWhileTheDeviceWasOffIsMissed() {
        assertEquals(RecordingPlan.Missed, RecordingPlan.decide("SCHEDULED", now - 2 * hour, hour, now))
    }

    @Test fun lessThanAMinuteLeftIsNotWorthStarting() {
        val start = now - hour + 30_000L
        assertEquals(RecordingPlan.Missed, RecordingPlan.decide("SCHEDULED", start, hour, now))
    }

    @Test fun anInterruptedCaptureResumesForTheRestOfItsWindow() {
        val start = now - 10 * 60_000L
        assertEquals(
            RecordingPlan.StartNow(remainingMs = hour - 10 * 60_000L, resume = true),
            RecordingPlan.decide("RECORDING", start, hour, now)
        )
    }

    @Test fun anInterruptedCaptureWhoseWindowEndedIsLeftToTheExistingCleanUp() {
        assertEquals(RecordingPlan.Leave, RecordingPlan.decide("RECORDING", now - 2 * hour, hour, now))
    }

    @Test fun finishedFailedAndCompressingRecordingsAreLeftAlone() {
        for (status in listOf("DONE", "FAILED", "COMPRESSING")) {
            assertEquals(status, RecordingPlan.Leave, RecordingPlan.decide(status, now + hour, hour, now))
        }
    }
}
