package com.iptvapp.ui.player

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import com.iptvapp.R
import com.iptvapp.databinding.DialogSyncPanelBinding
import com.iptvapp.util.PlaybackSync

/**
 * The player's Audio & Subtitle Sync panel: a strip along the bottom, the video still playing
 * above it, so every change is seen and heard at once. Changes are for now (this item, until
 * the app closes) until Save keeps them for the channel or video.
 *
 * It sits at the top of the screen, so the subtitles (at the bottom) stay in view.
 *
 * Remote: Up/Down moves between the two values and the action buttons; Left/Right on a value
 * moves it by its step (50 ms to start; held, it speeds up to 100 ms, then 500 ms); OK on a
 * value changes its step (50, 100, 500 ms); Back closes and gives the focus back to where it
 * was. Touch: the step buttons, the action buttons, and a tap on a value changes its step.
 */
class SyncPanel(private val activity: Activity, private val host: Host) {

    /** What the panel shows; read again after every change. */
    data class Item(
        /** "Channel: BBC One", "Movie: Heat". */
        val title: String,
        /** "this channel", "this movie", "this episode", "this recording". */
        val scope: String,
        val active: PlaybackSync.Offsets,
        val saved: PlaybackSync.Offsets,
        /** Why the audio offset can't act now; null when it can. */
        val audioUnavailable: String?,
        /** A note on subtitles (none showing); null when one is showing. */
        val subtitleNote: String?,
    )

    interface Host {
        /** The item playing, or null when there is nothing the offsets can apply to. */
        fun syncItem(): Item?
        /** Applies [offsets] now, for this item and this session. */
        fun setSyncOffsets(offsets: PlaybackSync.Offsets)
        fun saveSyncOffsets()
        fun useSavedSyncOffsets()
    }

    private var dialog: Dialog? = null
    private var binding: DialogSyncPanelBinding? = null
    private var restoreFocus: View? = null
    private var audioStep = 0
    private var subtitleStep = 0

    val isOpen: Boolean get() = dialog?.isShowing == true

    fun show() {
        if (isOpen) { refresh(); return }
        val item = host.syncItem() ?: return
        restoreFocus = activity.currentFocus
        val b = DialogSyncPanelBinding.inflate(LayoutInflater.from(activity))
        binding = b
        val d = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        d.setContentView(b.root)
        d.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.TOP)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // The picture stays bright: the point is to watch it while adjusting.
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        d.setOnDismissListener {
            dialog = null
            binding = null
            restoreFocus?.takeIf { it.isAttachedToWindow && it.isShown }?.requestFocus()
            restoreFocus = null
        }
        dialog = d

        arrange(b)
        buildSteps(b.audioSteps, audio = true)
        buildSteps(b.subtitleSteps, audio = false)
        wireRow(b.rowAudio, audio = true)
        wireRow(b.rowSubtitle, audio = false)
        b.tvAudioValue.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        b.tvSubtitleValue.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        b.btnSave.setOnClickListener { host.saveSyncOffsets(); refresh() }
        b.btnUseSaved.setOnClickListener { host.useSavedSyncOffsets(); refresh() }
        b.btnResetBoth.setOnClickListener { host.setSyncOffsets(PlaybackSync.Offsets()); refresh() }
        b.btnClose.setOnClickListener { d.dismiss() }

