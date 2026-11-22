package com.iptvapp.data.accessibility

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.home.CardDensity
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.dataStore
import androidx.datastore.preferences.core.edit
import com.iptvapp.util.AccessibilityContextWrapper
import com.iptvapp.util.AccessibilityEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → Accessibility: High-Visibility / Senior Mode and its parts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccessibilityPreferencesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // The settings store outlives a test; each starts from a new install.
    @org.junit.Before
    fun clearSettings(): Unit = runBlocking { context.dataStore.edit { it.clear() } }

    @Test
    fun offUntilChanged(): Unit = runBlocking {
        val s = PreferencesManager(context).accessibility.first()
        assertEquals(AccessibilitySettings.STANDARD, s)
        assertFalse(s.seniorMode)
        assertEquals(1.0f, s.textSize.scale)
        assertEquals(2_500L, s.tuneBannerMs)
        assertEquals(5_000L, s.playerControlsMs)
        assertEquals(0, s.minTouchTargetDp)
    }

    @Test
    fun seniorModeOnSetsEverythingAndOffPutsItAllBack(): Unit = runBlocking {
        val prefs = PreferencesManager(context)
        prefs.setCardDensity(CardDensity.COMPACT)

        prefs.setSeniorMode(true)
        val on = prefs.accessibility.first()
        assertTrue(on.seniorMode)
        assertEquals(1.30f, on.textSize.scale)
        assertTrue(on.extendedOsd)
        assertTrue(on.highContrastFocus)
        assertEquals(6_500L, on.tuneBannerMs)
        assertEquals(12_000L, on.playerControlsMs)
        assertEquals(52, on.minTouchTargetDp)
        assertEquals(CardDensity.EXPANDED, prefs.cardDensity.first())

        // Switching it on again doesn't forget the size from before.
        prefs.setSeniorMode(true)
        prefs.setSeniorMode(false)
        assertEquals(AccessibilitySettings.STANDARD, prefs.accessibility.first())
        assertEquals(CardDensity.COMPACT, prefs.cardDensity.first())

        // Off while already off leaves the cards alone.
        prefs.setCardDensity(CardDensity.EXPANDED)
        prefs.setSeniorMode(false)
        assertEquals(CardDensity.EXPANDED, prefs.cardDensity.first())
    }

    @Test
    fun eachPartCanBeChangedAlone(): Unit = runBlocking {
        val prefs = PreferencesManager(context)
        prefs.setSeniorMode(true)
        prefs.setUiFontScale(TextSizeLevel.LARGE)
        prefs.setExtendedOsdTimeout(false)
        val s = prefs.accessibility.first()
        assertTrue(s.seniorMode)
        assertEquals(TextSizeLevel.LARGE, s.textSize)
        assertFalse(s.extendedOsd)
        assertTrue(s.highContrastFocus)

        prefs.setAccessibility(AccessibilitySettings(highContrastFocus = true))
        assertEquals(AccessibilitySettings(highContrastFocus = true), prefs.accessibility.first())

        // A restored Senior Mode still knows which card size to put back.
        prefs.setAccessibility(AccessibilitySettings.SENIOR, CardDensity.COMPACT.id)
        assertEquals(CardDensity.COMPACT.id, prefs.seniorPrevCardDensityId())
        prefs.setSeniorMode(false)
        assertEquals(CardDensity.COMPACT, prefs.cardDensity.first())
        assertEquals(null, prefs.seniorPrevCardDensityId())
    }

    @Test
    fun aValueNeverSetFollowsTheMasterSwitch() {
        assertEquals(AccessibilitySettings.SENIOR, AccessibilitySettings.resolve(true, null, null, null))
        assertEquals(AccessibilitySettings.STANDARD, AccessibilitySettings.resolve(null, null, null, null))
        val tuned = AccessibilitySettings.resolve(true, 1.0f, false, null)
        assertEquals(TextSizeLevel.STANDARD, tuned.textSize)
        assertFalse(tuned.extendedOsd)
        assertTrue(tuned.highContrastFocus)
    }

    @Test
    fun textSizeReadsTheNearestLevelAndRejectsNonsense() {
        assertEquals(TextSizeLevel.LARGE, TextSizeLevel.fromScale(1.15f))
        assertEquals(TextSizeLevel.EXTRA_LARGE, TextSizeLevel.fromScale(1.3000001f))
        assertEquals(TextSizeLevel.STANDARD, TextSizeLevel.fromScale(1.02f))
        assertEquals(TextSizeLevel.STANDARD, TextSizeLevel.fromScale(Float.NaN))
        assertEquals(TextSizeLevel.STANDARD, TextSizeLevel.fromScale(9f))
        assertEquals(TextSizeLevel.STANDARD, TextSizeLevel.fromScale(null))
    }

    @Test
    fun onlyTextSizeAndTheMasterSwitchRebuildScreens() {
        val base = AccessibilitySettings.STANDARD.screenSignature
        assertEquals(base, AccessibilitySettings(extendedOsd = true, highContrastFocus = true).screenSignature)
        assertNotEquals(base, AccessibilitySettings(textSize = TextSizeLevel.LARGE).screenSignature)
        assertNotEquals(base, AccessibilitySettings(seniorMode = true).screenSignature)
    }

    @Test
    fun theWrapperMultipliesTheDevicesFontScale() {
        assertSame(context, AccessibilityContextWrapper.wrap(context, 1.0f))
        val device = context.resources.configuration.fontScale
        val wrapped = AccessibilityContextWrapper.wrap(context, 1.30f)
        assertEquals(device * 1.30f, wrapped.resources.configuration.fontScale, 0.0001f)
        // Text gets bigger. (Android 14 and later grow large text a little less than 30%, on purpose.)
        val sp = { c: Context -> android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 20f, c.resources.displayMetrics) }
        assertTrue(sp(wrapped) > sp(context) * 1.15f && sp(wrapped) <= sp(context) * 1.30f + 0.01f)
    }

    @Test
    fun touchTargetsGrowOnlyWhereTheLayoutSizesToContent() {
        val root = LinearLayout(context)
        val wrapButton = Button(context).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        val fixedButton = Button(context).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 40) }
        val icon = ImageButton(context).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        root.addView(wrapButton); root.addView(fixedButton); root.addView(icon)
        AccessibilityEngine.enlargeTouchTargets(root, 130)
        assertEquals(130, wrapButton.minHeight)
        assertTrue(fixedButton.minHeight < 130)
        assertEquals(130, icon.minimumHeight)
        assertEquals(130, icon.minimumWidth)
    }

    @Test
    fun theBoldRingGoesOnAndComesOffWithoutTouchingTheBackground() {
        val root = FrameLayout(context)
        val card = View(context)
        root.addView(card, FrameLayout.LayoutParams(200, 100))
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1000, 600)
        val bg = card.background

        AccessibilityEngine.FocusRing.add(card)
        assertTrue(AccessibilityEngine.FocusRing.isShowing(card))
        assertSame(bg, card.background)
        AccessibilityEngine.FocusRing.add(card) // no second ring
        AccessibilityEngine.FocusRing.remove(card)
        assertFalse(AccessibilityEngine.FocusRing.isShowing(card))

        // Whatever fills the screen (the video) gets none.
        AccessibilityEngine.FocusRing.add(root)
        assertFalse(AccessibilityEngine.FocusRing.isShowing(root))
    }
}
