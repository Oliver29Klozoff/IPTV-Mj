package com.iptvapp.ui.epg

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptvapp.data.epg.EpgGuideChannel
import com.iptvapp.data.epg.EpgIssueKind
import com.iptvapp.data.epg.EpgLocalChannel
import com.iptvapp.data.epg.EpgOverrideMode
import com.iptvapp.data.epg.EpgRepairRepository
import com.iptvapp.data.epg.EpgTimeOffset
import com.iptvapp.databinding.ActivityEpgFixBinding
import com.iptvapp.databinding.ItemEpgGuideOptionBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * One channel's guide (EPG Health): where its listings come from, suggested guide channels,
 * a search of every guide channel its provider's guides list, a time offset, and back to
 * automatic matching. Nothing is saved by moving focus or tapping a suggestion: each choice
 * asks first. Corrections show everywhere the guide is read (TV guide, Quick Peek, the
 * player's Now/Next, recordings), because they change the listings stored for the channel.
 */
@AndroidEntryPoint
class EpgFixChannelActivity : AppCompatActivity() {

    @Inject lateinit var repair: EpgRepairRepository

    private lateinit var binding: ActivityEpgFixBinding
    private lateinit var channel: EpgLocalChannel
    private var status: EpgRepairRepository.ChannelStatus? = null
    private var searchJob: Job? = null
    private var changed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgFixBinding.inflate(layoutInflater)
        setContentView(binding.root)
        channel = EpgLocalChannel(
            serverIndex = intent.getIntExtra(EXTRA_SERVER, -1),
            streamId = intent.getIntExtra(EXTRA_STREAM, 0),
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            epgChannelId = intent.getStringExtra(EXTRA_EPG_ID),
        )
        changed = savedInstanceState?.getBoolean(KEY_CHANGED) ?: false
        if (changed) setResult(RESULT_OK)
        binding.tvChannel.text = channel.name
        binding.tvProvider.text = intent.getStringExtra(EXTRA_PROVIDER).orEmpty()
        binding.btnBack.setOnClickListener { finish() }
        binding.btnOffset.setOnClickListener { chooseOffset() }
        binding.btnReset.setOnClickListener { confirmReset() }
        binding.btnGuide.setOnClickListener {
            startActivity(Intent(this, com.iptvapp.ui.guide.EpgTimelineActivity::class.java))
        }
        binding.etSearch.setOnEditorActionListener { _, action, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (action == EditorInfo.IME_ACTION_SEARCH || enter) { search(binding.etSearch.text.toString(), now = true); true } else false
        }
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { search(s?.toString().orEmpty(), now = false) }
        })
        load(focusFirst = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_CHANGED, changed)
    }

    private fun load(focusFirst: Boolean = false) {
        lifecycleScope.launch {
            busy(true)
            val s = repair.channelStatus(channel)
            status = s
            busy(false)
            render(s)
            if (focusFirst) {
                (binding.suggestions.getChildAt(0) ?: binding.etSearch).requestFocus()
            }
        }
    }

    private fun render(s: EpgRepairRepository.ChannelStatus) {
        val nowSec = System.currentTimeMillis() / 1000
        val o = s.override
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        binding.tvStatus.text = when {
            s.lastStop > nowSec -> "Has listings until ${time.format(Date(s.lastStop * 1000))}"
            s.lastStop > 0 -> "No future listings — the last one ended ${time.format(Date(s.lastStop * 1000))}"
            else -> "No listings"
        }
        val issue = repair.report?.issues?.firstOrNull { it.channel.serverIndex == channel.serverIndex && it.channel.streamId == channel.streamId }
        binding.tvStatusDetail.text = buildList {
            if (o != null && o.mode == EpgOverrideMode.MAP.name) add("Correction saved: listings from \"${o.guideName ?: o.xmltvId}\" (${repair.sourceLabel(channel.serverIndex, o.sourceKey.orEmpty())}).")
            if (o != null && o.offsetMinutes != 0) add("Times moved ${EpgTimeOffset.label(o.offsetMinutes)}.")
            if (o == null) add("Automatic matching.")
            s.suspicion?.let { add(it + ".") }
            if (issue?.kind == EpgIssueKind.OFFSET_HINT && issue.offsetHint != null) add("The provider's own guide suggests moving the times ${EpgTimeOffset.label(issue.offsetHint)}.")
            if (issue != null && issue.kind != EpgIssueKind.OK && issue.kind != EpgIssueKind.CORRECTED) add(issue.detail + ".")
        }.distinct().joinToString(" ")
        binding.btnReset.visibility = if (o != null) View.VISIBLE else View.GONE
        binding.btnOffset.text = if (o != null && o.offsetMinutes != 0) "Time offset: ${EpgTimeOffset.label(o.offsetMinutes)}" else "Time offset"

        binding.current.removeAllViews()
        val current = s.current
        binding.tvCurrentTitle.visibility = if (current != null) View.VISIBLE else View.GONE
        if (current != null) binding.current.addView(option(current, reason = null, choosable = false))

        binding.suggestions.removeAllViews()
        val match = s.match
        if (match.candidates.isEmpty()) {
            binding.tvSuggestReason.text = if (current != null) "No other guide channel looks like this one. Search below to choose one." else "${match.reason}. Search below to choose a guide channel."
        } else {
            binding.tvSuggestReason.text = "${EpgUiText.state(match.state)} — ${match.reason}"
            match.candidates.forEach { binding.suggestions.addView(option(it.guide, it.reason, choosable = true)) }
        }
    }

    /** One guide channel as a row; a choosable one asks before it is saved. */
    private fun option(guide: EpgGuideChannel, reason: String?, choosable: Boolean): View {
        val b = ItemEpgGuideOptionBinding.inflate(LayoutInflater.from(this), binding.suggestions, false)
        b.tvName.text = guide.displayName
        b.tvSource.text = "${repair.sourceLabel(channel.serverIndex, guide.sourceKey)} · ${guide.xmltvId}"
        b.tvReason.visibility = if (reason.isNullOrBlank()) View.GONE else View.VISIBLE
        b.tvReason.text = reason
        b.tvListings.text = EpgUiText.listings(this, guide)
        if (choosable) {
            b.root.setOnClickListener { confirmApply(guide) }
            b.root.contentDescription = "${guide.displayName}, ${repair.sourceLabel(channel.serverIndex, guide.sourceKey)}. ${reason.orEmpty()}. Select to use it for ${channel.name}."
        } else {
            // Shown, not chosen: still focusable so a TV remote can scroll past it, but does nothing.
            b.root.isClickable = false
        }
        return b.root
    }

    private fun search(query: String, now: Boolean) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            if (!now) delay(SEARCH_DELAY_MS)
            val q = query.trim()
            binding.results.removeAllViews()
            if (q.length < 2) { binding.tvSearchNote.text = ""; return@launch }
            val found = repair.searchGuide(channel.serverIndex, q)
            binding.tvSearchNote.text = when {
                found.isEmpty() -> "No guide channel of this provider's guides matches \"$q\"."
                found.size > SHOWN_RESULTS -> "Showing ${SHOWN_RESULTS} of ${found.size}. Type more to narrow it down."
                else -> "${found.size} found."
            }
            // Listings come with the full rows: fetch them for the ones shown.
            found.take(SHOWN_RESULTS).forEach { g ->
                val full = repair.guideChannel(g.serverIndex, g.sourceKey, g.xmltvId) ?: g
                binding.results.addView(option(full, reason = null, choosable = true))
            }
        }
    }

    private fun confirmApply(guide: EpgGuideChannel) {
        lifecycleScope.launch {
            val recordings = repair.futureRecordings(channel.serverIndex, channel.streamId)
            val message = buildString {
                append("Listings for \"${channel.name}\" will come from \"${guide.displayName}\" (${repair.sourceLabel(channel.serverIndex, guide.sourceKey)}).")
                append("\n\nNext on that guide channel:\n${EpgUiText.listings(this@EpgFixChannelActivity, guide)}")
                append("\n\nThis shows in the TV guide, Quick Peek and the player, and stays through guide refreshes until you restore automatic matching.")
                if (recordings > 0) append("\n\n$recordings scheduled ${if (recordings == 1) "recording keeps its" else "recordings keep their"} channel and times.")
            }
            AlertDialog.Builder(this@EpgFixChannelActivity)
                .setTitle("Use this guide channel?")
                .setMessage(message)
                .setPositiveButton("Use it") { _, _ -> apply(guide) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun apply(guide: EpgGuideChannel) {
        lifecycleScope.launch {
            busy(true)
            binding.tvStatusDetail.text = "Saving and loading listings from \"${guide.displayName}\"…"
            val outcome = repair.applyCorrection(channel, guide)
            markChanged()
            busy(false)
            AlertDialog.Builder(this@EpgFixChannelActivity).setMessage(outcome.message).setPositiveButton("OK", null).show()
            load()
        }
    }

    private fun chooseOffset() {
        val current = status?.override?.offsetMinutes ?: 0
        val hint = repair.report?.issues?.firstOrNull {
            it.channel.serverIndex == channel.serverIndex && it.channel.streamId == channel.streamId && it.kind == EpgIssueKind.OFFSET_HINT
        }?.offsetHint
        val choices = (listOf(0) + EpgTimeOffset.CHOICES + listOfNotNull(hint, current.takeIf { it != 0 })).distinct().sorted()
        val labels = choices.map { m ->
            EpgTimeOffset.label(m) + when (m) { hint -> "  (suggested)"; else -> "" }
        } + "Custom…"
        val checked = choices.indexOf(current)
        AlertDialog.Builder(this)
            .setTitle("Move this channel's listings")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (which == choices.size) chooseCustomOffset(current) else confirmOffset(choices[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseCustomOffset(current: Int) {
        val all = (EpgTimeOffset.MIN_MINUTES..EpgTimeOffset.MAX_MINUTES step EpgTimeOffset.STEP_MINUTES).toList()
        AlertDialog.Builder(this)
            .setTitle("Custom offset")
            .setSingleChoiceItems(all.map { EpgTimeOffset.label(it) }.toTypedArray(), all.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                confirmOffset(all[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmOffset(minutes: Int) {
        if (minutes == (status?.override?.offsetMinutes ?: 0)) return
        lifecycleScope.launch {
            val recordings = repair.futureRecordings(channel.serverIndex, channel.streamId)
            val message = buildString {
                append(
                    if (minutes == 0) "This channel's listings go back to the guide's own times."
                    else "Every listing of this channel moves ${EpgTimeOffset.label(minutes)} from the guide's own times, now and after each refresh."
                )
                append(" Use this only when the programs really start at a different time than the guide says, not for a time zone: the guide already shows times in this device's zone, daylight saving included.")
                if (recordings > 0) append("\n\n$recordings scheduled ${if (recordings == 1) "recording on this channel keeps its" else "recordings on this channel keep their"} current times. Check them after the change.")
            }
            AlertDialog.Builder(this@EpgFixChannelActivity)
                .setTitle(if (minutes == 0) "Remove the time offset?" else "Move listings ${EpgTimeOffset.label(minutes)}?")
                .setMessage(message)
                .setPositiveButton("Move") { _, _ ->
                    lifecycleScope.launch {
                        busy(true)
                        val text = repair.setOffset(channel, minutes)
                        markChanged()
                        busy(false)
                        AlertDialog.Builder(this@EpgFixChannelActivity).setMessage(text).setPositiveButton("OK", null).show()
                        load()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Restore automatic matching?")
            .setMessage("The saved guide channel and time offset for \"${channel.name}\" are removed, and the guide refreshes for its provider.")
            .setPositiveButton("Restore") { _, _ ->
                lifecycleScope.launch {
                    busy(true)
                    val text = repair.resetCorrection(channel.serverIndex, channel.streamId)
                    markChanged()
                    busy(false)
                    AlertDialog.Builder(this@EpgFixChannelActivity).setMessage(text).setPositiveButton("OK", null).show()
                    load()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun markChanged() {
        changed = true
        setResult(RESULT_OK)
    }

    private fun busy(on: Boolean) {
        binding.progress.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnOffset.isEnabled = !on
        binding.btnReset.isEnabled = !on
        setEnabled(binding.suggestions, !on)
        setEnabled(binding.results, !on)
    }

    private fun setEnabled(group: LinearLayout, on: Boolean) {
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = on
    }

    companion object {
        private const val EXTRA_SERVER = "server_index"
        private const val EXTRA_STREAM = "stream_id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_EPG_ID = "epg_id"
        private const val EXTRA_PROVIDER = "provider"
        private const val KEY_CHANGED = "changed"
        private const val SEARCH_DELAY_MS = 350L
        private const val SHOWN_RESULTS = 50

        fun intent(context: Context, channel: EpgLocalChannel, providerName: String): Intent =
            Intent(context, EpgFixChannelActivity::class.java)
                .putExtra(EXTRA_SERVER, channel.serverIndex)
                .putExtra(EXTRA_STREAM, channel.streamId)
                .putExtra(EXTRA_NAME, channel.name)
                .putExtra(EXTRA_EPG_ID, channel.epgChannelId)
                .putExtra(EXTRA_PROVIDER, providerName)
    }
}
