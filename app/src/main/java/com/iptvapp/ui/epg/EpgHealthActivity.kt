package com.iptvapp.ui.epg

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.iptvapp.R
import com.iptvapp.data.epg.EpgHealthReport
import com.iptvapp.data.epg.EpgIssue
import com.iptvapp.data.epg.EpgIssueKind
import com.iptvapp.data.epg.EpgLocalChannel
import com.iptvapp.data.epg.EpgRepairRepository
import com.iptvapp.data.epg.EpgSourceResult
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityEpgHealthBinding
import com.iptvapp.databinding.ItemEpgIssueBinding
import com.iptvapp.worker.EpgRefreshWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

/**
 * EPG Health: how complete the guide is, what is wrong with it, and the way to each channel's
 * correction screen. Opening it checks the guide already on the device (no downloads); "Refresh
 * guide now" runs the normal guide refresh, once (an already running one is kept).
 */
@AndroidEntryPoint
class EpgHealthActivity : AppCompatActivity() {

    @Inject lateinit var repair: EpgRepairRepository
    @Inject lateinit var repository: XtreamRepository

    private lateinit var binding: ActivityEpgHealthBinding
    private val adapter = IssueAdapter { open(it) }

    private enum class Tab(val label: String) {
        FIXES("Suggested fixes"), MISSING("Without listings"), STALE("Stale"), SUSPECT("Possible mismatches"),
        OFFSETS("Time offsets"), CORRECTIONS("Corrections"), ALL("All channels")
    }

    private var tab = Tab.FIXES
    private var filter = ""
    private var report: EpgHealthReport? = null
    private var allChannels: List<EpgIssue>? = null
    private var busy = false
    // The channel whose screen was opened, so focus comes back to it.
    private var returnTo: Pair<Int, Int>? = null

