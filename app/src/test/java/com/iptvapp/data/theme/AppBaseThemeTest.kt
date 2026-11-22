package com.iptvapp.data.theme

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.R
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.util.AccentColors
import com.iptvapp.util.ThemeEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → Appearance → Base theme and the curated accents (AppBaseTheme, ThemePalette, ThemeEngine). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppBaseThemeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun fromIdFallsBackToOledBlackAndIgnoresCaseAndSpaces() {
        assertEquals(AppBaseTheme.OLED_BLACK, AppBaseTheme.fromId(null))
        assertEquals(AppBaseTheme.OLED_BLACK, AppBaseTheme.fromId(""))
        assertEquals(AppBaseTheme.OLED_BLACK, AppBaseTheme.fromId("sepia"))
        assertEquals(AppBaseTheme.MIDNIGHT_NAVY, AppBaseTheme.fromId("MIDNIGHT_NAVY"))
        assertEquals(AppBaseTheme.CHARCOAL_DARK, AppBaseTheme.fromId(" charcoal_dark "))
        for (t in AppBaseTheme.entries) assertEquals(t, AppBaseTheme.fromId(t.id))
        assertEquals(AppBaseTheme.entries.size, AppBaseTheme.entries.map { it.id }.toSet().size)
    }

    @Test
    fun everyColourIsOpaqueAndOledIsTrueBlack() {
        for (t in AppBaseTheme.entries) {
            for (c in listOf(t.backgroundColor, t.surfaceColor, t.cardColor, t.borderColor, t.dividerColor)) {
                assertEquals("${t.id} ${Integer.toHexString(c)}", 0xFF, Color.alpha(c))
            }
            assertTrue(context.getString(t.titleRes).isNotBlank())
            assertTrue(context.getString(t.descRes).isNotBlank())
        }
        assertEquals(Color.BLACK, AppBaseTheme.OLED_BLACK.backgroundColor)
        assertEquals(Color.BLACK, AppBaseTheme.OLED_BLACK.cardColor)
        assertEquals(0xFF0B1120.toInt(), AppBaseTheme.MIDNIGHT_NAVY.backgroundColor)
        assertEquals(0xFF121212.toInt(), AppBaseTheme.CHARCOAL_DARK.backgroundColor)
    }

    @Test
    fun onlyTheAppsOwnNearBlackGreysAreThemed() {
        val navy = AppBaseTheme.MIDNIGHT_NAVY
        assertEquals(navy.backgroundColor, navy.surfaceFor(0xFF000000.toInt()))
        assertEquals(navy.surfaceColor, navy.surfaceFor(0xFF0D0D0D.toInt()))
        assertEquals(navy.cardColor, navy.surfaceFor(0xFF1F1F1F.toInt()))
        assertEquals(navy.dividerColor, navy.surfaceFor(0xFF1A1A1A.toInt(), thin = true))
        // The accent, a lighter grey, a see-through scrim and a bluish panel stay as they are.
        assertNull(navy.surfaceFor(0xFF06B6D4.toInt()))
        assertNull(navy.surfaceFor(0xFF303030.toInt()))
        assertNull(navy.surfaceFor(0xCC000000.toInt()))
        assertNull(navy.surfaceFor(0xFF0A0E17.toInt()))
        assertNull(navy.surfaceFor(0xFFFF3B30.toInt()))
    }

    @Test
    fun aRecessedPanelStaysDarkerThanTheGroupAroundItInEveryTheme() {
        for (t in AppBaseTheme.entries) {
            val recessed = t.surfaceFor(0xFF070707.toInt())!!
            val group = t.surfaceFor(0xFF0D0D0D.toInt())!!
            assertTrue(t.id, ColorUtils.calculateLuminance(recessed) < ColorUtils.calculateLuminance(group))
        }
    }

    @Test
    fun textAndFocusOutlineMeetContrastOnEverySurface() {
        val text = context.getColor(R.color.rack_text)
        val secondary = context.getColor(R.color.rack_text_secondary)
        val focus = context.getColor(R.color.rack_focus_ring)
        for (t in AppBaseTheme.entries) {
            for (bg in listOf(t.backgroundColor, t.surfaceColor, t.cardColor)) {
                val where = "${t.id} on #${Integer.toHexString(bg)}"
                assertTrue("text $where", ColorUtils.calculateContrast(text, bg) >= 4.5)
                assertTrue("secondary text $where", ColorUtils.calculateContrast(secondary, bg) >= 4.5)
                assertTrue("focus outline $where", ColorUtils.calculateContrast(focus, bg) >= 3.0)
            }
        }
    }

    @Test
    fun curatedAccentsAreValidAndCyberCyanIsTheDefault() {
        val presets = ThemePalette.PRESET_ACCENTS
        assertEquals(8, presets.size)
        assertEquals(presets.size, presets.map { it.name }.toSet().size)
        for (p in presets) {
            assertNotNull(p.name, AccentColors.parseOrNull(p.start))
            p.end?.let { assertNotNull(p.name, AccentColors.parseOrNull(it)) }
        }
        assertEquals(AccentColors.DEFAULT_HEX, presets.first().start)
        assertEquals(listOf("Sunset Horizon", "Ocean Wave"), presets.filter { it.isGradient }.map { it.name })
        assertEquals("Ocean Wave", ThemePalette.presetFor("#008cff", "#10b981")?.name)
        assertEquals("Emerald Sport", ThemePalette.presetFor("#10B981", "")?.name)
        // A solid start that is also a gradient's start is the solid only when there is no end.
        assertEquals("Neon Amber", ThemePalette.presetFor("#F59E0B", "")?.name)
        assertEquals("Sunset Horizon", ThemePalette.presetFor("#F59E0B", "#EF4444")?.name)
        assertNull(ThemePalette.presetFor("#123456", ""))
    }

    @Test
    fun theThemeIsSavedAndReadBack(): Unit = runBlocking {
        val prefs = PreferencesManager(context)
        prefs.setBaseTheme(AppBaseTheme.MIDNIGHT_NAVY)
        assertEquals(AppBaseTheme.MIDNIGHT_NAVY, prefs.baseTheme.first())
        assertEquals(AppBaseTheme.MIDNIGHT_NAVY, PreferencesManager(context).baseTheme.first())
        prefs.setBaseTheme(AppBaseTheme.CHARCOAL_DARK)
        assertEquals(AppBaseTheme.CHARCOAL_DARK, prefs.baseTheme.first())
        prefs.setBaseTheme(AppBaseTheme.OLED_BLACK)
        assertEquals(AppBaseTheme.OLED_BLACK, prefs.baseTheme.first())
    }

    @Test
    fun aScreenRepaintsInPlaceAndCanSwitchBack() {
        val root = FrameLayout(context).apply { setBackgroundColor(0xFF000000.toInt()) }
        val panel = LinearLayout(context).apply { setBackgroundColor(0xFF0D0D0D.toInt()) }
        val card = View(context).apply {
            background = GradientDrawable().apply { setColor(0xFF1F1F1F.toInt()) }
        }
        val rule = View(context).apply {
            setBackgroundColor(0xFF1A1A1A.toInt())
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }
        val accent = View(context).apply { setBackgroundColor(0xFF06B6D4.toInt()) }
        val preview = View(context).apply {
            setBackgroundColor(0xFF0D0D0D.toInt())
            setTag(R.id.theme_engine_skip, true)
        }
        panel.addView(card)
        panel.addView(rule)
        panel.addView(accent)
        root.addView(panel)
        root.addView(preview)
        fun color(v: View) = (v.background as ColorDrawable).color

        val navy = AppBaseTheme.MIDNIGHT_NAVY
        ThemeEngine.applySurfaceTheme(root, navy)
        assertEquals(navy.backgroundColor, color(root))
        assertEquals(navy.surfaceColor, color(panel))
        assertEquals(navy.cardColor, (card.background as GradientDrawable).color!!.defaultColor)
        assertEquals(navy.dividerColor, color(rule))
        assertEquals(0xFF06B6D4.toInt(), color(accent))
        assertEquals(0xFF0D0D0D.toInt(), color(preview))

        // Switching again starts from the layout's own colours, not from navy.
        val oled = AppBaseTheme.OLED_BLACK
        ThemeEngine.applySurfaceTheme(root, oled)
        assertEquals(Color.BLACK, color(root))
        assertEquals(oled.surfaceColor, color(panel))
        assertEquals(oled.cardColor, (card.background as GradientDrawable).color!!.defaultColor)
        assertEquals(oled.dividerColor, color(rule))

        // Something else repainting a panel later makes that its own colour from then on.
        panel.setBackgroundColor(0xFF06B6D4.toInt())
        ThemeEngine.applySurfaceTheme(root, navy)
        assertEquals(0xFF06B6D4.toInt(), color(panel))
        assertNotEquals(oled.backgroundColor, color(root))
    }
}
