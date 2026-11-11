package com.iptvapp.ui.search

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyboard keys that run the search (and close the keyboard, so a TV remote can reach the results). */
class SearchKeyboardTest {

    @Test
    fun searchGoDoneAndNextAllRunTheSearch() {
        listOf(EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_NEXT)
            .forEach { assertTrue("action $it", SearchActivity.isSubmitAction(it)) }
    }

    @Test
    fun otherKeysDoNot() {
        listOf(EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_PREVIOUS, EditorInfo.IME_ACTION_SEND)
            .forEach { assertFalse("action $it", SearchActivity.isSubmitAction(it)) }
    }
}
