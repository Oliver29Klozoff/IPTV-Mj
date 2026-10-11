package com.iptvapp.di

import android.content.Context
import com.iptvapp.data.local.IptvDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The app's one database (AppModule's singleton) for code Hilt can't inject — the home-screen
 * widget's RemoteViewsService and its configure screen. They used to build their own Room instance
 * on every refresh, a second database object beside the app's. Never close what this returns.
 */
object AppDatabaseAccess {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun database(): IptvDatabase
    }

    fun get(context: Context): IptvDatabase =
        EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).database()
}
