package com.iptvapp.data.api

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class XtreamUrlBuilder(
    private val serverUrl: String,
    private val username: String,
    private val password: String
) {
    fun apiUrl(): String = "${serverUrl.trimEnd('/')}/player_api.php"

    fun liveStreamUrl(streamId: Int, format: String = "m3u8"): String =
        "${serverUrl.trimEnd('/')}/live/$username/$password/$streamId.$format"

    fun vodStreamUrl(streamId: Int, containerExtension: String): String =
        "${serverUrl.trimEnd('/')}/movie/$username/$password/$streamId.$containerExtension"

    fun seriesStreamUrl(episodeId: String, containerExtension: String): String =
        "${serverUrl.trimEnd('/')}/series/$username/$password/$episodeId.$containerExtension"

    fun timeshiftUrl(streamId: Int, startTimestampSec: Long, durationMinutes: Int): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val startStr = sdf.format(Date(startTimestampSec * 1000L))
        return "${serverUrl.trimEnd('/')}/timeshift/$username/$password/$durationMinutes/$startStr/$streamId.ts"
    }

    companion object {
        fun isValidServerUrl(url: String): Boolean =
            url.startsWith("http://") || url.startsWith("https://")
    }
}
