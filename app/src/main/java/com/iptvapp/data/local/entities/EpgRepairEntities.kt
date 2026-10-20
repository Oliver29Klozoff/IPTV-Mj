package com.iptvapp.data.local.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * EPG repair (Settings › EPG Health). Three small tables beside the guide itself; guide rows stay
 * in epg_entries, which every screen already reads.
 *
 * Nothing here holds an address, a username or a password: a source is a salted hash of its
 * address plus a plain label ("Provider guide", "Custom guide (epg.example.com)"), and a provider
 * is a salted hash of its server and username.
 */

/** How a guide source did at its last refresh, per provider slot (the same public guide can serve several). */
@Entity(tableName = "epg_guide_sources", primaryKeys = ["serverIndex", "sourceKey"])
data class EpgGuideSourceEntity(
    val serverIndex: Int,
    val sourceKey: String,
    /** PROVIDER, CUSTOM or PUBLIC: see EpgSourceKind. */
    val kind: String,
    val label: String,
    /** Its place in the provider's source list: lower is preferred. */
    val rank: Int,
    val lastAttemptAt: Long,
    /** 0 = never succeeded. */
    val lastSuccessAt: Long,
    /** OK, FAILED or EMPTY: see EpgSourceResult. */
    val lastResult: String,
    val channelCount: Int,
    val programCount: Int,
    /** Listings dropped for a missing title or an end before the start. */
    val invalidProgramCount: Int,
    /** Listings whose times carried no time zone (read as UTC). */
    val zonelessProgramCount: Int,
    /** End of the latest listing (epoch seconds). */
    val horizonEnd: Long,
)

/**
 * The channels a guide source listed at its last successful refresh, with a few upcoming
 * listings each, so a channel can be matched to one by hand without downloading the guide again.
 */
@Entity(
    tableName = "epg_guide_channels",
    primaryKeys = ["serverIndex", "sourceKey", "xmltvId"],
)
data class EpgGuideChannelEntity(
    val serverIndex: Int,
    val sourceKey: String,
    val xmltvId: String,
    val displayName: String,
    val programCount: Int,
    val lastStop: Long,
    /** Up to three upcoming listings: see EpgSamples. */
    val sample: String,
)

/**
 * The user's correction for one channel on one provider: which guide channel it takes listings
 * from (MAP), and/or by how much its listings are moved (offsetMinutes, with AUTO matching).
 * Deleting the row restores automatic matching.
 */
@Entity(
    tableName = "epg_overrides",
    primaryKeys = ["serverIndex", "streamId"],
    indices = [Index(value = ["serverIndex", "sourceKey"])]
)
data class EpgOverrideEntity(
    val serverIndex: Int,
    val streamId: Int,
    /** The provider the slot held when this was saved; a different one there leaves it unused. */
    val providerKey: String,
    /** MAP or AUTO: see EpgOverrideMode. */
    val mode: String,
    val sourceKey: String?,
    val xmltvId: String?,
    /** For showing the correction when the channel or guide is gone. */
    val channelName: String,
    val guideName: String?,
    /** Minutes added to this channel's listings (whole quarter hours, ±12 h). */
    val offsetMinutes: Int,
    val createdAt: Long,
    val updatedAt: Long,
)