        render(item)
        d.show()
        (if (item.audioUnavailable == null) b.rowAudio else b.rowSubtitle).requestFocus()
    }

    fun dismiss() { dialog?.dismiss() }

    /** The item or its tracks changed while open: show it as it is now (or close, if it went). */
    fun refresh() {
        if (!isOpen) return
        val item = host.syncItem()
        if (item == null) { dismiss(); return }
        render(item)
    }

    private fun current(): Item? = host.syncItem()

    private fun adjust(audio: Boolean, deltaMs: Int?) {
        val item = current() ?: return
        if (audio && item.audioUnavailable != null) return
        val a = item.active
        val next = if (audio) {
            a.copy(audioMs = if (deltaMs == null) 0 else PlaybackSync.clampAudio(a.audioMs.toLong() + deltaMs))
        } else {
            a.copy(subtitleMs = if (deltaMs == null) 0 else PlaybackSync.clampSubtitle(a.subtitleMs.toLong() + deltaMs))
        }
        if (next != a) host.setSyncOffsets(next)
        refresh()
    }

    /** A wide screen (a TV, a phone on its side) puts each value and its step buttons on one line. */
    private fun arrange(b: DialogSyncPanelBinding) {
        val wide = activity.resources.configuration.screenWidthDp >= 720
        val gap = (12 * activity.resources.displayMetrics.density).toInt()
        for ((col, row, steps) in listOf(
            Triple(b.colAudio, b.rowAudio, b.audioSteps.parent as View),
            Triple(b.colSubtitle, b.rowSubtitle, b.subtitleSteps.parent as View),
        )) {
            col.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_VERTICAL
            // Baseline alignment would push the value down and cut off its focus ring.
            col.isBaselineAligned = false
            row.layoutParams = (row.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (wide) 0 else ViewGroup.LayoutParams.MATCH_PARENT
                weight = if (wide) 1f else 0f
                bottomMargin = gap / 3
            }
            steps.layoutParams = LinearLayout.LayoutParams(
                if (wide) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { if (wide) marginStart = gap }
        }
        // Up/Down: one value, the other, then the action buttons.
        b.rowAudio.nextFocusDownId = b.rowSubtitle.id
        b.rowSubtitle.nextFocusUpId = b.rowAudio.id
        b.rowSubtitle.nextFocusDownId = b.btnSave.id
        for (btn in listOf(b.btnSave, b.btnUseSaved, b.btnResetBoth, b.btnClose)) btn.nextFocusUpId = b.rowSubtitle.id
    }

    private fun buildSteps(container: LinearLayout, audio: Boolean) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        val steps = PlaybackSync.STEPS_MS
        val values: List<Int?> = steps.reversed().map { -it } + listOf(null) + steps
        val what = if (audio) "audio" else "subtitles"
        for (v in values) {
            val btn = inflater.inflate(R.layout.item_sync_step, container, false) as Button
            btn.text = when {
                v == null -> "Reset"
                v > 0 -> "+$v"
                else -> "−${-v}"
            }
            btn.contentDescription = when {
                v == null -> "Reset $what to zero"
                v > 0 -> "$what $v milliseconds later"
                else -> "$what ${-v} milliseconds earlier"
            }
            btn.setOnClickListener { adjust(audio, v) }
            // The remote moves by Left/Right on the value itself; these are for touch.
            btn.isFocusable = false
            container.addView(btn)
        }
    }

    private fun stepMs(audio: Boolean): Int = PlaybackSync.STEPS_MS[if (audio) audioStep else subtitleStep]

    private fun wireRow(row: View, audio: Boolean) {
        row.setOnClickListener {
            if (audio) audioStep = (audioStep + 1) % PlaybackSync.STEPS_MS.size
            else subtitleStep = (subtitleStep + 1) % PlaybackSync.STEPS_MS.size
            refresh()
        }
        row.setOnKeyListener { _, keyCode, event ->
            val dir = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_DOWN) adjust(audio, dir * maxOf(stepMs(audio), heldStepMs(event.repeatCount)))
            true
        }
    }

    /** 50 ms a press; held, it speeds up to 100 ms, then 500 ms. */
    private fun heldStepMs(repeatCount: Int): Int = when {
        repeatCount < 6 -> PlaybackSync.STEPS_MS[0]
        repeatCount < 20 -> PlaybackSync.STEPS_MS[1]
        else -> PlaybackSync.STEPS_MS[2]
    }

    private fun render(item: Item) {
        val b = binding ?: return
        b.tvItem.text = item.title
        b.btnSave.text = "Save for ${item.scope}"

        val audioOn = item.audioUnavailable == null
        b.tvAudioValue.text = PlaybackSync.label(item.active.audioMs)
        b.tvAudioMeaning.text = if (audioOn) "${PlaybackSync.audioMeaning(item.active.audioMs)} · step ${stepMs(true)} ms" else "Not available here"
        b.tvAudioStatus.text = item.audioUnavailable ?: savedLine(item.active.audioMs, item.saved.audioMs, item.scope)
        b.rowAudio.isEnabled = audioOn
        b.rowAudio.isFocusable = audioOn
        b.rowAudio.alpha = if (audioOn) 1f else 0.5f
        for (i in 0 until b.audioSteps.childCount) b.audioSteps.getChildAt(i).isEnabled = audioOn
        b.rowAudio.contentDescription = if (audioOn) {
            "Audio delay, ${PlaybackSync.spoken(item.active.audioMs)}. ${PlaybackSync.audioMeaning(item.active.audioMs)}. Left or right to change by ${stepMs(true)} milliseconds, OK to change the step."
        } else {
            "Audio delay not available. ${item.audioUnavailable}"
        }

        b.tvSubtitleValue.text = PlaybackSync.label(item.active.subtitleMs)
        b.tvSubtitleMeaning.text = "${PlaybackSync.subtitleMeaning(item.active.subtitleMs)} · step ${stepMs(false)} ms"
        b.tvSubtitleStatus.text = listOfNotNull(
            item.subtitleNote,
            savedLine(item.active.subtitleMs, item.saved.subtitleMs, item.scope),
        ).joinToString("\n")
        b.rowSubtitle.contentDescription =
            "Subtitle delay, ${PlaybackSync.spoken(item.active.subtitleMs)}. ${PlaybackSync.subtitleMeaning(item.active.subtitleMs)}. Left or right to change by ${stepMs(false)} milliseconds, OK to change the step."

        b.btnSave.isEnabled = item.active != item.saved
        b.btnUseSaved.isEnabled = item.active != item.saved
        b.btnResetBoth.isEnabled = !item.active.isZero

        // A button that just turned itself off (Save, once saved) can't keep the remote's focus.
        val focused = b.root.findFocus()
        if (focused == null || !focused.isEnabled || !focused.isFocusable) {
            (if (audioOn) b.rowAudio else b.rowSubtitle).requestFocus()
        }
    }

    private fun savedLine(active: Int, saved: Int, scope: String): String = when {
        active == saved && saved == 0 -> "Nothing saved for $scope"
        active == saved -> "Saved for $scope"
        saved == 0 -> "Not saved (nothing saved for $scope)"
        else -> "Not saved · saved for $scope: ${PlaybackSync.label(saved)}"
    }
}
