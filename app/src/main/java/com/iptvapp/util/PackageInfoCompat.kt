package com.iptvapp.util

import android.content.pm.PackageInfo
import android.os.Build

/** PackageInfo.getLongVersionCode() requires API 28 — calling it directly on API 25-27 throws
 * NoSuchMethodError, which is an Error (not an Exception), so a plain try/catch(Exception) around
 * it does NOT catch it and crashes the app. Confirmed via a real crash on a Fire TV Stick 4K
 * running Android 7.1 (API 25) right after minSdk was lowered from 26 to 25. */
val PackageInfo.versionCodeCompat: Long
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else @Suppress("DEPRECATION") versionCode.toLong()
