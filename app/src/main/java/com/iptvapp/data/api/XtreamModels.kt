package com.iptvapp.data.api

import com.google.gson.annotations.SerializedName

data class XtreamAuthResponse(
    @SerializedName("user_info") val userInfo: UserInfo,
    @SerializedName("server_info") val serverInfo: ServerInfo
)

data class UserInfo(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
    @SerializedName("status") val status: String,
    @SerializedName("exp_date") val expDate: String?,
    @SerializedName("is_trial") val isTrial: String,
    @SerializedName("active_cons") val activeCons: String,
    @SerializedName("max_connections") val maxConnections: String,
    @SerializedName("allowed_output_formats") val allowedFormats: List<String>
)

data class ServerInfo(
    @SerializedName("url") val url: String,
    @SerializedName("port") val port: String,
    @SerializedName("https_port") val httpsPort: String?,
    @SerializedName("server_protocol") val protocol: String,
    @SerializedName("rtmp_port") val rtmpPort: String?,
    @SerializedName("timezone") val timezone: String,
    @SerializedName("timestamp_now") val timestampNow: Long
)

data class LiveStream(
    @SerializedName("num") val num: Int,
    @SerializedName("name") val name: String,
    @SerializedName("stream_type") val streamType: String,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("epg_channel_id") val epgChannelId: String?,
    @SerializedName("added") val added: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("tv_archive") val tvArchive: Int,
    @SerializedName("direct_source") val directSource: String?
)

data class Category(
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("category_name") val categoryName: String,
    @SerializedName("parent_id") val parentId: Int
)

data class VodStream(
    @SerializedName("num") val num: Int,
    @SerializedName("name") val name: String,
    @SerializedName("stream_type") val streamType: String,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("rating_5based") val rating5Based: Double?,
    @SerializedName("added") val added: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("container_extension") val containerExtension: String,
    @SerializedName("custom_sid") val customSid: String?,
    @SerializedName("direct_source") val directSource: String?
)

data class VodInfo(
    @SerializedName("info") val info: VodInfoDetail?,
    @SerializedName("movie_data") val movieData: VodStream?
)

data class VodInfoDetail(
    @SerializedName("tmdb_id") val tmdbId: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("cover_big") val coverBig: String?,
    @SerializedName("movie_image") val movieImage: String?,
    @SerializedName("releasedate") val releaseDate: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("actors") val actors: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("country") val country: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("duration") val duration: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("backdrop_path") val backdropPath: List<String>?,
    // A bare YouTube video ID (e.g. "dQw4w9WgXcQ"), not a full URL — most Xtream panels that
    // populate this at all send just the ID. Empty string or absent when the provider has no
    // trailer for this title, which is common.
    @SerializedName("youtube_trailer") val youtubeTrailer: String?
)

data class Series(
    @SerializedName("num") val num: Int,
    @SerializedName("name") val name: String,
    @SerializedName("series_id") val seriesId: Int,
    @SerializedName("cover") val cover: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("releaseDate") val releaseDate: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("rating_5based") val rating5Based: Double?,
    @SerializedName("category_id") val categoryId: String?
)

data class SeriesInfo(
    @SerializedName("info") val info: SeriesInfoDetail?,
    @SerializedName("episodes") val episodes: Map<String, List<Episode>>?,
    @SerializedName("seasons") val seasons: List<Season>?
)

data class SeriesInfoDetail(
    @SerializedName("name") val name: String?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("youtube_trailer") val youtubeTrailer: String?
)

data class Season(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("season_number") val seasonNumber: Int?
)

data class Episode(
    @SerializedName("id") val id: String,
    @SerializedName("episode_num") val episodeNum: Int,
    @SerializedName("title") val title: String,
    @SerializedName("container_extension") val containerExtension: String,
    @SerializedName("added") val added: String?,
    @SerializedName("season") val season: Int,
    @SerializedName("direct_source") val directSource: String?
)

data class EpgResponse(
    @SerializedName("epg_listings") val epgListings: List<EpgEntry>
)

data class EpgEntry(
    @SerializedName("id") val id: String,
    @SerializedName("epg_id") val epgId: String,
    @SerializedName("title") val title: String,
    @SerializedName("lang") val lang: String,
    @SerializedName("start") val start: String,
    @SerializedName("end") val end: String,
    @SerializedName("description") val description: String,
    @SerializedName("channel_id") val channelId: String,
    @SerializedName("start_timestamp") val startTimestamp: Long,
    @SerializedName("stop_timestamp") val stopTimestamp: Long,
    @SerializedName("now_playing") val nowPlaying: Int,
    @SerializedName("has_archive") val hasArchive: Int
)
