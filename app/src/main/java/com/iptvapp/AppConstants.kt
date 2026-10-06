package com.iptvapp

object AppConstants {
    val DISCORD_WEBHOOK: String get() = BuildConfig.DISCORD_WEBHOOK

    // Fallback XMLTV guide for providers whose Xtream panel doesn't supply EPG data at all —
    // offered as a one-tap default rather than making someone go find a guide URL themselves,
    // and also tried automatically when a provider's own guide comes back empty (see
    // XtreamRepository.fetchXmltvEpg). ~6.5 MB gzipped, ~770 US cable/broadcast networks, ~4 days.
    const val DEFAULT_US_EPG_URL = "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz"

    // The previous default. It grew to ~520 MB of uncompressed XML (two months of 13k channels),
    // which can't download and parse inside XmltvFetcher's timeout, so it silently returned
    // nothing. A saved copy of it is treated as the current default.
    private const val LEGACY_US_EPG_URL = "https://iptv-epg.org/files/epg-us.xml"

    fun currentEpgUrl(url: String): String = if (url.trim() == LEGACY_US_EPG_URL) DEFAULT_US_EPG_URL else url
}
