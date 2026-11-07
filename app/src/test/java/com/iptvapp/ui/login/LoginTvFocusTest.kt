package com.iptvapp.ui.login

import android.app.Application
import android.content.Context
import android.view.LayoutInflater
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.R
import com.iptvapp.databinding.ActivityLoginBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TV D-pad focus and scrolling verification for the Login screen:
 * Verifies that the screen is scrollable on TV viewports, that all fields & buttons
 * form an unbroken two-way nextFocus chain, and that high-contrast focus highlight
 * selectors exist for all interactive components.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LoginTvFocusTest {

    private lateinit var context: Context
    private lateinit var binding: ActivityLoginBinding

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_IptvApp)
        binding = ActivityLoginBinding.inflate(LayoutInflater.from(context))
    }

    @Test
    fun loginScreenIsScrollableWithFillViewport() {
        val root = binding.root
        assertTrue("ScrollView must enable fillViewport for centered layout on tall screens", root.isFillViewport)
        assertNotNull("scrollLogin view binding must exist", binding.scrollLogin)
    }

    @Test
    fun dpadNextFocusChainIsUnbrokenTopToBottom() {
        // Forward D-pad Down traversal from top field to bottom-most action
        assertEquals(R.id.etNickname, binding.etServerUrl.nextFocusDownId)
        assertEquals(R.id.etUsername, binding.etNickname.nextFocusDownId)
        assertEquals(R.id.etPassword, binding.etUsername.nextFocusDownId)
        assertEquals(R.id.etEpgUrl, binding.etPassword.nextFocusDownId)
        assertEquals(R.id.btnLogin, binding.etEpgUrl.nextFocusDownId)
        assertEquals(R.id.btnImportM3u, binding.btnLogin.nextFocusDownId)
        assertEquals(R.id.btnScanQr, binding.btnImportM3u.nextFocusDownId)
        assertEquals(R.id.btnRestoreFromBackup, binding.btnScanQr.nextFocusDownId)
        assertEquals(R.id.btnReceiveCast, binding.btnRestoreFromBackup.nextFocusDownId)
    }

    @Test
    fun dpadNextFocusChainIsUnbrokenBottomToTop() {
        // Reverse D-pad Up traversal from bottom action back to top field
        assertEquals(R.id.btnRestoreFromBackup, binding.btnReceiveCast.nextFocusUpId)
        assertEquals(R.id.btnScanQr, binding.btnRestoreFromBackup.nextFocusUpId)
        assertEquals(R.id.btnImportM3u, binding.btnScanQr.nextFocusUpId)
        assertEquals(R.id.btnLogin, binding.btnImportM3u.nextFocusUpId)
        assertEquals(R.id.etEpgUrl, binding.btnLogin.nextFocusUpId)
        assertEquals(R.id.etPassword, binding.etEpgUrl.nextFocusUpId)
        assertEquals(R.id.etUsername, binding.etPassword.nextFocusUpId)
        assertEquals(R.id.etNickname, binding.etUsername.nextFocusUpId)
        assertEquals(R.id.etServerUrl, binding.etNickname.nextFocusUpId)
    }

    @Test
    fun allLoginElementsAreFocusable() {
        val interactiveElements = listOf(
            binding.etServerUrl,
            binding.etNickname,
            binding.etUsername,
            binding.etPassword,
            binding.etEpgUrl,
            binding.btnLogin,
            binding.btnImportM3u,
            binding.btnScanQr,
            binding.btnRestoreFromBackup,
            binding.btnReceiveCast
        )
        for (element in interactiveElements) {
            assertTrue("Element ${element.id} must be focusable for D-pad", element.isFocusable)
        }
    }

    @Test
    fun focusColorStateListsProvideDistinctHighlights() {
        val focusedState = intArrayOf(android.R.attr.state_focused)
        val defaultState = intArrayOf()

        // 1. Text input border stroke
        val inputStroke = ContextCompat.getColorStateList(context, R.color.login_input_box_stroke)
        assertNotNull(inputStroke)
        assertNotEquals(
            "Input stroke must differ under focus",
            inputStroke!!.getColorForState(defaultState, 0),
            inputStroke.getColorForState(focusedState, 0)
        )

        // 2. Text input box background
        val inputBg = ContextCompat.getColorStateList(context, R.color.login_input_box_bg)
        assertNotNull(inputBg)
        assertNotEquals(
            "Input background must differ under focus",
            inputBg!!.getColorForState(defaultState, 0),
            inputBg.getColorForState(focusedState, 0)
        )

        // 3. Primary button background
        val primaryBg = ContextCompat.getColorStateList(context, R.color.login_btn_primary_bg)
        assertNotNull(primaryBg)
        assertNotEquals(
            "Primary button background must differ under focus",
            primaryBg!!.getColorForState(defaultState, 0),
            primaryBg.getColorForState(focusedState, 0)
        )

        // 4. Primary button stroke
        val primaryStroke = ContextCompat.getColorStateList(context, R.color.login_btn_primary_stroke)
        assertNotNull(primaryStroke)
        assertNotEquals(
            "Primary button stroke must differ under focus",
            primaryStroke!!.getColorForState(defaultState, 0),
            primaryStroke.getColorForState(focusedState, 0)
        )

        // 5. Secondary button background
        val secondaryBg = ContextCompat.getColorStateList(context, R.color.login_btn_secondary_bg)
        assertNotNull(secondaryBg)
        assertNotEquals(
            "Secondary button background must differ under focus",
            secondaryBg!!.getColorForState(defaultState, 0),
            secondaryBg.getColorForState(focusedState, 0)
        )

        // 6. Secondary button stroke
        val secondaryStroke = ContextCompat.getColorStateList(context, R.color.login_btn_secondary_stroke)
        assertNotNull(secondaryStroke)
        assertNotEquals(
            "Secondary button stroke must differ under focus",
            secondaryStroke!!.getColorForState(defaultState, 0),
            secondaryStroke.getColorForState(focusedState, 0)
        )
    }

    @Test
    fun textInputLayoutBoxBackgroundColorStateListIsSupported() {
        val inputBg = ContextCompat.getColorStateList(context, R.color.login_input_box_bg)
        assertNotNull(inputBg)
        val focusedColor = inputBg!!.getColorForState(
            intArrayOf(android.R.attr.state_focused, android.R.attr.state_enabled),
            0
        )
        val defaultColor = inputBg.defaultColor
        assertNotEquals("Focused input color must differ from default", defaultColor, focusedColor)

        val inputLayouts = listOf(
            binding.tilServerUrl,
            binding.tilNickname,
            binding.tilUsername,
            binding.tilPassword,
            binding.tilEpgUrl
        )
        inputLayouts.forEach { til ->
            til.setBoxBackgroundColorStateList(inputBg)
            til.setBoxBackgroundColor(focusedColor)
            assertEquals(focusedColor, til.boxBackgroundColor)
            til.setBoxBackgroundColor(defaultColor)
            assertEquals(defaultColor, til.boxBackgroundColor)
        }
    }
}
