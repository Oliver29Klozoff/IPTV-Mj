package com.iptvapp.settings

import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.TuningProfile
import com.iptvapp.data.local.TuningSettings
import com.iptvapp.ui.settings.SubtitleAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsConsolidationTest {

    /** What a fresh install has saved for the four tuning knobs (PreferencesManager defaults). */
    private val outOfTheBox = TuningSettings(preWarmOnFocus = true, turboTune = "automatic", liveReconnectSpeed = "normal", channelZapDebounceMs = 0)

    @Test
    fun anUpgradeShowsStandardAndChangesNobodysPlayback() {
        assertEquals(TuningProfile.STANDARD, TuningProfile.matching(outOfTheBox))
        assertEquals(outOfTheBox, TuningProfile.STANDARD.settings)
        assertEquals("Standard", TuningProfile.label(TuningProfile.STANDARD))
    }

    @Test
    fun turboUsesThePlayersFastestKnobs() {
        val s = TuningProfile.TURBO.settings
        assertEquals("performance", s.turboTune)
        assertTrue(s.preWarmOnFocus)
        assertEquals("aggressive", s.liveReconnectSpeed)
        assertEquals(150, s.channelZapDebounceMs)
    }

    @Test
    fun lowBandwidthFetchesNothingAhead() {
        val s = TuningProfile.LOW_BANDWIDTH.settings
        assertFalse(s.preWarmOnFocus)
        assertEquals("off", s.turboTune)
        assertEquals("patient", s.liveReconnectSpeed)
        assertTrue(s.channelZapDebounceMs > TuningProfile.STANDARD.settings.channelZapDebounceMs)
    }

    @Test
    fun everyProfileUsesValuesThePlayerUnderstands() {
        for (p in TuningProfile.entries) {
            assertTrue(p.settings.turboTune in setOf("off", "automatic", "performance"))
            assertTrue(p.settings.liveReconnectSpeed in setOf("aggressive", "normal", "patient"))
            assertEquals(p, TuningProfile.matching(p.settings))
        }
    }

    @Test
    fun aHandSetMixShowsAsCustom() {
        val mixed = outOfTheBox.copy(channelZapDebounceMs = 300)
        assertNull(TuningProfile.matching(mixed))
        assertEquals("Custom", TuningProfile.label(null))
    }

    @Test
    fun subtitleRowsStepThroughTheirValuesAndWrap() {
        assertEquals(1.25f, SubtitleAppearance.next(SubtitleAppearance.SIZES, 1.0f))
        assertEquals(0.75f, SubtitleAppearance.next(SubtitleAppearance.SIZES, 2.0f))
        assertEquals("an unknown saved value starts the list", 0.75f, SubtitleAppearance.next(SubtitleAppearance.SIZES, 1.1f))
        assertEquals("Up 20dp", SubtitleAppearance.offsetLabel(20))
        assertEquals("Down 40dp", SubtitleAppearance.offsetLabel(-40))
        assertEquals("Default", SubtitleAppearance.offsetLabel(0))
    }

    @Test
    fun subtitleSummaryNamesTheStyle() {
        val style = PreferencesManager.SubtitleStyle(
            sizeScale = 1.25f, verticalOffsetDp = 0, bold = true, textColor = 0xFFFFFF00.toInt(),
            backgroundColor = 0, outlineEnabled = true, outlineColor = 0xFF000000.toInt()
        )
        assertEquals("125% · Yellow · Bold · Outline", SubtitleAppearance.summary(style))
        assertEquals("100% · White · Shaded",
            SubtitleAppearance.summary(style.copy(sizeScale = 1.0f, textColor = 0xFFFFFFFF.toInt(), bold = false,
                outlineEnabled = false, backgroundColor = 0x99000000.toInt())))
    }
}
