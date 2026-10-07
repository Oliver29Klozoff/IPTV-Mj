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

    /** The start goes in as wall-clock time in the panel's own time zone ([serverTimeZone], from
     * player_api's server_info.timezone) — that's how Xtream panels read it. Formatting it in UTC
     * started every replay hours away from the show picked in the guide. */
    fun timeshiftUrl(
        streamId: Int,
        startTimestampSec: Long,
        durationMinutes: Int,
        serverTimeZone: TimeZone = TimeZone.getTimeZone("UTC")
    ): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        sdf.timeZone = serverTimeZone
        val startStr = sdf.format(Date(startTimestampSec * 1000L))
        return "${serverUrl.trimEnd('/')}/timeshift/$username/$password/$durationMinutes/$startStr/$streamId.ts"
    }

    companion object {
        fun isValidServerUrl(url: String): Boolean =
            url.startsWith("http://") || url.startsWith("https://")
    }
}
