package com.iptvapp.data.profile

import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.search.SearchHit
import com.iptvapp.util.KidsFilter
import kotlinx.coroutines.flow.first

/**
 * Kids profiles, for results that don't come through the repository's filtered lists (global
 * search, Home's automatic rows): what KidsFilter calls adult, by title or by the category it's
 * filed in. Category ids are per type (a movie category can share a live one's id).
 */
class KidsGuard private constructor(
    private val live: Set<String>,
    private val vod: Set<String>,
    private val series: Set<String>,
) {
    fun allows(hit: SearchHit): Boolean = when (hit) {
        is SearchHit.Channel -> !KidsFilter.isAdult(hit.title) && hit.channel?.categoryId !in live &&
            !KidsFilter.isAdult(hit.merged?.categoryName)
        is SearchHit.Program -> !KidsFilter.isAdult(hit.title) && !KidsFilter.isAdult(hit.channelName) &&
            hit.channel?.categoryId !in live && !KidsFilter.isAdult(hit.merged?.categoryName)
        is SearchHit.Movie -> !KidsFilter.isAdult(hit.title) && hit.vod?.categoryId !in vod &&
            !KidsFilter.isAdult(hit.merged?.categoryName)
        is SearchHit.Series -> !KidsFilter.isAdult(hit.title) && hit.series?.categoryId !in series &&
            !KidsFilter.isAdult(hit.merged?.categoryName)
        is SearchHit.Episode -> !KidsFilter.isAdult(hit.title) && allows(hit.series)
        is SearchHit.Recording -> !KidsFilter.isAdult(hit.title) && !KidsFilter.isAdult(hit.recording.channelName)
    }

    companion object {
        /** The guard for the active profile, or null when it isn't a Kids profile (nothing hidden). */
        suspend fun forActive(db: IptvDatabase, kids: Boolean): KidsGuard? {
            if (!kids) return null
            suspend fun adult(type: String) = db.categoryDao().getCategoriesByType(type).first()
                .filter { KidsFilter.isAdult(it.categoryName) }.map { it.categoryId }.toSet()
            return KidsGuard(adult("live"), adult("vod"), adult("series"))
        }
    }
}
