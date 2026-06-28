package com.iptvapp.di

import android.content.Context
import androidx.room.Room
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.local.IptvDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
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
            IptvDatabase.MIGRATION_6_7
        ).build()

    @Provides fun provideChannelDao(db: IptvDatabase) = db.channelDao()
    @Provides fun provideCategoryDao(db: IptvDatabase) = db.categoryDao()
    @Provides fun provideVodDao(db: IptvDatabase) = db.vodDao()
    @Provides fun provideSeriesDao(db: IptvDatabase) = db.seriesDao()
    @Provides fun provideEpgDao(db: IptvDatabase) = db.epgDao()
}