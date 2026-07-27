package com.iptvapp.util

// Most Xtream resellers bake a quality/codec tag directly into the channel name itself
// ("ESPN HD", "CNN FHD", "Discovery 4K", "BBC One UK SD") since the API has no separate
// resolution field at all — this is a label of what the provider CLAIMS in the name, not a
// verified measurement of the actual stream (that would require opening every channel's real
// video track, which is expensive and can burn a limited concurrent-connection slot per probe).
// Good enough for its actual purpose: telling apart the same channel offered by two different
// providers/qualities in a list, which is exactly the case where their name strings differ.
object ChannelQualityTag {
    // Checked in priority order so "4K"/"UHD" wins over a channel that also happens to mention
    // "HD" elsewhere in its name (rare, but seen in the wild as "Channel 4K HD Backup").
    private val UHD_4K = Regex("""\b(4K|UHD)\b""", RegexOption.IGNORE_CASE)
    private val FHD = Regex("""\b(FHD|FULL\s?HD|1080P?)\b""", RegexOption.IGNORE_CASE)
    private val HD = Regex("""\bHD\b""", RegexOption.IGNORE_CASE)
    private val SD = Regex("""\bSD\b""", RegexOption.IGNORE_CASE)

    fun labelFor(channelName: String): String? = when {
        UHD_4K.containsMatchIn(channelName) -> "4K"
        FHD.containsMatchIn(channelName) -> "FHD"
        HD.containsMatchIn(channelName) -> "HD"
        SD.containsMatchIn(channelName) -> "SD"
        else -> null
    }
}
