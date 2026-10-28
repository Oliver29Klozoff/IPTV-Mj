package com.iptvapp.tv

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LauncherArtworkTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val provider get() = LauncherArtworkProvider().also { it.attachInfo(context, null) }
    private fun art(path: String) = Uri.parse("content://${LauncherArtwork.authority(context)}/$path")

    @Test fun `public artwork passes through and unusable links fall back to the icon`() {
        val tmdb = "https://image.tmdb.org/t/p/w500/poster.jpg"
        assertEquals(tmdb, LauncherArtwork.poster(context, tmdb).toString())
        assertEquals(LauncherArtwork.fallback(context), LauncherArtwork.poster(context, null))
        assertEquals(LauncherArtwork.fallback(context), LauncherArtwork.poster(context, "file:///data/secret.png"))
        // Provider art never downloads while publishing: icon now, queued for fetchPending.
        assertEquals(LauncherArtwork.fallback(context), LauncherArtwork.poster(context, "http://127.0.0.1:9/u/p/logo.png"))
    }

    @Test fun `provider serves only hash-named cached posters read-only`() {
        val name = "a".repeat(64)
        LauncherArtwork.dir(context).mkdirs()
        File(LauncherArtwork.dir(context), name).writeBytes(byteArrayOf(1, 2, 3))
        assertNotNull(provider.openFile(art(name), "r"))
        for (bad in listOf("..%2Fshared_prefs%2Fx.xml", "abc", name.uppercase(), "$name/x")) {
            try { provider.openFile(art(bad), "r"); fail("served $bad") } catch (_: FileNotFoundException) {}
        }
        try { provider.openFile(art(name), "w"); fail("writable") } catch (_: SecurityException) {}
    }
}
