package com.iptvapp.tv

import java.net.URI

/** Only public, credential-free artwork is passed to the system launcher. */
object LauncherMetadata {
    fun artwork(value: String?): String? = runCatching {
        val uri = URI(value ?: return null)
        if (uri.scheme != "https" || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        // Provider artwork may embed credentials in path segments. Allow known public artwork hosts only.
        if (uri.host !in setOf("image.tmdb.org", "images.tmdb.org", "artworks.thetvdb.com")) return null
        value
    }.getOrNull()
}
