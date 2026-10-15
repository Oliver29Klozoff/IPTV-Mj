package com.iptvapp.ui.player.timeshift

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timeshift decisions, the bounded on-disk window, and one real local HTTP redirect.
 * No sleeps. A latch wait is bounded by its timeout.
 */
class TimeshiftTest {

    @Test
    fun livePlaybackStartsInTheLiveState() {
        val live = TimeshiftNavigator.freshLive(60_000L)
        assertEquals(TimeshiftPlaybackState.LIVE, TimeshiftNavigator.state(live))
        assertTrue(TimeshiftNavigator.atLiveEdge(live))
        assertEquals(0L, TimeshiftNavigator.behindMs(live))
    }

    @Test
    fun pauseChangesToPaused() {
        val paused = reading(position = 40_000L, edge = 60_000L, playing = false)
        assertEquals(TimeshiftPlaybackState.PAUSED, TimeshiftNavigator.state(paused))
    }

    @Test
    fun resumeContinuesFromThePausedPosition() {
        val pausedAt = 40_000L
        val edge = 60_000L
        assertEquals(pausedAt, TimeshiftNavigator.resumePosition(pausedAt))
        assertEquals(edge, TimeshiftNavigator.goLive(reading(position = pausedAt, edge = edge, playing = false)))
        assertTrue(TimeshiftNavigator.resumePosition(pausedAt) < edge)
    }

    @Test
    fun rewind10SecondsClampsToTheEarliestPoint() {
        val nearStart = reading(position = 12_000L, edge = 60_000L, earliest = 5_000L)
        assertEquals(5_000L, TimeshiftNavigator.rewind(nearStart, TimeshiftNavigator.REPLAY_10_MS))
        val room = reading(position = 50_000L, edge = 80_000L)
        assertEquals(40_000L, TimeshiftNavigator.rewind(room, TimeshiftNavigator.REPLAY_10_MS))
    }

    @Test
    fun rewind30SecondsClampsToTheEarliestPoint() {
        val nearStart = reading(position = 20_000L, edge = 60_000L)
        assertEquals(0L, TimeshiftNavigator.rewind(nearStart, TimeshiftNavigator.REPLAY_30_MS))
        val room = reading(position = 50_000L, edge = 80_000L)
        assertEquals(20_000L, TimeshiftNavigator.rewind(room, TimeshiftNavigator.REPLAY_30_MS))
        assertEquals(0L, TimeshiftNavigator.rewind(room, TimeshiftNavigator.REPLAY_60_MS))
    }

    @Test
    fun seekCannotPassTheEarliestBufferedPoint() {
        assertEquals(8_000L, TimeshiftNavigator.clamp(-20_000L, 8_000L, 50_000L))
        val reading = reading(position = 10_000L, edge = 50_000L, earliest = 8_000L)
        assertEquals(8_000L, TimeshiftNavigator.rewind(reading, 60_000L))
    }

    @Test
    fun seekForwardCannotPassTheLiveEdge() {
        val reading = reading(position = 90_000L, edge = 100_000L)
        assertEquals(100_000L, TimeshiftNavigator.forward(reading, TimeshiftNavigator.REPLAY_30_MS))
        assertEquals(100_000L, TimeshiftNavigator.clamp(150_000L, 0L, 100_000L))
    }

    @Test
    fun goLiveReturnsToTheLiveEdge() {
        val behind = reading(position = 10_000L, edge = 90_000L, playing = false)
        assertEquals(90_000L, TimeshiftNavigator.goLive(behind))
        val back = behind.copy(
            positionMs = TimeshiftNavigator.goLive(behind),
            playWhenReady = true,
            returningToLive = false,
        )
        assertEquals(TimeshiftPlaybackState.LIVE, TimeshiftNavigator.state(back))
        assertTrue(TimeshiftNavigator.atLiveEdge(back))
    }

    @Test
    fun behindLiveDurationIsTheGapToTheLiveEdge() {
        val behindMs = 2 * 60_000L + 34_000L
        val reading = reading(position = 0L, edge = behindMs)
        assertEquals(behindMs, TimeshiftNavigator.behindMs(reading))
        assertEquals(TimeshiftPlaybackState.BEHIND_LIVE, TimeshiftNavigator.state(reading))
        assertEquals("2:34", TimeshiftCopy.formatClock(behindMs))
        assertEquals("\u25CF LIVE -2:34", TimeshiftCopy.liveButtonText(behindMs, atLive = false))
        assertEquals("\u25CF LIVE", TimeshiftCopy.liveButtonText(0L, atLive = true))
        assertEquals(
            "Go live, 2 minutes 34 seconds behind live",
            TimeshiftCopy.liveButtonDescription(behindMs, atLive = false),
        )
        assertEquals("Live, at the live edge", TimeshiftCopy.liveButtonDescription(0L, atLive = true))
        assertEquals("Pause live TV", TimeshiftCopy.pauseDescription(playWhenReady = true))
        assertEquals("Resume", TimeshiftCopy.pauseDescription(playWhenReady = false))
        assertEquals("1:02:03", TimeshiftCopy.formatClock((1 * 3600L + 2 * 60L + 3L) * 1000L))
        // Inside the live-edge tolerance the viewer is still "at live".
        val almost = reading(position = 96_000L, edge = 100_000L)
        assertTrue(TimeshiftNavigator.atLiveEdge(almost))
        assertEquals(TimeshiftPlaybackState.LIVE, TimeshiftNavigator.state(almost))
    }

    @Test
    fun nativeResumeKeepsTheDistanceBehindAMovedWindow() {
        val twoHours = 120 * 60_000L
        val tenMinutes = 10 * 60_000L
        assertEquals(
            twoHours - tenMinutes,
            TimeshiftNavigator.nativeResumeMs(tenMinutes, seekable = true, earliestMs = 0L, liveEdgeMs = twoHours)
        )
        assertNull(TimeshiftNavigator.nativeResumeMs(4_000L, seekable = true, earliestMs = 0L, liveEdgeMs = twoHours))
        assertEquals(
            0L,
            TimeshiftNavigator.nativeResumeMs(tenMinutes, seekable = true, earliestMs = 0L, liveEdgeMs = 2 * 60_000L)
        )
        assertNull(TimeshiftNavigator.nativeResumeMs(tenMinutes, seekable = false, earliestMs = 0L, liveEdgeMs = 0L))
    }

