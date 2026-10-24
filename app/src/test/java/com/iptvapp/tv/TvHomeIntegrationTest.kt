package com.iptvapp.tv

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TvHomeIntegrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: IptvDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close() }

    @Test fun `artwork rejects private URLs and query credentials`() {
        assertNull(LauncherMetadata.artwork("https://provider.test/user/password/logo.png"))
        assertNull(LauncherMetadata.artwork("https://user:password@image.tmdb.org/a.jpg"))
        assertNull(LauncherMetadata.artwork("https://image.tmdb.org/a.jpg?token=secret"))
        assertEquals("https://image.tmdb.org/t/p/w500/a.jpg", LauncherMetadata.artwork("https://image.tmdb.org/t/p/w500/a.jpg"))
    }
    @Test fun `recording card rejects path substitution and foreign install`() {
        val key = ByteArray(32) { 1 }
        val identity = LauncherLink.recordingIdentity(8, "/private/recording.ts", 42)
        val token = LauncherLink.token(key, identity)
        val link = LauncherLink.buildRecording(8, token)
        assertEquals(LauncherLink.Parsed.Recording(8, token), LauncherLink.parse(LauncherLink.ACTION_VIEW, link))
        assertFalse(link.contains("private"))
        assertFalse(LauncherLink.verifyIdentity(key, LauncherLink.recordingIdentity(8, "/other.ts", 42), token))
        assertFalse(LauncherLink.verifyIdentity(ByteArray(32), identity, token))
        assertEquals(LauncherLink.Parsed.Invalid, LauncherLink.parse(LauncherLink.ACTION_VIEW, "$link?url=https://evil"))
    }
    @Test fun `same media number on two providers has different launcher identity`() {
        val key = ByteArray(32)
        assertNotEquals(LauncherLink.token(key, LauncherLink.movieIdentity("https://a", "u", 9)),
            LauncherLink.token(key, LauncherLink.movieIdentity("https://b", "u", 9)))
    }
    @Test fun `next episode uses supplied catalog including season transition`() {
        val rows = listOf(LauncherEpisodeCache.Ref(2, 1, "Next"), LauncherEpisodeCache.Ref(1, 8, "Last"))
        assertEquals(2, LauncherEpisodeCache.next(rows, 1, 8)?.season)
        assertNull(LauncherEpisodeCache.next(rows, 2, 1))
        assertNull(LauncherEpisodeCache.next(emptyList(), 1, 1))
    }
    @Test fun `recent completion query excludes unfinished latest episode`() = runBlocking {
        db.episodeWatchedDao().saveProgress(1, 1, 1, 950, 1000)
        assertEquals(1, db.episodeWatchedDao().launcherCompleted().size)
        db.episodeWatchedDao().saveProgress(1, 1, 2, 200, 1000)
        assertTrue(db.episodeWatchedDao().launcherCompleted().isEmpty())
    }
    @Test fun `launcher queries bound large catalogs and omit hidden channels`() = runBlocking {
        db.channelDao().upsertChannels((1..1000).map { ChannelEntity(it, "C$it", null, null, null, 0, num = it, isFavorite = true, isHidden = it == 1) })
        val rows = db.channelDao().launcherFavorites()
        assertEquals(20, rows.size)
        assertFalse(rows.any { it.streamId == 1 })
    }
    @Test fun `unavailable recordings cannot resolve`() {
        val rec = RecordingEntity(streamId = 1, channelName = "Test", scheduledStartMs = 1, durationMs = 1, outputPath = "/does-not-exist.ts", status = "DONE")
        assertFalse(TvHomeIntegration.available(context, rec))
        assertFalse(TvHomeIntegration.available(context, rec.copy(status = "RECORDING")))
    }
    @Test fun `disabled feature does not change watch history`() = runBlocking {
        db.episodeWatchedDao().saveProgress(1, 2, 3, 400, 1000)
        TvHomeIntegration.setEnabled(context, false)
        assertFalse(TvHomeIntegration.enabled(context))
        assertEquals(400L, db.episodeWatchedDao().getWatchedMs(1, 2, 3))
    }

    @Test fun `movie progress is exact and completion removes card without deleting history`() = runBlocking {
        val prefs = PreferencesManager(context)
        prefs.saveCredentials("https://fixture.invalid", "fixture", "fixture")
        db.vodDao().upsertVod(listOf(VodEntity(42, "Movie", null, null, null, "mp4", null,
            watchedMs = 123000, durationMs = 600000, lastWatchedAt = 1)))
        val item = WatchNextPublisher.items(context, db, prefs).single()
        assertEquals(123000L, item.positionMs)
        assertEquals(600000L, item.durationMs)
        db.vodDao().updateWatchProgress(42, 590000, 600000)
        assertTrue(WatchNextPublisher.items(context, db, prefs).isEmpty())
        assertEquals(590000L, db.vodDao().getWatchedMs(42))
    }

    @Test fun `completion advances only to cached verified episode and replay selects older episode`() = runBlocking {
        val prefs = PreferencesManager(context)
        prefs.saveCredentials("https://episodes.invalid", "fixture", "fixture")
        val credentials = prefs.credentials.first()
        db.seriesDao().upsertSeries(listOf(SeriesEntity(55, "Series", null, null, null, null, null, lastWatchedAt = 123)))
        db.episodeWatchedDao().saveProgress(55, 1, 1, 980, 1000)
        assertTrue(WatchNextPublisher.items(context, db, prefs).isEmpty()) // No invented S1E2.
        val episode = com.iptvapp.data.api.Episode("provider-episode-2", 2, "Second", "mp4", null, 1, null)
        prefs.cacheLauncherEpisodes(credentials, 55, com.iptvapp.data.api.SeriesInfo(null, mapOf("1" to listOf(episode)), null))
        val next = WatchNextPublisher.items(context, db, prefs).single()
        assertTrue(next.next)
        assertEquals(2, next.episode)
        db.episodeWatchedDao().saveProgress(55, 1, 2, 300, 1000)
        prefs.rememberLauncherEpisode(credentials, 55, 1, 2)
        assertFalse(WatchNextPublisher.items(context, db, prefs).single().next)
        db.episodeWatchedDao().saveProgress(55, 1, 1, 100, 1000)
        prefs.rememberLauncherEpisode(credentials, 55, 1, 1)
        val replay = WatchNextPublisher.items(context, db, prefs).single()
        assertEquals(1, replay.episode)
        assertEquals(100L, replay.positionMs)
        assertEquals(next.key, replay.key)
        assertEquals(replay, WatchNextPublisher.items(context, db, PreferencesManager(context)).single())
    }
}
