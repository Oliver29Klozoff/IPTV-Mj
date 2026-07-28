package com.iptvapp.di

import android.content.Context
import androidx.room.Room
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.util.DoHDns
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // Process-lifetime scope backing the cached DoH prefs below — this OkHttpClient (and the
    // Dns it holds) is a singleton that outlives every Activity, so there's no Activity/
    // ViewModel scope to tie this to.
    private val dohPrefsScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    @Provides
    @Singleton
    fun provideOkHttpClient(prefs: PreferencesManager): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
        // Dns.lookup() runs on an OkHttp dispatcher thread for every single request (EPG
        // polls, VOD/series sync, Trakt, update checks, ...). Calling runBlocking on a
        // DataStore read here — once was twice, sequentially — blocked that thread on every
        // lookup; under concurrent load (e.g. EPG refresh racing a VOD/series fetch) that
        // could exhaust OkHttp's limited dispatcher pool and stall unrelated requests in a
        // way that looks like a network problem but isn't. Instead, keep the prefs mirrored
        // into plain fields via a background collector, so lookup() itself never suspends.
        var dohEnabledCached = false
        var dohProviderCached = "cloudflare"
        dohPrefsScope.launch { prefs.dohEnabled.collect { dohEnabledCached = it } }
        dohPrefsScope.launch { prefs.dohProvider.collect { dohProviderCached = it } }
        // One long-lived instance (not one per lookup) so its address cache and connection
        // pool to the DoH resolver actually persist across requests — see DoHDns's kdoc.
        val dohDns = DoHDns()
        val dns = object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                return if (dohEnabledCached) dohDns.lookup(hostname, dohProviderCached) else Dns.SYSTEM.lookup(hostname)
            }
        }
        // Xtream panels are commonly fronted by Cloudflare (or similar WAFs) configured to
        // block/rate-limit known HTTP-library user agents like OkHttp's default
        // ("okhttp/4.x") to deter scraping/reselling tools — a real player app never sends
        // that. Without this, some providers 401 or instantly 429 even brand-new, valid
        // credentials, while any legitimate player (VLC, TiviMate, ...) connects fine with
        // the exact same login.
        //
        // Previously spoofed a Chrome browser UA here, which backfired on panels that do the
        // OPPOSITE of the above: whitelist known IPTV-PLAYER user agents and reject generic
        // browser strings as bot/scraper traffic (confirmed via two providers 401ing on the
        // plain login check itself — one that used to work here, one confirmed working in
        // other player apps with the same credentials). VLC's real UA is the most broadly
        // whitelisted string across Xtream panels since it's the reference player most of
        // them test against — matches what a real player app actually sends, both for panels
        // wanting "not a bare HTTP client" and panels wanting "a known player specifically".
        val userAgentInterceptor = okhttp3.Interceptor { chain ->
            val original = chain.request()
            // Playback requests (OkHttpDataSource.Factory.setUserAgent in PlayerActivity)
            // already set their own User-Agent on this same shared client — don't clobber it,
            // only fill in the header when nothing set one already (the player_api.php JSON
            // calls, which had no User-Agent at all before this).
            val request = if (original.header("User-Agent") != null) {
                original
            } else {
                original.newBuilder()
                    .header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
                    .build()
            }
            chain.proceed(request)
        }
        return OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(logging)
            .dns(dns)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideXtreamApiService(retrofit: Retrofit): XtreamApiService =
        retrofit.create(XtreamApiService::class.java)

    @Provides
    @Singleton
    fun provideTraktApiService(okHttpClient: OkHttpClient): com.iptvapp.data.api.TraktApiService {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.trakt.tv/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(com.iptvapp.data.api.TraktApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideTraktProxyApiService(okHttpClient: OkHttpClient): com.iptvapp.data.api.TraktProxyApiService {
        // Points at the user's own Cloudflare Worker (cloudflare/trakt-proxy-worker.js), which
        // holds the Trakt client_secret server-side. Falls back to a placeholder base URL when
        // unconfigured — calls will simply fail until TRAKT_PROXY_URL is set in local.properties.
        val base = com.iptvapp.BuildConfig.TRAKT_PROXY_URL.ifBlank { "https://unconfigured.invalid/" }
        val normalizedBase = if (base.endsWith("/")) base else "$base/"
        val retrofit = Retrofit.Builder()
            .baseUrl(normalizedBase)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(com.iptvapp.data.api.TraktProxyApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): IptvDatabase =
        Room.databaseBuilder(
            context,
            IptvDatabase::class.java,
            IptvDatabase.DATABASE_NAME
        ).addMigrations(*IptvDatabase.ALL_MIGRATIONS).build()

    @Provides fun provideChannelDao(db: IptvDatabase) = db.channelDao()
    @Provides fun provideCategoryDao(db: IptvDatabase) = db.categoryDao()
    @Provides fun provideVodDao(db: IptvDatabase) = db.vodDao()
    @Provides fun provideSeriesDao(db: IptvDatabase) = db.seriesDao()
    @Provides fun provideReliabilityDao(db: IptvDatabase) = db.reliabilityDao()
    @Provides fun provideEpgDao(db: IptvDatabase) = db.epgDao()
    @Provides fun provideRecordingDao(db: IptvDatabase) = db.recordingDao()
    @Provides fun provideEpisodeWatchedDao(db: IptvDatabase) = db.episodeWatchedDao()
    @Provides fun provideMergedChannelDao(db: IptvDatabase) = db.mergedChannelDao()
    @Provides fun provideFavoriteFolderDao(db: IptvDatabase) = db.favoriteFolderDao()
    @Provides fun provideMergedVodDao(db: IptvDatabase) = db.mergedVodDao()
    @Provides fun provideMergedSeriesDao(db: IptvDatabase) = db.mergedSeriesDao()
}