    private val fixScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) repair.scan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgHealthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        savedInstanceState?.getString(KEY_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name }?.let { tab = it } }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnScan.setOnClickListener { repair.scan() }
        binding.btnApplySafe.setOnClickListener { confirmSafeMatches() }
        binding.btnRefresh.setOnClickListener { refreshGuide() }
        binding.btnSources.setOnClickListener {
            binding.tvSources.visibility = if (binding.tvSources.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.btnResetAll.setOnClickListener { confirmResetAll() }
        binding.etFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filter = s?.toString().orEmpty().trim(); showList() }
        })
        buildTabs()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repair.state.collect { render(it) }
            }
        }
        // A scan reads only the guide on the device; reopening shows the last one until a new one finishes.
        if (savedInstanceState == null) repair.scan()
        binding.btnScan.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab.name)
    }

    private fun render(state: EpgRepairRepository.ScanState) {
        when (state) {
            is EpgRepairRepository.ScanState.Scanning -> {
                binding.progress.visibility = View.VISIBLE
                binding.progress.isIndeterminate = state.total == 0
                if (state.total > 0) { binding.progress.max = state.total; binding.progress.progress = state.done }
                if (report == null) binding.tvCoverage.text = "Checking the guide…"
            }
            is EpgRepairRepository.ScanState.Done -> {
                binding.progress.visibility = View.GONE
                report = state.report
                allChannels = null
                showSummary(state.report)
                buildTabs()
                showList()
            }
            is EpgRepairRepository.ScanState.Failed -> {
                binding.progress.visibility = View.GONE
                binding.tvCoverage.text = state.message
            }
            EpgRepairRepository.ScanState.Idle -> Unit
        }
        updateButtons()
    }

    private fun showSummary(r: EpgHealthReport) {
        val n = NumberFormat.getIntegerInstance(Locale.getDefault())
        val t = r.totals
        binding.tvCoverage.text = if (t.checked == 0) "No channels to check yet" else "Guide coverage: ${t.coveragePercent}%"
        binding.tvCounts.text = buildString {
            append("${n.format(t.checked)} channels checked · ${n.format(t.withListings)} with listings · ${n.format(t.withoutListings)} without")
            append("\n${n.format(t.fixable)} with a suggested match (${n.format(t.safeSuggestions)} safe to apply) · ${n.format(t.unavailable)} with no listings available")
            append("\n${n.format(t.stale)} stale · ${n.format(t.suspect)} possibly mismatched · ${n.format(t.corrections)} ${if (t.corrections == 1) "correction" else "corrections"} saved")
            if (t.brokenCorrections > 0) append(" (${n.format(t.brokenCorrections)} need attention)")
            if (r.unusedCorrections.isNotEmpty()) append("\n${r.unusedCorrections.size} corrections belong to a provider that is no longer set up; they are kept but not used")
        }
        binding.tvUpdated.text = "Last successful guide update: ${EpgUiText.dateTime(t.lastSuccessfulUpdate)}"
        binding.tvSources.text = r.sources.joinToString("\n\n") { s ->
            val rec = s.record
            val head = "${s.providerName} · ${s.label}"
            val state = when {
                rec == null -> ""
                rec.lastResult == EpgSourceResult.OK.name -> "\n${n.format(rec.channelCount)} channels, ${n.format(rec.programCount)} listings · updated ${EpgUiText.ago(rec.lastSuccessAt)}"
                else -> "\nLast tried ${EpgUiText.ago(rec.lastAttemptAt)}"
            }
            head + state + s.notes.joinToString("") { "\n• $it" }
        }.ifEmpty { "No guide sources yet." }
    }

    private fun count(t: Tab): Int? {
        val r = report ?: return null
        return when (t) {
            Tab.ALL -> null
            else -> r.issues.count { inTab(it, t) }
        }
    }

    private fun inTab(issue: EpgIssue, t: Tab) = when (t) {
        Tab.FIXES -> issue.kind == EpgIssueKind.MISSING_FIXABLE
        Tab.MISSING -> issue.kind == EpgIssueKind.MISSING_UNAVAILABLE
        Tab.STALE -> issue.kind == EpgIssueKind.STALE
        Tab.SUSPECT -> issue.kind == EpgIssueKind.SUSPECT
        Tab.OFFSETS -> issue.kind == EpgIssueKind.OFFSET_HINT
        Tab.CORRECTIONS -> issue.kind == EpgIssueKind.CORRECTED || issue.kind == EpgIssueKind.CORRECTION_BROKEN
        Tab.ALL -> true
    }

    private fun buildTabs() {
        val hadFocus = binding.tabs.findFocus() != null
        binding.tabs.removeAllViews()
        for (t in Tab.entries) {
            val layout = if (t == tab) R.layout.item_epg_tab_selected else R.layout.item_epg_tab
            val b = LayoutInflater.from(this).inflate(layout, binding.tabs, false) as Button
            b.text = count(t)?.let { "${t.label} ($it)" } ?: t.label
            b.isSelected = t == tab
            b.setOnClickListener {
                if (tab != t) { tab = t; buildTabs(); showList() }
            }
            binding.tabs.addView(b)
            if (hadFocus && t == tab) b.post { b.requestFocus() }
        }
    }

    private fun showList() {
        val r = report
        if (tab == Tab.ALL && allChannels == null && r != null) {
            loadAllChannels(r)
            return
        }
        val source = if (tab == Tab.ALL) allChannels.orEmpty() else r?.issues.orEmpty().filter { inTab(it, tab) }
        val items = if (filter.length < 2) source else source.filter { it.channel.name.contains(filter, ignoreCase = true) }
        adapter.submit(items)
        binding.tvEmpty.visibility = if (items.isEmpty() && r != null) View.VISIBLE else View.GONE
        binding.tvEmpty.text = when {
            filter.length >= 2 -> "No channel here matches \"$filter\"."
            tab == Tab.FIXES -> "No channel without listings has a match in your guides."
            tab == Tab.MISSING -> "Every channel without listings has a suggested match, or none is missing."
            tab == Tab.CORRECTIONS -> "No corrections saved. Open a channel to choose its guide channel or move its times."
            tab == Tab.STALE -> "No channel's listings have run out or end early."
            tab == Tab.SUSPECT -> "No channel's listings look like they belong to another feed or station."
            tab == Tab.OFFSETS -> "No channel's times look off compared with the provider's own guide."
            else -> "No channels."
        }
        returnTo?.let { (s, id) ->
            val pos = items.indexOfFirst { it.channel.serverIndex == s && it.channel.streamId == id }
            if (pos >= 0) {
                binding.list.scrollToPosition(pos)
                binding.list.post { binding.list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
            } else {
                // Fixed, so no longer in this list: focus stays on the list's category, not the top of the screen.
                binding.tabs.post { binding.tabs.getChildAt(Tab.entries.indexOf(tab))?.requestFocus() }
            }
            returnTo = null
        }
    }

    /** "All channels": every channel of every provider, for matching one by hand (local only). */
    private fun loadAllChannels(r: EpgHealthReport) {
        lifecycleScope.launch {
            val byKey = r.issues.associateBy { it.channel.serverIndex to it.channel.streamId }
            val out = ArrayList<EpgIssue>()
            for (p in repository.epgHealthProviders()) {
                for (ch in repository.epgHealthChannels(p.serverIndex)) {
                    out += byKey[ch.serverIndex to ch.streamId] ?: EpgIssue(
                        EpgIssueKind.OK, ch, p.name, "Has listings — open to check or change its guide channel", null, null, null, null, false, 0
                    )
                }
            }
            allChannels = out.sortedBy { it.channel.name.lowercase(Locale.getDefault()) }
            if (tab == Tab.ALL) showList()
        }
    }

    private fun open(issue: EpgIssue) {
        returnTo = issue.channel.serverIndex to issue.channel.streamId
        fixScreen.launch(EpgFixChannelActivity.intent(this, issue.channel, issue.providerName))
    }

    private fun updateButtons() {
        val scanning = repair.state.value is EpgRepairRepository.ScanState.Scanning
        // Stays enabled while scanning (a press restarts the scan), so a TV remote's focus isn't thrown off it.
        binding.btnScan.isEnabled = !busy
        binding.btnApplySafe.isEnabled = !busy && !scanning && (report?.totals?.safeSuggestions ?: 0) > 0
        binding.btnResetAll.isEnabled = !busy
        binding.btnRefresh.isEnabled = !busy
    }

    private fun confirmSafeMatches() {
        val r = report ?: return
        val safe = r.issues.filter { it.kind == EpgIssueKind.MISSING_FIXABLE && it.safe }
        if (safe.isEmpty()) return
        val list = safe.take(12).joinToString("\n") { "• ${it.channel.name} → ${it.match?.best?.guide?.displayName}" } +
            if (safe.size > 12) "\n…and ${safe.size - 12} more" else ""
        AlertDialog.Builder(this)
            .setTitle("Apply ${safe.size} safe ${if (safe.size == 1) "match" else "matches"}?")
            .setMessage(
                "Only matches with the channel's own guide ID, or a single same-named guide channel with no other feeds, are applied. " +
                    "Possible and ambiguous matches stay for you to review.\n\n$list\n\n" +
                    "Their listings are downloaded from the guides they come from. Scheduled recordings keep their times."
            )
            .setPositiveButton("Apply") { _, _ -> applySafe(r) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applySafe(r: EpgHealthReport) {
        busy = true
        updateButtons()
        binding.progress.visibility = View.VISIBLE
        binding.progress.isIndeterminate = true
        binding.tvUpdated.text = "Applying matches and loading their listings…"
        lifecycleScope.launch {
            val outcome = repair.applySafeMatches(r)
            busy = false
            binding.progress.visibility = View.GONE
            updateButtons()
            AlertDialog.Builder(this@EpgHealthActivity).setMessage(outcome.message).setPositiveButton("OK", null).show()
            repair.scan()
        }
    }

    private fun confirmResetAll() {
        AlertDialog.Builder(this)
            .setTitle("Restore automatic matching?")
            .setMessage("Every saved guide correction and time offset is removed, and the guide refreshes for the providers that had a corrected channel.")
            .setPositiveButton("Restore") { _, _ ->
                busy = true
                updateButtons()
                binding.progress.visibility = View.VISIBLE
                binding.progress.isIndeterminate = true
                lifecycleScope.launch {
                    val message = repair.resetAll()
                    busy = false
                    binding.progress.visibility = View.GONE
                    updateButtons()
                    AlertDialog.Builder(this@EpgHealthActivity).setMessage(message).setPositiveButton("OK", null).show()
                    repair.scan()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The normal guide refresh, once: one already running is kept, not restarted. */
    private fun refreshGuide() {
        val wm = WorkManager.getInstance(applicationContext)
        val request = OneTimeWorkRequestBuilder<EpgRefreshWorker>()
            .setInputData(workDataOf(EpgRefreshWorker.KEY_MISSING_ONLY to false))
            .build()
        wm.enqueueUniqueWork(EpgRefreshWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        binding.tvUpdated.text = "Refreshing the guide…"
        busy = true
        updateButtons()
        wm.getWorkInfosForUniqueWorkLiveData(EpgRefreshWorker.UNIQUE_WORK_NAME).observe(this) { infos ->
            val info = infos?.firstOrNull() ?: return@observe
            if (info.state.isFinished) {
                busy = false
                updateButtons()
                if (info.state == WorkInfo.State.SUCCEEDED) repair.scan()
                else binding.tvUpdated.text = "The guide refresh didn't finish. The guide you had is kept."
                wm.getWorkInfosForUniqueWorkLiveData(EpgRefreshWorker.UNIQUE_WORK_NAME).removeObservers(this)
            } else {
                info.progress.getString(EpgRefreshWorker.KEY_STATUS)?.let { binding.tvUpdated.text = it }
            }
        }
    }

    private class IssueAdapter(private val onOpen: (EpgIssue) -> Unit) : RecyclerView.Adapter<IssueAdapter.Holder>() {
        private var items: List<EpgIssue> = emptyList()

        fun submit(list: List<EpgIssue>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(val b: ItemEpgIssueBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemEpgIssueBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val issue = items[position]
            val b = holder.b
            b.tvTitle.text = "${issue.channel.name} · ${issue.providerName}"
            b.tvDetail.text = issue.detail
            val best = issue.match?.best
            if (best != null && issue.kind != EpgIssueKind.CORRECTED) {
                b.tvSuggestion.visibility = View.VISIBLE
                b.tvSuggestion.text = "${EpgUiText.state(issue.match.state)}: ${best.guide.displayName}" +
                    if (issue.match.candidates.size > 1) " (+${issue.match.candidates.size - 1} more)" else ""
            } else if (issue.offsetHint != null) {
                b.tvSuggestion.visibility = View.VISIBLE
                b.tvSuggestion.text = "Suggested offset: ${com.iptvapp.data.epg.EpgTimeOffset.label(issue.offsetHint)}"
            } else {
                b.tvSuggestion.visibility = View.GONE
            }
            b.root.contentDescription = "${issue.channel.name}, ${EpgUiText.kind(issue.kind)}. ${issue.detail}"
            b.root.setOnClickListener { onOpen(issue) }
        }
    }

    companion object {
        private const val KEY_TAB = "tab"

        fun intent(context: android.content.Context) = Intent(context, EpgHealthActivity::class.java)
    }
}
