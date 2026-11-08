package com.iptvapp.ui.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Voice search in SearchActivity: the system's speech screen hears a channel, show or movie, and
 * the words become the search. Only the words are used; they are searched on the device like
 * typed text.
 */
object VoiceSearch {

    /** True when this device has a speech recognizer and a screen that takes the voice intent. */
    fun isAvailable(context: Context): Boolean {
        val recognizer = try { SpeechRecognizer.isRecognitionAvailable(context) } catch (_: Exception) { false }
        if (!recognizer) return false
        val screens = try {
            context.packageManager.queryIntentActivities(intent(""), PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) { emptyList() }
        return screens.isNotEmpty()
    }

    fun intent(prompt: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    /**
     * The words to search for, or null when the viewer cancelled or nothing was heard (the search
     * stays as it was).
     */
    fun spokenQuery(resultCode: Int, data: Intent?): String? {
        if (resultCode != Activity.RESULT_OK) return null
        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) ?: return null
        return heard.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
