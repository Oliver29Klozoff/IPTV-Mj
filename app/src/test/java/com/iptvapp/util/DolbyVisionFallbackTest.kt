package com.iptvapp.util

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionFallbackTest {

    @Test
    fun `profiles with an ordinary picture underneath fall back to its decoder`() {
        assertEquals("video/hevc", DolbyVisionFallback.baseLayerMime(DolbyVisionFallback.PROFILE_7))
        assertEquals("video/hevc", DolbyVisionFallback.baseLayerMime(DolbyVisionFallback.PROFILE_8))
        assertEquals("video/hevc", DolbyVisionFallback.baseLayerMime(DolbyVisionFallback.PROFILE_4))
        assertEquals("video/avc", DolbyVisionFallback.baseLayerMime(DolbyVisionFallback.PROFILE_9))
        assertEquals("video/av01", DolbyVisionFallback.baseLayerMime(DolbyVisionFallback.PROFILE_10))
    }

    @Test
    fun `profile 5 and unknown streams never do, since they would turn purple and green`() {
        assertNull(DolbyVisionFallback.baseLayerMime(DolbyVisionFallback.PROFILE_5))
        assertNull(DolbyVisionFallback.baseLayerMime(null))
        assertNull(DolbyVisionFallback.baseLayerMime(1))
    }

    @Test
    fun `the constants are Android's own profile values`() {
        assertEquals(CodecProfileLevel.DolbyVisionProfileDvheDtr, DolbyVisionFallback.PROFILE_4)
        assertEquals(CodecProfileLevel.DolbyVisionProfileDvheStn, DolbyVisionFallback.PROFILE_5)
        assertEquals(CodecProfileLevel.DolbyVisionProfileDvheDtb, DolbyVisionFallback.PROFILE_7)
        assertEquals(CodecProfileLevel.DolbyVisionProfileDvheSt, DolbyVisionFallback.PROFILE_8)
        assertEquals(CodecProfileLevel.DolbyVisionProfileDvavSe, DolbyVisionFallback.PROFILE_9)
        assertEquals(CodecProfileLevel.DolbyVisionProfileDvav110, DolbyVisionFallback.PROFILE_10)
    }

    @Test
    fun `automatic on a screen without Dolby Vision, forced by the setting anywhere`() {
        assertTrue(DolbyVisionFallback.useBaseLayer(forcedInSettings = false, displayShowsDolbyVision = false))
        assertFalse(DolbyVisionFallback.useBaseLayer(forcedInSettings = false, displayShowsDolbyVision = true))
        assertTrue(DolbyVisionFallback.useBaseLayer(forcedInSettings = true, displayShowsDolbyVision = true))
    }
}
