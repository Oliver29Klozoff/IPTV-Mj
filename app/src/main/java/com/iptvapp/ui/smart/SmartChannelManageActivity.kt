package com.iptvapp.ui.smart

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.data.smart.SmartChannelStore
import com.iptvapp.databinding.ActivitySmartChannelsBinding
import com.iptvapp.databinding.ItemSmartChannelBinding
import com.iptvapp.util.SmartChannels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Review and edit Smart Channel groups. Every action reads or writes the group tables only.
 * Provider catalogs are not refreshed from this screen.
 */
@AndroidEntryPoint
class SmartChannelManageActivity : AppCompatActivity() {

    @Inject lateinit var store: SmartChannelStore
    @Inject lateinit var repository: XtreamRepository
    @Inject lateinit var prefs: PreferencesManager

    private lateinit var binding: ActivitySmartChannelsBinding
    private val rows = ArrayList<Row>()
    private val picked = LinkedHashMap<SmartChannels.Key, SmartChannels.Source>()
    private var query = ""
    private var loadJob: Job? = null
    private val adapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmartChannelsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnUpdate.setOnClickListener { updateFromSavedCatalog() }
        binding.btnAcceptVerified.setOnClickListener { keepVerified() }
        binding.btnGroupSearch.setOnClickListener { groupPicked(acknowledged = false) }
        binding.btnRestore.setOnClickListener { confirmRestore() }
        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty().trim()
                reload()
            }
        })
        reload()
    }

    private fun updateFromSavedCatalog() {
        lifecycleScope.launch {
            val sources = repository.smartChannelSources()
            store.rebuild(sources, System.currentTimeMillis())
            Toast.makeText(this@SmartChannelManageActivity, "Groups updated from the saved catalog", Toast.LENGTH_SHORT).show()
            reload()
        }
    }

    private fun keepVerified() {
        lifecycleScope.launch {
            val snapshot = store.snapshot()
            val verified = snapshot.groups.filter { it.group.reviewState == SmartChannels.VERIFIED }
            verified.forEach { store.acceptGroup(it.group.groupId) }
            Toast.makeText(this@SmartChannelManageActivity, "Kept ${verified.size} verified groups", Toast.LENGTH_SHORT).show()
            reload()
        }
    }

    private fun confirmRestore() {
        val anchor = currentFocus
        AlertDialog.Builder(this)
            .setTitle("Restore grouping defaults")
            .setMessage("Turns Smart Channels off and removes automatic groups. Channels, favorites, manual groups, and recordings stay.")
            .setPositiveButton("Restore") { _, _ ->
                lifecycleScope.launch {
                    store.restoreAutomaticGroups()
                    prefs.setSmartChannelDisplay(SmartChannels.DISPLAY_OFF)
                    prefs.setSmartAutoBackup(SmartChannels.BACKUP_OFF)
                    prefs.setSmartPreferHealthy(SmartChannels.PREFER_SELECTED)
                    reload()
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { DialogFocus.restore(anchor) }
            .show()
    }

    private fun reload() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val snapshot = store.snapshot()
            val needle = query.lowercase()
            val next = ArrayList<Row>()
            fun addSection(title: String, groups: List<SmartChannelStore.GroupSummary>) {
                val matched = groups.filter { summary ->
                    needle.isEmpty() || summary.group.displayName.lowercase().contains(needle) ||
                        summary.group.reason.lowercase().contains(needle) ||
                        summary.members.any { it.displayName.lowercase().contains(needle) }
                }
                if (matched.isEmpty()) return
                next += Row.Header("$title (${matched.size})")
                matched.forEach { next += Row.GroupRow(it) }
            }
            addSection("Verified", snapshot.groups.filter { it.group.reviewState == SmartChannels.VERIFIED })
            addSection("Suggested", snapshot.groups.filter { it.group.reviewState == SmartChannels.SUGGESTED })
            addSection("Needs review", snapshot.groups.filter { it.group.reviewState == SmartChannels.AMBIGUOUS })
            addSection("Grouped by hand", snapshot.groups.filter { it.group.reviewState == SmartChannels.MANUAL })
            val rejections = snapshot.rejections.filter {
                it.reason != SmartChannels.REMOVED && (
                    needle.isEmpty() || it.leftName.lowercase().contains(needle) || it.rightName.lowercase().contains(needle) ||
                        it.reason.lowercase().contains(needle)
                    )
            }
            if (rejections.isNotEmpty()) {
                next += Row.Header("Rejected (${rejections.size})")
                rejections.forEach { next += Row.Rejected(it.leftName, it.rightName, it.reason) }
            }
            if (needle.length >= 2) {
                val memberKeys = snapshot.groups.flatMap { it.members }.map { SmartChannels.Key(it.serverIndex, it.streamId) }.toSet()
                val hits = ArrayList<SmartChannels.Source>()
                try {
                    repository.searchChannels(query).first().take(30).forEach { row ->
                        val source = SmartChannels.Source(-1, row.streamId, row.name, row.epgChannelId.orEmpty(), "")
                        if (source.key !in memberKeys) hits += source
                    }
                    repository.searchMergedChannels(query).first().take(30).forEach { row ->
                        val source = SmartChannels.Source(row.serverIndex, row.streamId, row.name, row.epgChannelId.orEmpty(), row.categoryName.orEmpty())
                        if (source.key !in memberKeys) hits += source
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
                next += Row.Header("Ungrouped search (${hits.size})")
                hits.forEach { next += Row.Hit(it, it.key in picked) }
            } else {
                next += Row.Header("Ungrouped")
                next += Row.Note("Search to find channels that are not in a group. Source favorites stay on each channel.")
            }
            rows.clear()
            rows.addAll(next)
            adapter.notifyDataSetChanged()
        }
    }

    private fun onRow(anchor: View, row: Row) {
        when (row) {
            is Row.GroupRow -> groupMenu(anchor, row.summary)
            is Row.Hit -> {
                if (row.source.key in picked) picked.remove(row.source.key) else picked[row.source.key] = row.source
                reload()
            }
            else -> Unit
        }
    }

    private fun groupMenu(anchor: View, summary: SmartChannelStore.GroupSummary) {
        val group = summary.group
        val names = summary.members.joinToString(" + ") { it.displayName }
        val options = when (group.reviewState) {
            SmartChannels.SUGGESTED -> arrayOf("Group these", "Not the same", "Remove one source")
            SmartChannels.AMBIGUOUS -> arrayOf("Not the same", "Group anyway", "Remove one source")
            else -> arrayOf(
                "Set preferred source",
                if (group.failoverEnabled) "Turn backup off" else "Turn backup on",
                if (group.groupFavorite) "Remove group favorite" else "Favorite this group",
                "Remove one source",
                "Ungroup",
            )
        }
        val title = if (group.reason.isBlank()) names else "$names\n${group.reason}"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Group these" -> lifecycleScope.launch { store.acceptGroup(group.groupId); reload() }
                    "Not the same" -> lifecycleScope.launch { store.rejectSuggestion(group.groupId); reload() }
                    "Group anyway" -> confirmManual(summary)
                    "Turn backup off" -> lifecycleScope.launch { store.setFailover(group.groupId, false); reload() }
                    "Turn backup on" -> lifecycleScope.launch { store.setFailover(group.groupId, true); reload() }
                    "Favorite this group", "Remove group favorite" -> lifecycleScope.launch {
                        store.toggleGroupFavorite(group.groupId)
                        reload()
                    }
                    "Set preferred source" -> pickMember(anchor, summary, "Preferred source") { member ->
                        store.setPreferred(group.groupId, member.serverIndex, member.streamId)
                    }
                    "Remove one source" -> pickMember(anchor, summary, "Remove from group") { member ->
                        store.removeMember(group.groupId, member.serverIndex, member.streamId)
                    }
                    "Ungroup" -> lifecycleScope.launch { store.ungroup(group.groupId); reload() }
                }
            }
            .setNegativeButton("Close", null)
            .setOnDismissListener { DialogFocus.restore(anchor) }
            .show()
    }

    private fun confirmManual(summary: SmartChannelStore.GroupSummary) {
        val sources = summary.members.map { it.toSource() }
        AlertDialog.Builder(this)
            .setTitle("Group anyway")
            .setMessage("${summary.group.reason} Group them only if you know they are the same channel.")
            .setPositiveButton("Group") { _, _ ->
                lifecycleScope.launch {
                    store.ungroup(summary.group.groupId)
                    store.createManual(sources, acknowledgedMismatch = true)
                    reload()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickMember(
        anchor: View,
        summary: SmartChannelStore.GroupSummary,
        title: String,
        onPick: suspend (com.iptvapp.data.local.entities.SmartChannelMemberEntity) -> Unit,
    ) {
        val labels = summary.members.map { member ->
            val where = if (member.serverIndex == -1) "Main provider" else "Provider ${member.serverIndex + 1}"
            "$where · ${member.displayName}"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                lifecycleScope.launch {
                    onPick(summary.members[which])
                    reload()
                }
            }
            .setNegativeButton("Close", null)
            .setOnDismissListener { DialogFocus.restore(anchor) }
            .show()
    }

    private fun groupPicked(acknowledged: Boolean) {
        val sources = picked.values.toList()
        if (sources.size < 2) {
            Toast.makeText(this, "Search and tap at least two ungrouped channels", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val reason = store.createManual(sources, acknowledged)
            if (reason != null && !acknowledged) {
                AlertDialog.Builder(this@SmartChannelManageActivity)
                    .setTitle("These may not be the same channel")
                    .setMessage(reason)
                    .setPositiveButton("Group anyway") { _, _ -> groupPicked(acknowledged = true) }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else if (reason == null) {
                picked.clear()
                Toast.makeText(this@SmartChannelManageActivity, "Grouped by hand", Toast.LENGTH_SHORT).show()
                reload()
            }
        }
    }

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Note(val text: String) : Row()
        data class GroupRow(val summary: SmartChannelStore.GroupSummary) : Row()
        data class Rejected(val left: String, val right: String, val reason: String) : Row()
        data class Hit(val source: SmartChannels.Source, val selected: Boolean) : Row()
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {
        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemSmartChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val (title, detail) = when (row) {
                is Row.Header -> row.title to ""
                is Row.Note -> row.text to ""
                is Row.Rejected -> "${row.left} + ${row.right}" to "Not equivalent. ${row.reason}"
                is Row.Hit -> {
                    val where = if (row.source.serverIndex == -1) "Main provider" else "Provider ${row.source.serverIndex + 1}"
                    val mark = if (row.selected) "Selected · " else ""
                    "$mark${row.source.name}" to where
                }
                is Row.GroupRow -> {
                    val names = row.summary.members.take(4).joinToString(" + ") { it.displayName }
                    val extra = row.summary.members.size
                    "$names" to "${row.summary.group.reason} $extra sources."
                }
            }
            holder.binding.title.text = title
            holder.binding.detail.text = detail
            holder.binding.detail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener { onRow(holder.itemView, row) }
        }

        inner class Holder(val binding: ItemSmartChannelBinding) : RecyclerView.ViewHolder(binding.root)
    }

    private fun com.iptvapp.data.local.entities.SmartChannelMemberEntity.toSource() = SmartChannels.Source(
        serverIndex, streamId, displayName, epgChannelId, "",
    )

    companion object {
        fun intent(context: Context): Intent = Intent(context, SmartChannelManageActivity::class.java)
    }
}
