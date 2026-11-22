package com.iptvapp.ui.channel

import android.content.DialogInterface
import android.text.InputFilter
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptvapp.R
import com.iptvapp.data.channel.ChannelNumbering
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.repository.XtreamRepository
import kotlinx.coroutines.launch

/**
 * Edit Channel Number: gives a main-provider channel its own number (Save), or puts it back on
 * the provider's (Reset to Provider). A number taken from another channel is said so.
 */
object ChannelNumberDialog {

    fun show(activity: AppCompatActivity, channel: ChannelEntity, repository: XtreamRepository) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(ChannelNumbering.MAX_DIGITS))
            hint = activity.getString(R.string.lcn_edit_hint)
            setSingleLine()
            (channel.customNum ?: channel.num.takeIf { it > 0 })?.let { setText(it.toString()); setSelection(text.length) }
        }
        val frame = android.widget.FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.lcn_edit_title, channel.name))
            .setView(frame)
            .setPositiveButton(R.string.lcn_edit_save, null)
            .setNeutralButton(R.string.lcn_edit_reset) { _, _ -> save(activity, channel, repository, null) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        fun trySave() {
            val num = input.text.toString().trim().toIntOrNull()
            if (num == null || num !in 1..99_999) {
                Toast.makeText(activity, R.string.lcn_edit_invalid, Toast.LENGTH_SHORT).show()
                return
            }
            dialog.dismiss()
            // The provider's own number, unchanged, stays the provider's: nothing to pin.
            if (channel.customNum == null && num == channel.num) return
            save(activity, channel, repository, num)
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { trySave() }
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { trySave(); true } else false
        }
        dialog.show()
        input.requestFocus()
    }

    private fun save(activity: AppCompatActivity, channel: ChannelEntity, repository: XtreamRepository, num: Int?) {
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            val displaced = try {
                repository.assignCustomChannelNumber(channel.streamId, num)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@launch
            }
            val text = when {
                num == null -> app.getString(R.string.lcn_edit_reset_done, channel.name)
                displaced.isNotEmpty() -> app.getString(R.string.lcn_edit_saved_moved, channel.name, num, displaced.first().name)
                else -> app.getString(R.string.lcn_edit_saved, channel.name, num)
            }
            Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
        }
    }
}