    @Test
    fun channelChangeInvalidatesThePreviousTimeshift() {
        val gate = TimeshiftSessionGate()
        val seen = gate.generation
        val next = gate.channelChanged()
        assertTrue(gate.stale(seen))
        assertFalse(gate.stale(next))
        val root = tempRoot()
        try {
            val store = store(root, durationMs = 60_000L, bytes = 1_000_000L)
            store.append(ByteArray(32) { 1 }, 4_000L, discontinuity = false)
            assertTrue(store.hasSegments())
            store.deleteAll()
            assertFalse(store.playlistFile.exists())
            assertFalse(store.hasSegments())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unsupportedStreamHidesTimeshiftControls() {
        assertFalse(TimeshiftNavigator.controlsAvailable(timeshiftEnabled = true, seekable = false, windowMs = 60_000L))
        assertFalse(TimeshiftNavigator.controlsAvailable(timeshiftEnabled = false, seekable = true, windowMs = 60_000L))
        assertFalse(TimeshiftNavigator.controlsAvailable(timeshiftEnabled = true, seekable = true, windowMs = 7_999L))
        assertTrue(TimeshiftNavigator.controlsAvailable(timeshiftEnabled = true, seekable = true, windowMs = 8_000L))
        val events = Events()
        val root = tempRoot()
        try {
            val loop = TimeshiftLoop(
                store(root, 60_000L, 1_000_000L),
                fetcher { UnknownOpened() },
                TimeshiftSleeper {},
                events,
                maxSteps = 3,
            )
            loop.run("https://provider.example/live")
            assertEquals(listOf("direct", "stopped"), events.steps)
            assertFalse(HlsPlaylistProbe.usefulNative(HlsPlaylistProbe.parse(shortLive(sequence = 1, segments = 1))))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reconnectReturnsToLiveWhenContinuityIsLost() {
        assertEquals(
            TimeshiftReconnect.Action.RETURN_TO_LIVE,
            TimeshiftReconnect.onStreamReconnect(sameUrl = false, missedMs = 0L, sequenceRestarted = false),
        )
        assertEquals(
            TimeshiftReconnect.Action.RETURN_TO_LIVE,
            TimeshiftReconnect.onStreamReconnect(sameUrl = true, missedMs = 15_001L, sequenceRestarted = false),
        )
        assertEquals(
            TimeshiftReconnect.Action.RETURN_TO_LIVE,
            TimeshiftReconnect.onStreamReconnect(sameUrl = true, missedMs = 0L, sequenceRestarted = true),
        )
        assertEquals(
            TimeshiftReconnect.Action.KEEP_BUFFER,
            TimeshiftReconnect.onStreamReconnect(sameUrl = true, missedMs = 15_000L, sequenceRestarted = false),
        )
        assertEquals(
            TimeshiftPlaybackState.LIVE,
            TimeshiftReconnect.stateAfter(TimeshiftReconnect.Action.RETURN_TO_LIVE, wasPaused = true, stillBehind = true),
        )
        assertEquals(
            TimeshiftPlaybackState.PAUSED,
            TimeshiftReconnect.stateAfter(TimeshiftReconnect.Action.KEEP_BUFFER, wasPaused = true, stillBehind = true),
        )

        val events = Events()
        val root = tempRoot()
        try {
            val playlists = ArrayDeque(listOf(
                shortLive(sequence = 10, segments = 1),
                shortLive(sequence = 1, segments = 2),
            ))
            val loop = TimeshiftLoop(
                store(root, 60_000L, 1_000_000L),
                playlistFetcher(playlists),
                TimeshiftSleeper {},
                events,
                maxSteps = 6,
            )
            loop.run("https://provider.example/live/user/secretpass/index.m3u8")
            assertTrue(events.steps.contains("lost"))
            assertFalse(events.playlistText.orEmpty().contains("secretpass"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun bufferStorageIsBounded() {
        val root = tempRoot()
        try {
            val byteBudget = 256L * 1024L
            val store = store(root, durationMs = 24L * 60L * 60L * 1000L, bytes = byteBudget)
            val payload = ByteArray(4 * 1024) { 7 }
            repeat(80) { store.append(payload, 4_000L, discontinuity = false) }
            assertEquals(byteBudget, store.totalBytes)
            // Three segment slots stay reserved so a player can finish reading a file that
            // just left the playlist. At this size that leaves 62 published and 2 in grace.
            assertEquals(62, store.segmentCount())
            val onDisk = directoryBytes(File(root, "s0123456789abcdef"))
            assertTrue("segment bytes plus playlist stayed bounded: $onDisk", onDisk < byteBudget + 64L * 1024L)
            assertTrue(onDisk > byteBudget)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oldSegmentsAreEvicted() {
        val root = tempRoot()
        try {
            val dir = File(root, "s0123456789abcdef")
            val store = store(root, durationMs = 30L * 60L * 1000L, bytes = 50L * 1024L * 1024L)
            val payload = ByteArray(64) { 3 }
            repeat(900) { store.append(payload, 4_000L, discontinuity = false) }
            assertEquals(450, store.segmentCount())
            assertEquals(30L * 60L * 1000L, store.retainedDurationMs())
            assertEquals((450L + TimeshiftStore.GRACE_SEGMENTS) * 64L, store.totalBytes)
            assertFalse(File(dir, "seg-00000000.ts").exists())
            assertTrue(File(dir, TimeshiftPlaylist.fileName(449)).exists())
            assertFalse(store.playlistFile.readText().contains("seg-00000449.ts"))
            assertTrue(File(dir, TimeshiftPlaylist.fileName(899)).exists())
            val text = store.playlistFile.readText()
            assertFalse(text.contains("http"))
            assertFalse(text.contains("#EXT-X-ENDLIST"))
            val probe = HlsPlaylistProbe.parse(text)
            assertEquals(450L, probe.mediaSequence)
            assertEquals(30L * 60L * 1000L, probe.windowMs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun bufferIsDeletedWhenPlaybackEnds() {
        val root = tempRoot()
        try {
            val store = store(root, durationMs = 60_000L, bytes = 1_000_000L)
            store.append(ByteArray(16) { 1 }, 4_000L, discontinuity = false)
            val session = LiveTimeshiftSession(
                OkHttpClient(),
                store,
                "MKTV-test",
                TimeshiftSleeper {},
                maxSteps = 1,
            )
            assertTrue(store.playlistFile.exists())
            session.close()
            session.joinForTest(5_000)
            assertFalse(store.playlistFile.exists())
            assertEquals(0, store.segmentCount())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun lowStorageDoesNotStopLivePlayback() {
        assertTrue(TimeshiftLimits.playbackContinuesWhenStorageStops())
        assertFalse(TimeshiftLimits.resolve(30, TimeshiftLimits.LOW_FREE_BYTES - 1).localEnabled)
        val saved = 60
        val shortened = TimeshiftLimits.resolve(saved, TimeshiftLimits.LOW_DEVICE_FREE_BYTES - 1)
        assertEquals(60, saved)
        assertEquals(15L * 60L * 1000L, shortened.durationMs)
        assertTrue(shortened.localEnabled)
        assertFalse(TimeshiftLimits.resolve(0, Long.MAX_VALUE).localEnabled)

        val events = Events()
        val root = tempRoot()
        try {
            var checks = 0
            val store = TimeshiftStore(
                File(root, "s0123456789abcdef"),
                60_000L,
                1_000_000L,
                { if (checks++ == 0) Long.MAX_VALUE else 0L },
                minFreeBytes = 1L,
            )
            val playlists = ArrayDeque(listOf(
                shortLive(sequence = 1, segments = 1),
                shortLive(sequence = 1, segments = 2),
            ))
            val loop = TimeshiftLoop(store, playlistFetcher(playlists), TimeshiftSleeper {}, events, maxSteps = 6)
            loop.run("https://provider.example/index.m3u8")
            assertTrue(events.steps.contains("storage"))
            assertTrue(store.hasSegments())
            assertTrue(store.stoppedForStorage)
            assertTrue(store.playlistFile.exists())
            store.deleteAll()
            assertFalse(store.playlistFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recordingFilesAreLeftAlone() {
        val root = tempRoot()
        try {
            val recordings = File(root, "recordings").apply { mkdirs() }
            val kept = File(recordings, "show.ts")
            kept.writeBytes(byteArrayOf(1, 2, 3))
            val inside = File(root, "timeshift")
            File(inside, "recordings").apply { mkdirs() }
            val recordingInside = File(inside, "recordings/episode.ts")
            recordingInside.parentFile?.mkdirs()
            recordingInside.writeBytes(byteArrayOf(4, 5))
            val stale = File(inside, "sdeadbeefdeadbeef").apply { mkdirs() }
            File(stale, "seg-00000000.ts").writeBytes(byteArrayOf(9))
            val live = File(inside, "slive000000000000").apply { mkdirs() }
            TimeshiftStore.sweepStale(inside, keep = live)
            assertFalse(stale.exists())
            assertTrue(live.exists())
            assertTrue(kept.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
            assertTrue(recordingInside.readBytes().contentEquals(byteArrayOf(4, 5)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aUsefulProviderWindowIsNotCopied() {
        val events = Events()
        val root = tempRoot()
        try {
            val window = StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:1\n")
            repeat(10) { window.append("#EXTINF:4.0,\nseg$it.ts\n") }
            val probe = HlsPlaylistProbe.parse(window.toString())
            assertTrue(HlsPlaylistProbe.usefulNative(probe))
            assertTrue(probe.windowMs >= TimeshiftNavigator.USEFUL_NATIVE_WINDOW_MS)
            val loop = TimeshiftLoop(
                store(root, 60_000L, 1_000_000L),
                playlistFetcher(ArrayDeque(listOf(window.toString()))),
                TimeshiftSleeper {},
                events,
                maxSteps = 4,
            )
            loop.run("https://provider.example/live/user/secretpass/index.m3u8")
            assertEquals(listOf("direct", "stopped"), events.steps)
            assertFalse(storeDir(root).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun redirectedPlaylistStoresSequenceNamesOnly() {
        val playlist = shortLive(sequence = 5, segments = 1).toByteArray()
        val segment = ByteArray(188) { 0x47 }
        val server = LocalRedirectServer(playlist, segment)
        val root = tempRoot()
        try {
            val store = store(root, durationMs = 60_000L, bytes = 1_000_000L)
            val captured = AtomicReference<String>()
            val ready = CountDownLatch(1)
            val session = LiveTimeshiftSession(
                OkHttpClient(),
                store,
                "MKTV-test",
                TimeshiftSleeper {},
                maxSteps = 2,
            )
            session.start("http://127.0.0.1:${server.port}/go", object : TimeshiftLoop.Listener {
                override fun onReady(playlist: File) {
                    captured.set(playlist.readText() + "\n" + playlist.parentFile?.list()?.joinToString(","))
                    ready.countDown()
                }
            })
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val text = captured.get().orEmpty()
            assertTrue(text.contains("seg-"))
            assertFalse(text.contains("secretpass"))
            assertFalse(text.contains("/live/"))
            session.joinForTest(5_000)
            assertFalse(store.playlistFile.exists())
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun rawTransportStreamIsCutOnPacketBoundaries() {
        var now = 0L
        val cutter = TsCutter(clock = { now })
        val first = cutter.feed(tsPacket(randomAccess = true), 0, TsCutter.PACKET)
        assertTrue(first.isEmpty())
        now = 1_000L
        val second = cutter.feed(tsPacket(randomAccess = true), 0, TsCutter.PACKET)
        assertEquals(1, second.size)
        assertEquals(TsCutter.PACKET, second[0].bytes.size)
        assertEquals(0x47.toByte(), second[0].bytes[0])
        now = 1_000L + 8_000L
        val forced = cutter.feed(tsPacket(randomAccess = false), 0, TsCutter.PACKET)
        assertEquals(1, forced.size)
        cutter.markDiscontinuity()
        now += 4_000L
        assertTrue(cutter.feed(tsPacket(randomAccess = false), 0, TsCutter.PACKET).isEmpty())
        now += 4_000L
        val marked = cutter.feed(tsPacket(randomAccess = true), 0, TsCutter.PACKET)
        assertEquals(1, marked.size)
        assertTrue(marked[0].discontinuity)
    }

    @Test
    fun anEmptyTransportStreamStopsRetrying() {
        var opens = 0
        val events = Events()
        val root = tempRoot()
        try {
            val loop = TimeshiftLoop(
                store(root, 60_000L, 1_000_000L),
                object : TimeshiftLoop.Fetcher {
                    override fun open(url: String): TimeshiftLoop.Opened {
                        opens++
                        return EndingTs()
                    }
                },
                TimeshiftSleeper {},
                events,
                maxSteps = 40,
            )
            loop.run("https://provider.example/live.ts")
            assertTrue(events.steps.toString(), events.steps.contains("giveup"))
            assertTrue("opens=$opens", opens in 2..12)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aReconnectDropsTheUnfinishedTransportStreamPiece() {
        val cutter = TsCutter(clock = { 0L })
        assertTrue(cutter.feed(tsPacket(randomAccess = false, pcr90k = 0L), 0, TsCutter.PACKET).isEmpty())
        cutter.feed(ByteArray(20) { 0x47 }, 0, 20)
        cutter.markDiscontinuity()
        val burst = ByteArray(TsCutter.PACKET * 2)
        System.arraycopy(tsPacket(randomAccess = false, pcr90k = 0L), 0, burst, 0, TsCutter.PACKET)
        System.arraycopy(tsPacket(randomAccess = true, pcr90k = 90_000L), 0, burst, TsCutter.PACKET, TsCutter.PACKET)
        val cuts = cutter.feed(burst, 0, burst.size)
        assertEquals(1, cuts.size)
        assertEquals(TsCutter.PACKET, cuts[0].bytes.size)
        assertEquals(0x47.toByte(), cuts[0].bytes[0])
        assertTrue(cuts[0].discontinuity)
    }

    @Test
    fun simulatedHalfHourStaysInsideTheThirtyMinuteCap() {
        val root = tempRoot()
        val notes = StringBuilder()
        try {
            val store = store(root, durationMs = 30L * 60L * 1000L, bytes = TimeshiftLimits.ABSOLUTE_MAX_BYTES)
            val payload = ByteArray(64) { 1 }
            fun checkpoint(label: String) {
                val disk = directoryBytes(storeDir(root))
                notes.append(label)
                    .append(" segments=").append(store.segmentCount())
                    .append(" durationMs=").append(store.retainedDurationMs())
                    .append(" segmentBytes=").append(store.totalBytes)
                    .append(" diskBytes=").append(disk)
                    .append('\n')
            }
            repeat(150) { store.append(payload, 4_000L, discontinuity = false) }
            checkpoint("10min")
            assertEquals(150, store.segmentCount())
            assertEquals(10L * 60L * 1000L, store.retainedDurationMs())
            repeat(300) { store.append(payload, 4_000L, discontinuity = false) }
            checkpoint("30min")
            assertEquals(450, store.segmentCount())
            assertEquals(30L * 60L * 1000L, store.retainedDurationMs())
            repeat(450) { store.append(payload, 4_000L, discontinuity = false) }
            checkpoint("60min")
            assertEquals(450, store.segmentCount())
            assertEquals(30L * 60L * 1000L, store.retainedDurationMs())
            assertEquals((450L + TimeshiftStore.GRACE_SEGMENTS) * 64L, store.totalBytes)
            val disk = directoryBytes(storeDir(root))
            assertTrue(disk < 256L * 1024L)
            File(System.getProperty("java.io.tmpdir"), "mktv-timeshift-measure.txt").writeText(notes.toString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun epgFollowsThePlaybackClockAndSeekingStateWins() {
        val shows = listOf(
            TimeshiftEpg.Show("Earlier", 0L, 30_000L),
            TimeshiftEpg.Show("Now", 30_000L, 90_000L),
        )
        val behind = 40_000L
        val wall = TimeshiftEpg.playbackWallMs(nowMs = 50_000L, behindMs = behind)
        assertEquals("Earlier", TimeshiftEpg.programmeAt(shows, wall)?.title)
        assertEquals("Now", TimeshiftEpg.programmeAt(shows, 50_000L)?.title)
        assertEquals("Now", TimeshiftEpg.programmeForUi(shows, 50_000L, behind, atLive = true)?.title)
        assertEquals("Earlier", TimeshiftEpg.programmeForUi(shows, 50_000L, behind, atLive = false)?.title)
        // A retained window's end stays put. Near that end still looks "live" on the
        // player timeline, but the guide must not follow the wall clock.
        val frozenShows = shows + TimeshiftEpg.Show("Later", 90_000L, 150_000L)
        assertEquals(
            "Now",
            TimeshiftEpg.programmeForUi(
                frozenShows, nowMs = 120_000L, behindMs = 5_000L, atLive = true, frozenEndpointMs = 50_000L,
            )?.title,
        )
        assertEquals(
            "Earlier",
            TimeshiftEpg.programmeForUi(
                frozenShows, nowMs = 120_000L, behindMs = 25_000L, atLive = false, frozenEndpointMs = 50_000L,
            )?.title,
        )
        val seeking = reading(position = 10_000L, edge = 50_000L, playing = false).copy(seeking = true)
        assertEquals(TimeshiftPlaybackState.SEEKING, TimeshiftNavigator.state(seeking))
        val returning = reading(position = 10_000L, edge = 50_000L).copy(returningToLive = true)
        assertEquals(TimeshiftPlaybackState.RETURNING_TO_LIVE, TimeshiftNavigator.state(returning))
    }

    @Test
    fun aPauseNearTheLiveEdgeKeepsTheBufferWhenStorageFails() {
        assertFalse(TimeshiftNavigator.followProviderOnStorageLoss(atLiveEdge = true, intendedPlaying = false))
        assertFalse(TimeshiftNavigator.followProviderOnStorageLoss(atLiveEdge = false, intendedPlaying = false))
        assertFalse(TimeshiftNavigator.followProviderOnStorageLoss(atLiveEdge = false, intendedPlaying = true))
        assertTrue(TimeshiftNavigator.followProviderOnStorageLoss(atLiveEdge = true, intendedPlaying = true))
    }

    @Test
    fun startupToggleFollowsThePendingRequest() {
        assertTrue(TimeshiftNavigator.intendedPlaying(starting = true, autoplay = true, playWhenReady = false))
        assertFalse(TimeshiftNavigator.intendedPlaying(starting = true, autoplay = false, playWhenReady = false))
        assertFalse(TimeshiftNavigator.intendedPlaying(starting = false, autoplay = true, playWhenReady = false))
        assertTrue(TimeshiftNavigator.intendedPlaying(starting = false, autoplay = false, playWhenReady = true))
    }

    @Test
    fun targetDurationCoversTheNextCutAndDoesNotShrink() {
        val first = TimeshiftPlaylist.render(
            listOf(TimeshiftPlaylist.Segment(0L, 1_000L, "seg-00000000.ts", false)),
        )
        assertTrue(first.contains("#EXT-X-TARGETDURATION:${TimeshiftPlaylist.MIN_TARGET_SEC}"))
        val root = tempRoot()
        try {
            val kept = store(root, durationMs = 1_500L, bytes = 1_000_000L)
            assertEquals(TimeshiftStore.Append.STORED, kept.append(ByteArray(8) { 1 }, 1_000L, false))
            assertTrue(kept.playlistFile.readText().contains("#EXT-X-TARGETDURATION:8"))
            assertEquals(TimeshiftStore.Append.STORED, kept.append(ByteArray(8) { 2 }, 10_000L, false))
            assertEquals(TimeshiftStore.Append.STORED, kept.append(ByteArray(8) { 3 }, 1_000L, false))
            val text = kept.playlistFile.readText()
            assertTrue(text.contains("#EXT-X-TARGETDURATION:10"))
            assertTrue(text.contains("seg-00000002.ts"))
            assertFalse(text.contains("seg-00000000.ts"))
            assertFalse(text.contains("seg-00000001.ts"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aFailedRetainReportsInsteadOfCrashing() {
        val root = tempRoot()
        try {
            val kept = store(root, 60_000L, 1_000_000L)
            assertEquals(TimeshiftStore.Append.STORED, kept.append(byteArrayOf(1, 2, 3, 4), 4_000L, false))
            val dir = kept.playlistFile.parentFile!!
            assertTrue(dir.deleteRecursively())
            assertTrue(dir.createNewFile())
            val events = java.util.Collections.synchronizedList(mutableListOf<String>())
            val session = LiveTimeshiftSession(
                OkHttpClient(),
                kept,
                "MKTV-test",
                TimeshiftSleeper {},
                maxSteps = 1,
                initialListener = object : TimeshiftLoop.Listener {
                    override fun onRetainFailed() { events.add("retain-failed") }
                },
            )
            session.stopKeepingFiles()
            session.joinForTest(5_000)
            assertEquals(listOf("retain-failed"), events.toList())
        } finally {
            val dir = File(root, "s0123456789abcdef")
            if (dir.isFile) dir.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun aFrozenBufferCanStillBeDeletedOnClose() {
        val root = tempRoot()
        try {
            val store = store(root, durationMs = 60_000L, bytes = 1_000_000L)
            store.append(ByteArray(8) { 2 }, 4_000L, discontinuity = false)
            val session = LiveTimeshiftSession(OkHttpClient(), store, "MKTV-test", TimeshiftSleeper {}, maxSteps = 1)
            session.stopKeepingFiles()
            session.joinForTest(5_000)
            assertTrue(store.playlistFile.readText().contains("#EXT-X-ENDLIST"))
            session.close()
            session.joinForTest(5_000)
            assertFalse(store.playlistFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun timelineSeekUsesThePlayerPosition() {
        val reading = TimeshiftNavigator.readingFromTimeline(
            isLive = true,
            seekable = true,
            durationMs = 100_000L,
            positionMs = 40_000L,
            liveOffsetMs = 90_000L,
            playWhenReady = true,
            seeking = false,
            returningToLive = false,
        )
        assertEquals(40_000L, reading.positionMs)
        assertEquals(60_000L, TimeshiftNavigator.behindMs(reading))
    }

    @Test
    fun `local cushion behind the newest piece still counts as live`() {
        fun at(positionMs: Long) = TimeshiftNavigator.readingFromTimeline(
            isLive = true,
            seekable = true,
            durationMs = 100_000L,
            positionMs = positionMs,
            liveOffsetMs = null,
            playWhenReady = true,
            seeking = false,
            returningToLive = false,
            edgeHoldbackMs = 10_000L,
        )
        // Playing 10 s behind the newest piece is where a local playlist sits at live.
        assertTrue(TimeshiftNavigator.atLiveEdge(at(90_000L)))
        // Go Live lands on the cushion, not the very end of the window.
        assertEquals(90_000L, TimeshiftNavigator.goLive(at(50_000L)))
        // Forward cannot push past the cushion either.
        assertEquals(90_000L, TimeshiftNavigator.forward(at(85_000L), 30_000L))
        assertFalse(TimeshiftNavigator.atLiveEdge(at(80_000L)))
    }

    @Test
    fun `inside the cushion playback is the edge and Forward does not step back`() {
        fun at(durationMs: Long, positionMs: Long) = TimeshiftNavigator.readingFromTimeline(
            isLive = true,
            seekable = true,
            durationMs = durationMs,
            positionMs = positionMs,
            liveOffsetMs = null,
            playWhenReady = true,
            seeking = false,
            returningToLive = false,
            edgeHoldbackMs = 10_000L,
        )
        // A window still shorter than the cushion, just starting.
        assertTrue(TimeshiftNavigator.atLiveEdge(at(6_000L, 2_000L)))
        assertEquals(2_000L, TimeshiftNavigator.forward(at(6_000L, 2_000L), 10_000L))
        // Playback that got closer than the cushion (a Go Live before this fix, say).
        val close = at(100_000L, 97_000L)
        assertEquals(97_000L, close.liveEdgeMs)
        assertEquals(97_000L, TimeshiftNavigator.forward(close, 10_000L))
        // No cushion: a frozen window plays to its very end.
        val frozen = TimeshiftNavigator.readingFromTimeline(
            isLive = true, seekable = true, durationMs = 100_000L, positionMs = 85_000L,
            liveOffsetMs = null, playWhenReady = true, seeking = false, returningToLive = false,
        )
        assertEquals(95_000L, TimeshiftNavigator.forward(frozen, 10_000L))
    }

    @Test
    fun encryptedOrMappedPlaylistsAreNotCopied() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/k"
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:1
            #EXTINF:4.0,
            a.ts
        """.trimIndent()
        assertFalse(HlsPlaylistProbe.parse(text).canBufferLocally)
        val events = Events()
        val root = tempRoot()
        try {
            val loop = TimeshiftLoop(
                store(root, 60_000L, 1_000_000L),
                playlistFetcher(ArrayDeque(listOf(text))),
                TimeshiftSleeper {},
                events,
                maxSteps = 3,
            )
            loop.run("https://provider.example/live/user/secretpass/index.m3u8")
            assertEquals(listOf("direct", "stopped"), events.steps)
            assertFalse(storeDir(root).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aSniffFailureRetriesInsteadOfCrashing() {
        val events = Events()
        val root = tempRoot()
        try {
            val loop = TimeshiftLoop(
                store(root, 60_000L, 1_000_000L),
                fetcher { throwOnSniff() },
                TimeshiftSleeper {},
                events,
                maxSteps = 20,
            )
            loop.run("https://provider.example/live")
            assertTrue(events.steps.contains("giveup"))
            assertEquals("stopped", events.steps.last())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aLongTransportStreamOutageReturnsToLive() {
        val burst = pcrBurst()
        var now = 0L
        var opens = 0
        val events = Events()
        val root = tempRoot()
        try {
            val loop = TimeshiftLoop(
                store(root, 60_000L, 1_000_000L),
                object : TimeshiftLoop.Fetcher {
                    override fun open(url: String): TimeshiftLoop.Opened {
                        opens++
                        if (opens == 1) return BytesOpened(burst)
                        now = TimeshiftReconnect.MAX_GAP_MS + 1_000L
                        return EndingTs()
                    }
                },
                TimeshiftSleeper {},
                events,
                maxSteps = 8,
                cutterClock = { now },
            )
            loop.run("https://provider.example/live.ts")
            assertTrue(events.steps.toString(), events.steps.contains("ready"))
            assertTrue(events.steps.toString(), events.steps.contains("lost"))
            assertEquals("stopped", events.steps.last())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aShortTransportStreamGapKeepsTheBuffer() {
        val burst = pcrBurst()
        var now = 0L
        var opens = 0
        val events = Events()
        val root = tempRoot()
        try {
            val kept = store(root, 60_000L, 1_000_000L)
            val loop = TimeshiftLoop(
                kept,
                object : TimeshiftLoop.Fetcher {
                    override fun open(url: String): TimeshiftLoop.Opened {
                        opens++
                        if (opens == 1) return BytesOpened(burst)
                        now = 1_000L
                        // Later pieces wait for 4s of programme time. One second would not close.
                        if (opens == 2) return BytesOpened(pcrBurst(360_000L))
                        return EndingTs()
                    }
                },
                TimeshiftSleeper {},
                events,
                maxSteps = 8,
                cutterClock = { now },
            )
            loop.retainFiles = true
            loop.run("https://provider.example/live.ts")
            assertTrue(events.steps.toString(), events.steps.contains("ready"))
            assertFalse(events.steps.toString(), events.steps.contains("lost"))
            assertTrue(kept.playlistFile.readText().contains("#EXT-X-DISCONTINUITY"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aBlockedTransportStreamReadCountsTheStall() {
        val burst = pcrBurst()
        var now = 0L
        var opens = 0
        var emptyReads = 0
        val events = Events()
        val root = tempRoot()
        try {
            val loop = TimeshiftLoop(
                store(root, 60_000L, 1_000_000L),
                object : TimeshiftLoop.Fetcher {
                    override fun open(url: String): TimeshiftLoop.Opened {
                        opens++
                        if (opens == 1) {
                            return TsThen(burst) {
                                // Empty reads are not progress, and the failing read itself
                                // may have been blocked for the client timeout.
                                if (emptyReads < 2) {
                                    emptyReads++
                                    now += 1_000L
                                    0
                                } else {
                                    now += 120_000L
                                    -1
                                }
                            }
                        }
                        return EndingTs()
                    }
                },
                TimeshiftSleeper {},
                events,
                maxSteps = 12,
                cutterClock = { now },
            )
            loop.run("https://provider.example/live.ts")
            assertTrue(events.steps.toString(), events.steps.contains("ready"))
            assertTrue(events.steps.toString(), events.steps.contains("lost"))
            assertEquals("stopped", events.steps.last())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anEmptyTransportStreamReadDoesNotRefreshTheOutageClock() {
        var now = 0L
        val events = Events()
        val root = tempRoot()
        try {
            val loop = TimeshiftLoop(
                store(root, 60_000L, 1_000_000L),
                object : TimeshiftLoop.Fetcher {
                    private var opens = 0
                    override fun open(url: String): TimeshiftLoop.Opened {
                        opens++
                        if (opens != 1) return EndingTs()
                        var empties = 0
                        return TsThen(pcrBurst()) {
                            if (empties < 3) {
                                empties++
                                now += 6_000L
                                0
                            } else {
                                -1
                            }
                        }
                    }
                },
                TimeshiftSleeper {},
                events,
                maxSteps = 12,
                cutterClock = { now },
            )
            loop.run("https://provider.example/live.ts")
            assertTrue(events.steps.toString(), events.steps.contains("ready"))
            assertTrue(events.steps.toString(), events.steps.contains("lost"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aLaterShortStallIsMeasuredFromTheNewestBytes() {
        var now = 0L
        var opens = 0
        val events = Events()
        val root = tempRoot()
        try {
            val kept = store(root, 60_000L, 1_000_000L)
            val loop = TimeshiftLoop(
                kept,
                object : TimeshiftLoop.Fetcher {
                    override fun open(url: String): TimeshiftLoop.Opened {
                        opens++
                        return when (opens) {
                            1 -> BytesOpened(pcrBurst())
                            2 -> {
                                // Still inside the reconnect limit, so the buffer is kept.
                                now = 12_000L
                                TsThen(pcrBurst(360_000L)) {
                                    // Long from the first byte, short from the byte just stored.
                                    now += 10_000L
                                    -1
                                }
                            }
                            else -> EndingTs()
                        }
                    }
                },
                TimeshiftSleeper {},
                events,
                maxSteps = 16,
                cutterClock = { now },
            )
            loop.retainFiles = true
            loop.run("https://provider.example/live.ts")
            assertTrue(events.steps.toString(), events.steps.contains("ready"))
            assertFalse(events.steps.toString(), events.steps.contains("lost"))
            assertTrue(kept.playlistFile.readText().contains("#EXT-X-DISCONTINUITY"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aDroppedTransportStreamRetriesUntilTheFailureLimit() {
        var opens = 0
        val events = Events()
        val root = tempRoot()
        try {
            val loop = TimeshiftLoop(
                store(root, 60_000L, 1_000_000L),
                object : TimeshiftLoop.Fetcher {
                    override fun open(url: String): TimeshiftLoop.Opened {
                        val n = opens++
                        if (n == 0) return EndingTs()
                        throw java.io.IOException("down")
                    }
                },
                TimeshiftSleeper {},
                events,
                maxSteps = 20,
            )
            loop.run("https://provider.example/live.ts")
            assertTrue("opens=$opens steps=${events.steps}", opens > 2)
            assertTrue(events.steps.toString(), events.steps.contains("giveup"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aNewSegmentStartsWithTheLastProgramTables() {
        var now = 0L
        val cutter = TsCutter(clock = { now })
        assertTrue(cutter.feed(patPacket(0x100), 0, TsCutter.PACKET).isEmpty())
        assertTrue(cutter.feed(pmtPacket(0x100), 0, TsCutter.PACKET).isEmpty())
        assertTrue(cutter.feed(tsPacket(randomAccess = false, pcr90k = 0L), 0, TsCutter.PACKET).isEmpty())
        now = 1_000L
        val first = cutter.feed(tsPacket(randomAccess = true, pcr90k = 90_000L), 0, TsCutter.PACKET)
        assertEquals(1, first.size)
        assertEquals(1_000L, first[0].durationMs)
        assertEquals(3 * TsCutter.PACKET, first[0].bytes.size)
        now = 5_000L
        val second = cutter.feed(
            tsPacket(randomAccess = true, pcr90k = 90_000L + 360_000L),
            0,
            TsCutter.PACKET,
        )
        assertEquals(1, second.size)
        assertEquals(4_000L, second[0].durationMs)
        assertEquals(3 * TsCutter.PACKET, second[0].bytes.size)
        assertEquals(0, TsCutter.pidOf(second[0].bytes, 0))
        assertEquals(0x100, TsCutter.pidOf(second[0].bytes, TsCutter.PACKET))
        assertEquals(0x40, second[0].bytes[1].toInt() and 0xFF)
    }

    @Test
    fun aReconnectDoesNotPrefixTheOldProgramTables() {
        var now = 0L
        val cutter = TsCutter(clock = { now })
        cutter.feed(patPacket(0x100), 0, TsCutter.PACKET)
        cutter.feed(pmtPacket(0x100), 0, TsCutter.PACKET)
        cutter.feed(tsPacket(randomAccess = false, pcr90k = 0L), 0, TsCutter.PACKET)
        cutter.markDiscontinuity()
        cutter.feed(tsPacket(randomAccess = false, pcr90k = 0L), 0, TsCutter.PACKET)
        now = 1_000L
        val first = cutter.feed(tsPacket(randomAccess = true, pcr90k = 90_000L), 0, TsCutter.PACKET)
        assertEquals(1, first.size)
        assertEquals(TsCutter.PACKET, first[0].bytes.size)
        now = 5_000L
        val second = cutter.feed(
            tsPacket(randomAccess = true, pcr90k = 90_000L + 360_000L),
            0,
            TsCutter.PACKET,
        )
        assertEquals(1, second.size)
        assertEquals(TsCutter.PACKET, second[0].bytes.size)
    }

    @Test
    fun aBurstUsesProgrammeTimeNotArrivalTime() {
        var now = 0L
        val cutter = TsCutter(clock = { now })
        val burst = ByteArray(TsCutter.PACKET * 2)
        System.arraycopy(tsPacket(randomAccess = false, pcr90k = 0L), 0, burst, 0, TsCutter.PACKET)
        System.arraycopy(tsPacket(randomAccess = true, pcr90k = 90_000L), 0, burst, TsCutter.PACKET, TsCutter.PACKET)
        val cuts = cutter.feed(burst, 0, burst.size)
        assertEquals(1, cuts.size)
        assertEquals(1_000L, cuts[0].durationMs)
        assertEquals(TsCutter.PACKET, cuts[0].bytes.size)
    }

    @Test
    fun aStallOnAnUntimestampedPacketKeepsTheMediaDuration() {
        var now = 0L
        val cutter = TsCutter(clock = { now })
        assertTrue(cutter.feed(tsPacket(randomAccess = false, pcr90k = 0L), 0, TsCutter.PACKET).isEmpty())
        now = 1_000L
        assertTrue(cutter.feed(tsPacket(randomAccess = false, pcr90k = 180_000L), 0, TsCutter.PACKET).isEmpty())
        now = 40_000L
        val cut = cutter.feed(tsPacket(randomAccess = false), 0, TsCutter.PACKET)
        assertEquals(1, cut.size)
        assertEquals(2_000L, cut[0].durationMs)
    }

    @Test
    fun aStallDoesNotStretchTheSegmentDuration() {
        var now = 0L
        val cutter = TsCutter(clock = { now })
        assertTrue(cutter.feed(tsPacket(randomAccess = false, pcr90k = 0L), 0, TsCutter.PACKET).isEmpty())
        now = 30_000L
        val cut = cutter.feed(tsPacket(randomAccess = false, pcr90k = 90_000L), 0, TsCutter.PACKET)
        assertEquals(1, cut.size)
        assertEquals(1_000L, cut[0].durationMs)
    }

    @Test
    fun presentationTimestampsAlsoTimeTheSegment() {
        var now = 0L
        val cutter = TsCutter(clock = { now })
        val burst = ByteArray(TsCutter.PACKET * 2)
        System.arraycopy(tsPacketWithPts(randomAccess = false, pts90k = 0L), 0, burst, 0, TsCutter.PACKET)
        System.arraycopy(tsPacketWithPts(randomAccess = true, pts90k = 90_000L), 0, burst, TsCutter.PACKET, TsCutter.PACKET)
        val cuts = cutter.feed(burst, 0, burst.size)
        assertEquals(1, cuts.size)
        assertEquals(1_000L, cuts[0].durationMs)
    }

    @Test
    fun separateAudioOrSubtitlePlaylistsAreNotCopied() {
        val audio = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO="aac"
            video.m3u8
        """.trimIndent()
        val subs = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="sub",NAME="English",URI="sub.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,SUBTITLES="sub"
            video.m3u8
        """.trimIndent()
        assertFalse(HlsPlaylistProbe.parse(audio).canBufferLocally)
        assertFalse(HlsPlaylistProbe.parse(subs).canBufferLocally)
        val events = Events()
        val root = tempRoot()
        try {
            val loop = TimeshiftLoop(
                store(root, 60_000L, 1_000_000L),
                playlistFetcher(ArrayDeque(listOf(audio))),
                TimeshiftSleeper {},
                events,
                maxSteps = 3,
            )
            loop.run("https://provider.example/live/user/secretpass/index.m3u8")
            assertEquals(listOf("direct", "stopped"), events.steps)
            assertFalse(storeDir(root).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aMasterPlaylistIsLeftToThePlayer() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",DEFAULT=YES,AUTOSELECT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=426x240
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080,AUDIO="aac"
            high.m3u8
        """.trimIndent()
        val events = Events()
        val root = tempRoot()
        try {
            val loop = TimeshiftLoop(
                store(root, 60_000L, 1_000_000L),
                playlistFetcher(ArrayDeque(listOf(master))),
                TimeshiftSleeper {},
                events,
                maxSteps = 3,
            )
            loop.run("https://provider.example/live/index.m3u8")
            assertEquals(listOf("direct", "stopped"), events.steps)
            assertFalse(storeDir(root).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anHlsWriteFailureKeepsSegmentsAlreadyStored() {
        var checks = 0
        val events = Events()
        val root = tempRoot()
        try {
            val kept = store(root, 60_000L, 1_000_000L) {
                checks++
                if (checks > 1) throw java.io.IOException("disk")
                Long.MAX_VALUE
            }
            val playlists = ArrayDeque(
                listOf(shortLive(sequence = 1, segments = 1), shortLive(sequence = 2, segments = 1)),
            )
            val loop = TimeshiftLoop(kept, playlistFetcher(playlists), TimeshiftSleeper {}, events, maxSteps = 6)
            loop.run("https://provider.example/live/index.m3u8")
            assertTrue(events.steps.toString(), events.steps.contains("ready"))
            assertTrue(events.steps.toString(), events.steps.contains("storage"))
            assertFalse(events.steps.toString(), events.steps.contains("giveup"))
            assertEquals("stopped", events.steps.last())
            assertTrue(kept.hasSegments())
            assertTrue(kept.playlistFile.exists())
            assertFalse(kept.playlistFile.readText().contains("provider.example"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aStorageFailureDuringTransportStreamDoesNotEscape() {
        val events = Events()
        val root = tempRoot()
        try {
            val blocking = storeDir(root)
            blocking.parentFile?.mkdirs()
            blocking.writeBytes(byteArrayOf(1))
            val store = TimeshiftStore(blocking, 60_000L, 1_000_000L, { Long.MAX_VALUE }, minFreeBytes = 0L)
            val burst = ByteArray(TsCutter.PACKET * 2)
            System.arraycopy(tsPacket(randomAccess = false, pcr90k = 0L), 0, burst, 0, TsCutter.PACKET)
            System.arraycopy(tsPacket(randomAccess = true, pcr90k = 90_000L), 0, burst, TsCutter.PACKET, TsCutter.PACKET)
            val loop = TimeshiftLoop(
                store,
                object : TimeshiftLoop.Fetcher {
                    override fun open(url: String): TimeshiftLoop.Opened = BytesOpened(burst)
                },
                TimeshiftSleeper {},
                events,
                maxSteps = 5,
            )
            loop.run("https://provider.example/live.ts")
            assertTrue(events.steps.toString(), events.steps.contains("storage"))
            assertEquals("stopped", events.steps.last())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun downloadedBytesAreReportedForTheBandwidthTracker() {
        val playlist = shortLive(sequence = 1, segments = 1).toByteArray()
        val segment = ByteArray(64) { 7 }
        val server = LocalRedirectServer(playlist, segment)
        val root = tempRoot()
        val counted = AtomicLong()
        try {
            val session = LiveTimeshiftSession(
                OkHttpClient(),
                store(root, 60_000L, 1_000_000L),
                "MKTV-test",
                TimeshiftSleeper {},
                maxSteps = 2,
                onNetworkBytes = { counted.addAndGet(it) },
            )
            val ready = CountDownLatch(1)
            session.start("http://127.0.0.1:${server.port}/live.m3u8", object : TimeshiftLoop.Listener {
                override fun onReady(playlist: File) { ready.countDown() }
            })
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertTrue(counted.get() >= playlist.size + segment.size)
            session.close()
            session.joinForTest(5_000)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aBrokenSegmentDownloadIsNotStored() {
        val events = Events()
        val root = tempRoot()
        try {
            val kept = store(root, 60_000L, 1_000_000L)
            val loop = TimeshiftLoop(
                kept,
                fetcher { url ->
                    if (url.endsWith(".ts")) {
                        object : TimeshiftLoop.Opened {
                            override fun sniff(): TimeshiftLoop.Kind = TimeshiftLoop.Kind.TS
                            override fun playlistText(): String? = null
                            override fun finalUrl(): String = url
                            override fun read(buffer: ByteArray): Int = throw java.io.IOException("reset")
                            override fun close() = Unit
                        }
                    } else {
                        TextOpened(url, shortLive(sequence = 1, segments = 1))
                    }
                },
                TimeshiftSleeper {},
                events,
                maxSteps = 3,
            )
            loop.run("https://provider.example/live/index.m3u8")
            assertEquals(0, kept.segmentCount())
            assertFalse(events.steps.contains("ready"))
            assertEquals("stopped", events.steps.last())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anEvictedSegmentRemainsUntilTheReaderGraceEnds() {
        var now = 0L
        val root = tempRoot()
        try {
            val kept = TimeshiftStore(
                storeDir(root),
                8_000L,
                1_000_000L,
                { Long.MAX_VALUE },
                minFreeBytes = 0L,
                clock = { now },
            )
            val payload = ByteArray(32) { 1 }
            repeat(3) { kept.append(payload, 4_000L, discontinuity = false) }
            val evicted = File(storeDir(root), "seg-00000000.ts")
            assertTrue(evicted.exists())
            assertFalse(kept.playlistFile.readText().contains("seg-00000000.ts"))
            assertEquals(96L, kept.totalBytes)
            now = TimeshiftStore.READER_GRACE_MS
            kept.append(payload, 4_000L, discontinuity = false)
            assertFalse(evicted.exists())
            assertTrue(kept.totalBytes <= 1_000_000L)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aRepairAtTheStartOfTheWindowKeepsThatPosition() {
        assertEquals(0L, TimeshiftNavigator.mediaStartMs(0L))
        assertEquals(15_000L, TimeshiftNavigator.mediaStartMs(15_000L))
        assertEquals(null, TimeshiftNavigator.mediaStartMs(null))
    }

    @Test
    fun evictionDoesNotReuseSegmentSequences() {
        val root = tempRoot()
        try {
            val kept = store(root, 60_000L, 100L)
            assertEquals(TimeshiftStore.Append.STORED, kept.append(ByteArray(80) { 1 }, 1_000L, false))
            assertEquals(TimeshiftStore.Append.STORED, kept.append(ByteArray(80) { 2 }, 1_000L, false))
            val text = kept.playlistFile.readText()
            assertTrue(text.contains("seg-00000001.ts"))
            assertFalse(text.contains("seg-00000000.ts"))
            assertEquals(1, kept.segmentCount())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anOversizedSegmentIsNotStoredShort() {
        var calls = 0
        val fits = collectSegment({
            calls++
            if (calls == 1) 4 else -1
        }, cap = 4)
        assertEquals(4, fits?.size)
        val overflow = collectSegment({ buffer -> buffer.size }, cap = 4)
        assertEquals(null, overflow)
    }

    @Test
    fun aRetainedWindowEndsSoThePlayerCanFinishIt() {
        val root = tempRoot()
        try {
            val kept = store(root, 60_000L, 1_000_000L)
            assertEquals(TimeshiftStore.Append.STORED, kept.append(byteArrayOf(1, 2, 3, 4), 4_000L, false))
            kept.finishRetainedWindow()
            val ended = kept.playlistFile.readText()
            assertTrue(ended.contains("#EXT-X-ENDLIST"))
            assertEquals(TimeshiftStore.Append.STORED, kept.append(byteArrayOf(5, 6, 7, 8), 4_000L, false))
            assertTrue(kept.playlistFile.readText().contains("#EXT-X-ENDLIST"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sweepLeavesAnOpenSessionAndDeletesItAfterClose() {
        val root = tempRoot()
        try {
            val inside = File(root, "timeshift").apply { mkdirs() }
            val budget = TimeshiftLimits.Budget(60_000L, 1_000_000L, localEnabled = true)
            val store = TimeshiftStore.create(inside, budget) { Long.MAX_VALUE }
            assertEquals(TimeshiftStore.Append.STORED, store.append(byteArrayOf(1, 2, 3, 4), 1_000L, false))
            val stale = File(inside, "sstale000000000001").apply { mkdirs() }
            File(stale, "seg-00000000.ts").writeBytes(byteArrayOf(9))
            TimeshiftStore.sweepStale(inside, keep = null)
            assertTrue(store.playlistFile.exists())
            assertFalse(stale.exists())
            val name = store.playlistFile.parentFile?.name
            store.deleteAll()
            assertFalse(store.playlistFile.exists())
            val leftover = File(inside, name!!)
            leftover.mkdirs()
            File(leftover, "seg-00000000.ts").writeBytes(byteArrayOf(1))
            TimeshiftStore.sweepStale(inside, keep = null)
            assertFalse(leftover.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun closingBetweenSegmentsDoesNotOpenTheNextOne() {
        val playlists = ArrayDeque<String>()
        playlists.add(shortLive(sequence = 1, segments = 1))
        playlists.add(shortLive(sequence = 2, segments = 2))
        var segmentOpens = 0
        val root = tempRoot()
        lateinit var loop: TimeshiftLoop
        try {
            loop = TimeshiftLoop(
                store(root, 60_000L, 1_000_000L),
                object : TimeshiftLoop.Fetcher {
                    override fun open(url: String): TimeshiftLoop.Opened {
                        if (url.endsWith(".ts")) {
                            segmentOpens++
                            if (segmentOpens == 2) loop.close()
                        }
                        return if (url.endsWith(".ts")) BytesOpened(ByteArray(32) { 1 })
                        else TextOpened(url, playlists.removeFirst())
                    }
                },
                TimeshiftSleeper {},
                Events(),
                maxSteps = 6,
            )
            loop.run("http://cdn.example/live.m3u8")
            assertEquals(2, segmentOpens)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun reading(
        position: Long,
        edge: Long,
        earliest: Long = 0L,
        playing: Boolean = true,
    ) = TimeshiftReading(
        seekable = true,
        earliestMs = earliest,
        liveEdgeMs = edge,
        positionMs = position,
        playWhenReady = playing,
        seeking = false,
        returningToLive = false,
    )

    private fun tempRoot(): File = java.nio.file.Files.createTempDirectory("mktv-timeshift").toFile()

    private fun store(root: File, durationMs: Long, bytes: Long, free: () -> Long = { Long.MAX_VALUE }): TimeshiftStore {
        return TimeshiftStore(File(root, "s0123456789abcdef"), durationMs, bytes, free, minFreeBytes = 0L)
    }

    private fun storeDir(root: File): File = File(root, "s0123456789abcdef")

    private fun directoryBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun shortLive(sequence: Long, segments: Int): String {
        val text = StringBuilder()
        text.append("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:").append(sequence).append('\n')
        repeat(segments) {
            text.append("#EXTINF:4.000,\na.ts\n")
        }
        return text.toString()
    }

    private fun playlistFetcher(playlists: ArrayDeque<String>): TimeshiftLoop.Fetcher {
        return fetcher { url ->
            if (url.endsWith(".ts")) BytesOpened(ByteArray(32) { 1 })
            else TextOpened(url, playlists.removeFirst())
        }
    }

    private fun fetcher(open: (String) -> TimeshiftLoop.Opened): TimeshiftLoop.Fetcher {
        return object : TimeshiftLoop.Fetcher {
            override fun open(url: String): TimeshiftLoop.Opened = open(url)
        }
    }

    private class Events : TimeshiftLoop.Listener {
        val steps = ArrayList<String>()
        var playlistText: String? = null
        override fun onReady(playlist: File) {
            steps += "ready"
            playlistText = runCatching { playlist.readText() }.getOrNull()
        }
        override fun onNeedDirect() { steps += "direct" }
        override fun onContinuityLost() { steps += "lost" }
        override fun onStorageDegraded() { steps += "storage" }
        override fun onGiveUp() { steps += "giveup" }
        override fun onStopped() { steps += "stopped" }
    }

    private class TextOpened(private val url: String, private val text: String) : TimeshiftLoop.Opened {
        override fun sniff(): TimeshiftLoop.Kind = TimeshiftLoop.Kind.HLS
        override fun playlistText(): String = text
        override fun finalUrl(): String = url
        override fun read(buffer: ByteArray): Int = -1
        override fun close() = Unit
    }

    /** Serves [bytes] once, then each later read is whatever [afterBytes] returns. */
    private class TsThen(
        private val bytes: ByteArray,
        private val afterBytes: () -> Int,
    ) : TimeshiftLoop.Opened {
        private var sent = false
        override fun sniff(): TimeshiftLoop.Kind = TimeshiftLoop.Kind.TS
        override fun playlistText(): String? = null
        override fun finalUrl(): String = "https://provider.example/live.ts"
        override fun read(buffer: ByteArray): Int {
            if (sent) return afterBytes()
            sent = true
            val n = minOf(buffer.size, bytes.size)
            System.arraycopy(bytes, 0, buffer, 0, n)
            return n
        }
        override fun close() = Unit
    }

    private class BytesOpened(private val bytes: ByteArray) : TimeshiftLoop.Opened {
        private var pos = 0
        override fun sniff(): TimeshiftLoop.Kind = TimeshiftLoop.Kind.TS
        override fun playlistText(): String? = null
        override fun finalUrl(): String = "https://cdn.example/a.ts"
        override fun read(buffer: ByteArray): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(buffer.size, bytes.size - pos)
            java.lang.System.arraycopy(bytes, pos, buffer, 0, n)
            pos += n
            return n
        }
        override fun close() = Unit
    }

    private class EndingTs : TimeshiftLoop.Opened {
        override fun sniff(): TimeshiftLoop.Kind = TimeshiftLoop.Kind.TS
        override fun playlistText(): String? = null
        override fun finalUrl(): String = "https://provider.example/live.ts"
        override fun read(buffer: ByteArray): Int = -1
        override fun close() = Unit
    }

    private fun throwOnSniff(): TimeshiftLoop.Opened = object : TimeshiftLoop.Opened {
        override fun sniff(): TimeshiftLoop.Kind = throw java.io.IOException("timeshift sniff")
        override fun playlistText(): String? = null
        override fun finalUrl(): String = "https://provider.example/live"
        override fun read(buffer: ByteArray): Int = -1
        override fun close() = Unit
    }

    private class UnknownOpened : TimeshiftLoop.Opened {
        override fun sniff(): TimeshiftLoop.Kind = TimeshiftLoop.Kind.UNKNOWN
        override fun playlistText(): String? = null
        override fun finalUrl(): String = "https://provider.example/live"
        override fun read(buffer: ByteArray): Int = -1
        override fun close() = Unit
    }

    /** One-connection redirect plus the playlist and segment it points at. No JDK-internal server. */
    private class LocalRedirectServer(playlist: ByteArray, segment: ByteArray) {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        private val worker = thread(name = "mktv-timeshift-test-http", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) {
                    try {
                        client.use { serve(it, playlist, segment) }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            worker.join(1_000)
        }

        private fun serve(client: Socket, playlist: ByteArray, segment: ByteArray) {
            client.soTimeout = 2_000
            val header = StringBuilder()
            val input = client.getInputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return
                header.append(b.toChar())
                if (header.endsWith("\r\n\r\n") || header.length > 8_000) break
            }
            val path = header.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1).orEmpty()
            val out = client.getOutputStream()
            when {
                path.startsWith("/go") -> {
                    val location = "http://127.0.0.1:$port/live/user/secretpass/1.m3u8"
                    val text = "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    out.write(text.toByteArray(Charsets.US_ASCII))
                }
                path.endsWith(".m3u8") -> writeOk(out, "application/vnd.apple.mpegurl", playlist)
                else -> writeOk(out, "video/mp2t", segment)
            }
            out.flush()
        }

        private fun writeOk(out: java.io.OutputStream, type: String, body: ByteArray) {
            val head = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
        }
    }

    /** One program, PMT at [pmtPid]. Payload-start PAT, no adaptation field. */
    private fun patPacket(pmtPid: Int): ByteArray {
        val packet = ByteArray(TsCutter.PACKET)
        packet[0] = 0x47
        packet[1] = 0x40
        packet[3] = 0x10
        packet[4] = 0x00
        packet[5] = 0x00
        packet[6] = 0xB0.toByte()
        packet[7] = 0x0D
        packet[8] = 0x00
        packet[9] = 0x01
        packet[10] = 0xC1.toByte()
        packet[13] = 0x00
        packet[14] = 0x01
        packet[15] = (0xE0 or ((pmtPid ushr 8) and 0x1F)).toByte()
        packet[16] = (pmtPid and 0xFF).toByte()
        return packet
    }

    private fun pmtPacket(pid: Int): ByteArray {
        val packet = ByteArray(TsCutter.PACKET)
        packet[0] = 0x47
        packet[1] = (0x40 or ((pid ushr 8) and 0x1F)).toByte()
        packet[2] = (pid and 0xFF).toByte()
        packet[3] = 0x10
        packet[5] = 0x02
        return packet
    }

    private fun pcrBurst(span90k: Long = 90_000L): ByteArray {
        val burst = ByteArray(TsCutter.PACKET * 2)
        System.arraycopy(tsPacket(randomAccess = false, pcr90k = 0L), 0, burst, 0, TsCutter.PACKET)
        System.arraycopy(tsPacket(randomAccess = true, pcr90k = span90k), 0, burst, TsCutter.PACKET, TsCutter.PACKET)
        return burst
    }

    private fun tsPacket(randomAccess: Boolean, pcr90k: Long? = null): ByteArray {
        val packet = ByteArray(TsCutter.PACKET)
        packet[0] = 0x47
        if (pcr90k == null && !randomAccess) {
            packet[3] = 0x10
            return packet
        }
        packet[3] = 0x30
        if (pcr90k == null) {
            packet[4] = 1
            packet[5] = 0x40
            return packet
        }
        packet[4] = 7
        var flags = 0x10
        if (randomAccess) flags = flags or 0x40
        packet[5] = flags.toByte()
        val base = pcr90k and 0x1FFFFFFFFL
        packet[6] = (base ushr 25).toByte()
        packet[7] = (base ushr 17).toByte()
        packet[8] = (base ushr 9).toByte()
        packet[9] = (base ushr 1).toByte()
        packet[10] = ((base and 1L) shl 7).toByte()
        return packet
    }

    private fun tsPacketWithPts(randomAccess: Boolean, pts90k: Long): ByteArray {
        val packet = ByteArray(TsCutter.PACKET)
        packet[0] = 0x47
        packet[1] = 0x40
        packet[3] = 0x30
        packet[4] = 1
        packet[5] = if (randomAccess) 0x40 else 0
        val pes = 6
        packet[pes + 2] = 1
        packet[pes + 3] = 0xE0.toByte()
        packet[pes + 6] = 0x80.toByte()
        packet[pes + 7] = 0x80.toByte()
        packet[pes + 8] = 5
        val v = pts90k and 0x1FFFFFFFFL
        val at = pes + 9
        packet[at] = (0x21 or ((v ushr 29) and 0x0E).toInt()).toByte()
        packet[at + 1] = (v ushr 22).toByte()
        packet[at + 2] = (((v ushr 14) and 0xFE) or 1L).toByte()
        packet[at + 3] = (v ushr 7).toByte()
        packet[at + 4] = (((v shl 1) and 0xFE) or 1L).toByte()
        return packet
    }
}
