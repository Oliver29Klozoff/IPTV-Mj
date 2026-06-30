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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    @Provides
    @Singleton
    fun provideOkHttpClient(prefs: PreferencesManager): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
        // Dynamic DoH DNS — checks pref on every lookup so toggling takes effect without restart
        val dns = object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val enabled = runBlocking { prefs.dohEnabled.first() }
                val provider = runBlocking { prefs.dohProvider.first() }
                return if (enabled) DoHDns(provider).lookup(hostname) else Dns.SYSTEM.lookup(hostname)
            }
        }
        return OkHttpClient.Builder()
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
    fun provideDatabase(@ApplicationContext context: Context): IptvDatabase =
        Room.databaseBuilder(
            context,
            IptvDatabase::class.java,
            IptvDatabase.DATABASE_NAME
        ).addMigrations(
            IptvDatabase.MIGRATION_2_3,
            IptvDatabase.MIGRATION_3_4,
            IptvDatabase.MIGRATION_4_5,
            IptvDatabase.MIGRATION_5_6,
            IptvDatabase.MIGRATION_6_7,
            IptvDatabase.MIGRATION_7_8,
            IptvDatabase.MIGRATION_8_9,
            IptvDatabase.MIGRATION_9_10
        ).build()

    @Provides fun provideChannelDao(db: IptvDatabase) = db.channelDao()
    @Provides fun provideCategoryDao(db: IptvDatabase) = db.categoryDao()
    @Provides fun provideVodDao(db: IptvDatabase) = db.vodDao()
    @Provides fun provideSeriesDao(db: IptvDatabase) = db.seriesDao()
    @Provides fun provideEpgDao(db: IptvDatabase) = db.epgDao()
    @Provides fun provideRecordingDao(db: IptvDatabase) = db.recordingDao()
}