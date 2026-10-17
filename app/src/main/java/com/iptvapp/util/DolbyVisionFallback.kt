package com.iptvapp.util

/**
 * When to play the base layer of a Dolby Vision stream instead of Dolby Vision itself, and with
 * which decoder.
 *
 * Most Dolby Vision streams carry an ordinary picture underneath: HDR10 (profiles 7 and 8.1),
 * HLG (8.4), SDR (4, 8.2), or AVC / AV1 (9, 10). A screen that cannot show Dolby Vision gets that
 * base layer, which is what an HDR10 TV should show. Profile 5 has no such layer: decoded as
 * plain HEVC it comes out purple and green, so it is never sent down this path.
 */
object DolbyVisionFallback {
    // MediaCodecInfo.CodecProfileLevel values, as MediaCodecUtil.getCodecProfileAndLevel reports them.
    const val PROFILE_4 = 16      // DolbyVisionProfileDvheDtr
    const val PROFILE_5 = 32      // DolbyVisionProfileDvheStn: no compatible base layer
    const val PROFILE_7 = 128     // DolbyVisionProfileDvheDtb
    const val PROFILE_8 = 256     // DolbyVisionProfileDvheSt
    const val PROFILE_9 = 512     // DolbyVisionProfileDvavSe
    const val PROFILE_10 = 1024   // DolbyVisionProfileDvav110

    private const val HEVC = "video/hevc"
    private const val AVC = "video/avc"
    private const val AV1 = "video/av01"

    /**
     * Whether Dolby Vision plays as its base layer: always when the viewer asked for it in
     * Settings, otherwise only when the screen this plays on cannot show Dolby Vision.
     */
    fun useBaseLayer(forcedInSettings: Boolean, displayShowsDolbyVision: Boolean): Boolean =
        forcedInSettings || !displayShowsDolbyVision

    /** The decoder type for a profile's base layer, or null when it has none to play. */
    fun baseLayerMime(dolbyVisionProfile: Int?): String? = when (dolbyVisionProfile) {
        PROFILE_4, PROFILE_7, PROFILE_8 -> HEVC
        PROFILE_9 -> AVC
        PROFILE_10 -> AV1
        else -> null
    }
}
