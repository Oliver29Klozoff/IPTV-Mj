package com.iptvapp.data.local

/**
 * The Channel Tuning Engine: one choice in Settings that sets the four tuning knobs the player
 * already reads (Turbo Tune mode, channel-change wait, live reconnect pace, pre-warm on focus).
 * The knobs stay the saved state, so nothing in the player changes and an older backup or a
 * hand-set combination still works. A combination that matches no profile shows as Custom.
 *
 * Standard is exactly the out-of-the-box values, so an upgrade changes nobody's playback.
 * Turbo is opt-in because its Performance mode can open a spare provider connection, which
 * providers with a one- or two-connection limit may refuse.
 */
enum class TuningProfile(
    val id: String,
    val title: String,
    val description: String,
    val settings: TuningSettings,
) {
    TURBO(
        "turbo", "Turbo",
        "Fastest channel changes: prepares the next channel and reconnects quickly. May use a spare provider connection.",
        TuningSettings(preWarmOnFocus = true, turboTune = "performance", liveReconnectSpeed = "aggressive", channelZapDebounceMs = 150),
    ),
    STANDARD(
        "standard", "Standard (recommended)",
        "Quick channel changes without extra provider connections. Right for most homes.",
        TuningSettings(preWarmOnFocus = true, turboTune = "automatic", liveReconnectSpeed = "normal", channelZapDebounceMs = 0),
    ),
    LOW_BANDWIDTH(
        "low_bandwidth", "Low bandwidth",
        "Nothing is fetched ahead and a dropped channel retries gently. For slow or metered internet.",
        TuningSettings(preWarmOnFocus = false, turboTune = "off", liveReconnectSpeed = "patient", channelZapDebounceMs = 400),
    );

    companion object {
        /** The profile these saved knobs add up to, or null when they were set by hand (Custom). */
        fun matching(current: TuningSettings): TuningProfile? = entries.firstOrNull { it.settings == current }

        fun label(profile: TuningProfile?): String = profile?.title?.substringBefore(" (") ?: "Custom"
    }
}

data class TuningSettings(
    val preWarmOnFocus: Boolean,
    val turboTune: String,
    val liveReconnectSpeed: String,
    val channelZapDebounceMs: Int,
)

/** "Switch to a backup stream on freeze" reads On while either of its two saved halves is on. */
fun autoBackupStreamOn(reliableCopy: Boolean, backupFeed: Boolean): Boolean = reliableCopy || backupFeed
