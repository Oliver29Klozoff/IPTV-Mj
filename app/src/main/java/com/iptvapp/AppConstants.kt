package com.iptvapp

object AppConstants {
    val DISCORD_WEBHOOK: String get() = BuildConfig.DISCORD_WEBHOOK

    // Fallback XMLTV guide for providers whose Xtream panel doesn't supply EPG data at all —
    // offered as a one-tap default rather than making someone go find a guide URL themselves.
    const val DEFAULT_US_EPG_URL = "https://iptv-epg.org/files/epg-us.xml"
}
