package com.iptvapp.ui.epg

import android.content.Context
import com.iptvapp.data.epg.EpgGuideChannel
import com.iptvapp.data.epg.EpgIssueKind
import com.iptvapp.data.epg.EpgSamples
import com.iptvapp.data.epg.MatchState
import java.text.DateFormat
import java.util.Date

/** The words EPG Health's screens share. */
internal object EpgUiText {

    fun state(state: MatchState): String = when (state) {
        MatchState.EXACT -> "Exact guide ID"
        MatchState.HIGH -> "Likely match"
        MatchState.POSSIBLE -> "Possible match — confirm"
        MatchState.AMBIGUOUS -> "Several feeds — pick one"
        MatchState.NONE -> "No match"
    }

    fun kind(kind: EpgIssueKind): String = when (kind) {
        EpgIssueKind.MISSING_FIXABLE -> "No listings — a match was found"
        EpgIssueKind.MISSING_UNAVAILABLE -> "No listings available"
        EpgIssueKind.STALE -> "Stale listings"
        EpgIssueKind.SUSPECT -> "Possibly the wrong guide channel"
        EpgIssueKind.OFFSET_HINT -> "Times may be off"
        EpgIssueKind.CORRECTED -> "Correction saved"
        EpgIssueKind.CORRECTION_BROKEN -> "Correction needs attention"
        EpgIssueKind.OK -> "Has listings"
    }

    /** "Now 8:00 PM The Walking Dead · 9:00 PM Talking Dead", or "No upcoming listings". */
    fun listings(context: Context, guide: EpgGuideChannel): String {
        val next = EpgSamples.decode(guide.sample)
        if (next.isEmpty()) {
            return if (guide.programCount > 0 && guide.lastStop > System.currentTimeMillis() / 1000) "${guide.programCount} listings" else "No upcoming listings"
        }
        val time = android.text.format.DateFormat.getTimeFormat(context)
        val nowSec = System.currentTimeMillis() / 1000
        return next.joinToString("\n") { l ->
            val at = if (l.startSec <= nowSec && l.stopSec > nowSec) "Now" else time.format(Date(l.startSec * 1000))
            "$at  ${l.title}"
        }
    }

    fun dateTime(ms: Long): String =
        if (ms <= 0) "never" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    fun ago(ms: Long): String {
        if (ms <= 0) return "never"
        val min = (System.currentTimeMillis() - ms) / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} days ago"
        }
    }
}
