package com.iptvapp.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** checkStreamHealth: the shared client is reused, and a cancelled check is never reported as a dead stream. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StreamHealthCheckTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: IptvDatabase
    private lateinit var repository: XtreamRepository

    /** What the fake server does with the next health ping. */
    @Volatile private var reply: () -> okhttp3.Response? = { null }
    private val sharedInterceptorCalls = AtomicInteger()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            sharedInterceptorCalls.incrementAndGet()
            reply() ?: okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(206).message("Partial").body("x".toResponseBody("video/mp2t".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("http://localhost/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(XtreamApiService::class.java)
        repository = XtreamRepository(api, db, PreferencesManager(context), client)
    }

    @After
    fun tearDown() = db.close()

    private fun status(code: Int): () -> okhttp3.Response = {
        okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("http://s.test/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("x")
            .body("".toResponseBody(null)).build()
    }

    @Test
    fun answersFromTheServerAreReadAsBefore() = runBlocking {
        assertTrue(repository.checkStreamHealth("http://s.test/live/1.ts"))
        reply = status(404)
        assertTrue(repository.checkStreamHealth("http://s.test/live/1.ts"))
        reply = status(503)
        assertFalse(repository.checkStreamHealth("http://s.test/live/1.ts"))
        reply = { throw IOException("connection reset") }
        assertFalse(repository.checkStreamHealth("http://s.test/live/1.ts"))
        // Every ping went through the shared, injected client.
        assertEquals(4, sharedInterceptorCalls.get())
    }

    @Test
    fun aCancelledCheckIsPassedOnNotReportedDead() = runBlocking {
        reply = { throw CancellationException("switched channel") }
        try {
            repository.checkStreamHealth("http://s.test/live/1.ts")
            fail("a cancelled check must not come back as a result")
        } catch (e: CancellationException) {
            assertEquals("switched channel", e.message)
        }
    }
}
