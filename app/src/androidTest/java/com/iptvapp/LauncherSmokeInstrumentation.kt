package com.iptvapp

import android.app.Instrumentation
import android.os.Bundle
import androidx.tvprovider.media.tv.TvContractCompat
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.VodEntity
import com.iptvapp.service.RecordingDeps
import com.iptvapp.tv.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking

/** Run only on a fresh emulator: leaves credential-free fixture cards for launcher UI inspection. */
class LauncherSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            runBlocking {
                check(TvHomeIntegration.supported(targetContext))
                val deps = EntryPointAccessors.fromApplication(targetContext, RecordingDeps::class.java)
                val db = deps.database()
                check(db.channelDao().getCount() == 0) { "Requires an empty isolated emulator" }
                val prefs = PreferencesManager(targetContext)
                prefs.saveCredentials("http://127.0.0.1:18770", "fixture", "fixture")
                db.channelDao().upsertChannels(listOf(ChannelEntity(991, "MKTV Launcher Test Channel", null, null, null, 0,
                    num = 1, isFavorite = true, lastWatched = System.currentTimeMillis(), streamUrl = "http://127.0.0.1:18770/fixture.ts")))
                db.vodDao().upsertVod(listOf(VodEntity(992, "MKTV Resume Test Movie", null, null, null, "mp4", null,
                    watchedMs = 3000, durationMs = 30000, lastWatchedAt = System.currentTimeMillis())))
                db.seriesDao().upsertSeries(listOf(com.iptvapp.data.local.entities.SeriesEntity(993, "MKTV Next Episode Test", null, null, null, null, null, lastWatchedAt = System.currentTimeMillis())))
                db.episodeWatchedDao().saveProgress(993, 1, 1, 29900, 30000)
                deps.repository().fetchSeriesInfo(993)
                val recording = java.io.File(targetContext.filesDir, "launcher-fixture.mp4")
                context.assets.open("launcher-fixture.mp4").use { input -> recording.outputStream().use { input.copyTo(it) } }
                db.recordingDao().insert(com.iptvapp.data.local.entities.RecordingEntity(streamId = 991,
                    channelName = "MKTV Recording Test", scheduledStartMs = System.currentTimeMillis(), durationMs = 30000,
                    outputPath = recording.absolutePath, status = "DONE"))
                TvHomeIntegration.setEnabled(targetContext, true)
                repeat(3) { TvHomeIntegration.sync(targetContext, db, prefs, deps.repository()) }
                fun count(uri: android.net.Uri): Int = targetContext.contentResolver.query(uri, arrayOf("_id"), null, null, null)!!.use { it.count }
                check(count(TvContractCompat.Channels.CONTENT_URI) == 3)
                check(count(TvContractCompat.PreviewPrograms.CONTENT_URI) == 3)
                check(count(TvContractCompat.WatchNextPrograms.CONTENT_URI) == 2)
                TvHomeIntegration.setEnabled(targetContext, false)
                TvHomeIntegration.sync(targetContext, db, prefs, deps.repository())
                check(count(TvContractCompat.PreviewPrograms.CONTENT_URI) == 0)
                check(count(TvContractCompat.WatchNextPrograms.CONTENT_URI) == 0)
                check(db.vodDao().getWatchedMs(992) == 3000L)
                TvHomeIntegration.setEnabled(targetContext, true)
                TvHomeIntegration.sync(targetContext, db, prefs, deps.repository())
                result.putString("stream", "Launcher provider: repeated sync, disable cleanup, history preservation PASS. UI visibility must be checked separately.\n")
            }
            finish(android.app.Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("stream", "FAIL: ${failure.javaClass.simpleName}: ${failure.message}\n")
            finish(android.app.Activity.RESULT_CANCELED, result)
        }
    }
}
