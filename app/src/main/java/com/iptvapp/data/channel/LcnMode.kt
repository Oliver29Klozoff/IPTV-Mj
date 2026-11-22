package com.iptvapp.data.channel

import androidx.annotation.StringRes
import com.iptvapp.R

/** Settings → Channel Numbers: which number a channel shows (and answers to on the remote). */
enum class LcnMode(
    val id: String,
    @StringRes val titleResId: Int,
    @StringRes val descResId: Int,
) {
    /** Your own number when you gave the channel one, otherwise the provider's. The default. */
    CUSTOM_FIRST("custom_first", R.string.lcn_mode_custom_first, R.string.lcn_mode_custom_first_desc),
    /** 1, 2, 3… down whatever list is on screen. */
    SEQUENTIAL("sequential", R.string.lcn_mode_sequential, R.string.lcn_mode_sequential_desc),
    /** The provider's own numbers, ignoring yours. */
    PROVIDER_RAW("provider_raw", R.string.lcn_mode_provider_raw, R.string.lcn_mode_provider_raw_desc),
    /** No numbers on the lists (number entry still works with your numbers, then the provider's). */
    OFF("off", R.string.lcn_mode_off, R.string.lcn_mode_off_desc);

    companion object {
        fun fromId(id: String?): LcnMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CUSTOM_FIRST
    }
}
