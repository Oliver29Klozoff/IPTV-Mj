package com.iptvapp.ui.search

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.R
import com.iptvapp.databinding.ActivitySearchBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchVoiceTest {

    private lateinit var context: Context
    private lateinit var binding: ActivitySearchBinding

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_IptvApp)
        binding = ActivitySearchBinding.inflate(LayoutInflater.from(context))
    }

    @Test
    fun micButtonSitsRightOfTheSearchBox() {
        val bar = binding.searchTopBar
        val box = bar.indexOfChild(binding.etSearch)
        assertEquals(box + 1, bar.indexOfChild(binding.btnVoiceSearch))
        assertTrue(binding.btnVoiceSearch.isFocusable)
        assertEquals(context.getString(R.string.search_voice_description), binding.btnVoiceSearch.contentDescription)
    }

    @Test
    fun topBarFocusChainRunsBothWays() {
        assertEquals(R.id.etSearch, binding.btnSearchBack.nextFocusRightId)
        assertEquals(R.id.btnSearchBack, binding.etSearch.nextFocusLeftId)
        assertEquals(R.id.btnVoiceSearch, binding.etSearch.nextFocusRightId)
        assertEquals(R.id.etSearch, binding.btnVoiceSearch.nextFocusLeftId)
    }

    @Test
    fun voiceIntentAsksForFreeFormSpeechWithThePrompt() {
        val intent = VoiceSearch.intent("Say something")
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertEquals("Say something", intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT))
    }

    @Test
    fun topTranscriptBecomesTheQuery() {
        val data = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(" ESPN ", "ESPN 2"))
        assertEquals("ESPN", VoiceSearch.spokenQuery(Activity.RESULT_OK, data))
    }

    @Test
    fun cancelledOrEmptyResultsLeaveTheSearchAlone() {
        val data = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("ESPN"))
        assertNull(VoiceSearch.spokenQuery(Activity.RESULT_CANCELED, data))
        assertNull(VoiceSearch.spokenQuery(Activity.RESULT_OK, null))
        assertNull(VoiceSearch.spokenQuery(Activity.RESULT_OK, Intent()))
        assertNull(VoiceSearch.spokenQuery(Activity.RESULT_OK,
            Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf())))
        assertNull(VoiceSearch.spokenQuery(Activity.RESULT_OK,
            Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("  "))))
    }

    @Test
    fun noSpeechEngineMeansNoMic() {
        // Robolectric's device has no recognizer, like a bare AOSP car box.
        assertFalse(VoiceSearch.isAvailable(context))
    }
}
