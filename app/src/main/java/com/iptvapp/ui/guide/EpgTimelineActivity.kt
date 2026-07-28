package com.iptvapp.ui.guide

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.R
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.databinding.ActivityEpgTimelineBinding
import com.iptvapp.ui.home.HomeViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class EpgTimelineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEpgTimelineBinding
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var adapter: TimelineAdapter

    // 4dp per minute — 30min=120dp, 1hr=240dp
    private val dpPerMin = 4f
    // Show from 3 hours ago to 9 hours from now
    private val hoursBack = 3
    private val hoursAhead = 9

    // 0 = today, +1 = tomorrow, -1 = yesterday, etc. The window itself always starts
    // hoursBack before "now" ON that day, same shape as before — paging days just shifts
    // which day "now" is computed relative to, rather than changing the window's length.
    // Whether tomorrow/yesterday actually has any programs depends entirely on how far out
    // this device's configured EPG sources reach — get_short_epg typically only covers a few
    // hours ahead, so most users will only see real data for today+part of tomorrow unless
    // they have an XMLTV source configured with a longer guide. Paging still works either
    // way; a day with no cached data just renders an empty grid, same as a live EPG app would.
    private var dayOffset = 0

    private val nowMs get() = System.currentTimeMillis()
    private val startMs get() = run {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_MONTH, dayOffset)
        cal.add(java.util.Calendar.HOUR_OF_DAY, -hoursBack)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgTimelineBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemUi()

        adapter = TimelineAdapter(
            dpPerMin = dpPerMin,
            startMs = startMs,
            onScrollChanged = { scrollX -> syncScroll(scrollX) },
            onChannelClick = { row -> playChannel(row) },
            onProgramClick = { row, program -> handleProgramClick(row, program) },
            onProgramLongPress = { row, program -> showTimerDialog(row, program) }
        )

        binding.rvTimeline.layoutManager = LinearLayoutManager(this)
        binding.rvTimeline.adapter = adapter

        binding.btnTimelineBack.setOnClickListener { finish() }
        binding.btnTimelineNow.setOnClickListener {
            if (dayOffset != 0) changeDay(-dayOffset) else scrollToNow()
        }
        binding.btnTimelinePrevDay.setOnClickListener { changeDay(-1) }
        binding.btnTimelineNextDay.setOnClickListener { changeDay(1) }
        binding.etTimelineSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { applySearchFilter(s.toString()) }
        })

        updateDayLabel()
        buildTimeHeader()
        observeGuide()
    }

    private fun updateDayLabel() {
        binding.tvTimelineDay.text = when (dayOffset) {
            0 -> "Today"
            1 -> "Tomorrow"
            -1 -> "Yesterday"
            else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(startMs))
        }
    }

    // Rebuilding both the time header (its labels are computed from startMs) and the
    // adapter's own copy of startMs (each program block's position depends on it) keeps
    // everything in sync — changing just one and not the other would silently misalign the
    // program blocks with the header's time labels.
    private fun changeDay(delta: Int) {
        dayOffset += delta
        updateDayLabel()
        buildTimeHeader()
        adapter.updateStartMs(startMs)
        applySearchFilter(binding.etTimelineSearch.text?.toString() ?: "")
        binding.rvTimeline.post {
            if (dayOffset == 0) {
                scrollToNow()
            } else {
                binding.timeHeaderScroll.scrollTo(0, 0)
                adapter.scrollAllTo(0)
            }
        }
    }

    // Previously only matched the CHANNEL name — searching a show/program title (e.g. "NFL")
    // found nothing unless the channel itself happened to be named that, even though every
    // GuideRow already carries its full programs list. Now matches either.
    private fun applySearchFilter(query: String) {
        val q = query.trim()
        val filtered = if (q.isBlank()) rows() else rows().filter { row ->
            row.name.contains(q, ignoreCase = true) ||
                row.programs.any { it.title.contains(q, ignoreCase = true) }
        }
        adapter.submitList(filtered)
    }

    private fun observeGuide() {
        binding.timelineProgress.visibility = View.VISIBLE
        viewModel.loadGuide()
        lifecycleScope.launch {
            viewModel.guideRows.collect { rows ->
                if (rows.isNotEmpty()) {
                    binding.timelineProgress.visibility = View.GONE
                    binding.tvTimelineEmpty?.visibility = View.GONE
                    applySearchFilter(binding.etTimelineSearch.text?.toString() ?: "")
                    if (dayOffset == 0) binding.rvTimeline.post { scrollToNow() }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.loading.collect { loading ->
                if (rows().isEmpty()) {
                    binding.timelineProgress.visibility = if (loading) View.VISIBLE else View.GONE
                    // Cold-start-with-no-connectivity case (see loadGuide kdoc): nothing was ever
                    // cached, the fetch just finished (successfully or not) and there's still
                    // nothing to show — silently staying blank looked identical to a bug.
                    binding.tvTimelineEmpty?.visibility = if (!loading) View.VISIBLE else View.GONE
                }
            }
        }
    }

    // Merged/secondary-provider rows are included alongside primary now — recording/timeshift/
    // "Remind Me" simply aren't offered for them (see showTimerDialog/handleProgramClick),
    // matching the same primary-only gating the Guide list view already uses.
    private fun rows() = viewModel.guideRows.value

    private fun buildTimeHeader() {
        val container = binding.timeHeaderContent
        container.removeAllViews()
        val totalMinutes = (hoursBack + hoursAhead) * 60
        val slotMinutes = 30
        val slotCount = totalMinutes / slotMinutes
        val slotWidthDp = dpPerMin * slotMinutes
        for (i in 0 until slotCount) {
            val slotStartMs = startMs + i * slotMinutes * 60_000L
            val label = SimpleDateFormat("h:mm a", Locale.US).format(Date(slotStartMs))
            val tv = TextView(this).apply {
                text = label
                setTextColor(0xFF888888.toInt())
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(dpToPx(slotWidthDp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                setPadding(dpToPx(6f).toInt(), 0, 0, 0)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            container.addView(tv)
            // Divider
            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(1f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(0xFF2A2A2A.toInt())
            }
            container.addView(div)
        }
        // Sync header scroll with row scrolls
        binding.timeHeaderScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            adapter.setSharedScrollX(scrollX, sourceView = binding.timeHeaderScroll)
        }
    }

    private fun syncScroll(scrollX: Int) {
        binding.timeHeaderScroll.scrollTo(scrollX, 0)
    }

    private fun scrollToNow() {
        val offsetMs = nowMs - startMs
        val offsetMin = offsetMs / 60_000f
        val offsetPx = dpToPx(offsetMin * dpPerMin).toInt() - dpToPx(120f).toInt()
        binding.timeHeaderScroll.smoothScrollTo(offsetPx.coerceAtLeast(0), 0)
        adapter.scrollAllTo(offsetPx.coerceAtLeast(0))
    }

    fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    // Record/Remind Me are primary-provider-only concepts (RecordingSchedulerActivity/
    // ChannelTimerScheduler have no serverIndex support) — a merged row only ever gets the
    // plain "OK" dialog with no actionable buttons, same spirit as the Guide list view gating
    // onReplayClick off row.supportsReplay for merged rows.
    private fun showTimerDialog(row: GuideRow, program: EpgEntity) {
        val startSec = if (program.startTimestamp < 100_000_000_000L) program.startTimestamp else program.startTimestamp / 1000L
        val startMs = startSec * 1000L
        if (startMs <= nowMs) return
        val stopMs = if (program.stopTimestamp < 100_000_000_000L) program.stopTimestamp * 1000L else program.stopTimestamp
        val durationMs = (stopMs - startMs).coerceAtLeast(60_000L)
        val timeStr = SimpleDateFormat("h:mm a", Locale.US).format(Date(startMs))
        val ch = row.channel
        val builder = AlertDialog.Builder(this)
            .setTitle("\"${program.title}\"")
            .setMessage("${row.name} · $timeStr")
            .setNegativeButton("Cancel", null)
        if (ch != null) {
            builder.setPositiveButton("Record") { _, _ ->
                startActivity(
                    Intent(this, com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java)
                        .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_STREAM_ID, ch.streamId)
                        .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_START_MS, startMs)
                        .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_DURATION_MS, durationMs)
                )
            }
            builder.setNeutralButton("Remind Me") { _, _ ->
                ChannelTimerScheduler.schedule(this, ch.streamId, ch.name, program.title, startMs)
                android.widget.Toast.makeText(this, "Reminder set for $timeStr", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }

    private fun playChannel(row: GuideRow) {
        val mergedCh = row.mergedChannel
        if (mergedCh != null) {
            setResult(RESULT_OK, Intent()
                .putExtra("stream_id", -1)
                .putExtra("server_index", mergedCh.serverIndex)
                .putExtra("merged_stream_id", mergedCh.streamId))
        } else {
            setResult(RESULT_OK, Intent().putExtra("stream_id", row.channel!!.streamId))
        }
        finish()
    }

    private fun handleProgramClick(row: GuideRow, program: EpgEntity) {
        val nowMs = System.currentTimeMillis()
        val pStartMs = if (program.startTimestamp < 100_000_000_000L) program.startTimestamp * 1000L else program.startTimestamp
        val pStopMs = if (program.stopTimestamp < 100_000_000_000L) program.stopTimestamp * 1000L else program.stopTimestamp
        val ch = row.channel

        when {
            pStartMs <= nowMs && pStopMs > nowMs -> {
                // Currently airing — return to home and play in mini player
                playChannel(row)
            }
            pStartMs > nowMs -> {
                // Upcoming — offer to set a reminder (stay in grid)
                showTimerDialog(row, program)
            }
            ch != null && ch.tvArchive == 1 && program.hasArchive == 1 -> {
                // Past with replay archive — return to home and play timeshift in mini player.
                // Timeshift is primary-only (merged channels have no tvArchive), so this branch
                // never applies to a merged row — ch is smart-cast non-null here.
                lifecycleScope.launch {
                    val startSec = if (program.startTimestamp < 100_000_000_000L) program.startTimestamp
                    else program.startTimestamp / 1000L
                    val durationMin = ((pStopMs - pStartMs) / 60_000L).toInt().coerceAtLeast(1)
                    val url = viewModel.getTimeshiftUrl(ch.streamId, startSec, durationMin)
                    setResult(RESULT_OK, Intent()
                        .putExtra("stream_id", ch.streamId)
                        .putExtra("timeshift_url", url)
                        .putExtra("timeshift_title", "${ch.name} — ${program.title}"))
                    finish()
                }
            }
            else -> {
                // Past, no archive — return to home and play live
                playChannel(row)
            }
        }
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }
}

class TimelineAdapter(
    private val dpPerMin: Float,
    startMs: Long,
    private val onScrollChanged: (Int) -> Unit,
    private val onChannelClick: (GuideRow) -> Unit,
    private val onProgramClick: (GuideRow, EpgEntity) -> Unit,
    private val onProgramLongPress: (GuideRow, EpgEntity) -> Unit
) : RecyclerView.Adapter<TimelineAdapter.ViewHolder>() {

    // Mutable so day-paging can shift the whole grid's time window without recreating the
    // adapter (and losing its RecyclerView scroll-sync state) — each ViewHolder reads the
    // current value at bind time via the enclosing instance, not a constructor-captured copy.
    private var startMs: Long = startMs

    private var rows: List<GuideRow> = emptyList()
    private var sharedScrollX = 0
    private val scrollViews = mutableListOf<HorizontalScrollView>()
    private var isSyncing = false

    fun submitList(list: List<GuideRow>) {
        rows = list
        notifyDataSetChanged()
    }

    fun updateStartMs(newStartMs: Long) {
        startMs = newStartMs
        notifyDataSetChanged()
    }

    fun setSharedScrollX(x: Int, sourceView: View? = null) {
        if (isSyncing) return
        isSyncing = true
        sharedScrollX = x
        scrollViews.forEach { sv -> if (sv !== sourceView) sv.scrollTo(x, 0) }
        onScrollChanged(x)
        isSyncing = false
    }

    fun scrollAllTo(x: Int) {
        sharedScrollX = x
        scrollViews.forEach { it.smoothScrollTo(x, 0) }
        onScrollChanged(x)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_epg_timeline_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    override fun onViewRecycled(holder: ViewHolder) {
        scrollViews.remove(holder.scrollView)
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivLogo: ImageView = view.findViewById(R.id.ivTimelineChannelLogo)
        private val tvName: TextView = view.findViewById(R.id.tvTimelineChannelName)
        val scrollView: HorizontalScrollView = view.findViewById(R.id.programRowScroll)
        private val container: LinearLayout = view.findViewById(R.id.programRowContainer)

        private val providerStripe: View? = view.findViewById(R.id.viewTimelineProviderStripe)

        fun bind(row: GuideRow) {
            tvName.text = row.name
            tvName.setOnClickListener { onChannelClick(row) }
            providerStripe?.setBackgroundColor(providerColorFor(row.serverIndex) ?: 0x00000000)
            if (!row.streamIcon.isNullOrBlank()) {
                ivLogo.visibility = View.VISIBLE
                Glide.with(itemView.context).load(row.streamIcon)
                    .placeholder(android.R.drawable.ic_media_play)
                    .error(android.R.drawable.ic_media_play)
                    .into(ivLogo)
                ivLogo.setOnClickListener { onChannelClick(row) }
            } else {
                ivLogo.visibility = View.GONE
            }

            container.removeAllViews()
            buildProgramBlocks(row, container, itemView.context)

            if (!scrollViews.contains(scrollView)) scrollViews.add(scrollView)
            scrollView.scrollTo(sharedScrollX, 0)
            scrollView.setOnScrollChangeListener { _, x, _, _, _ ->
                setSharedScrollX(x, scrollView)
            }
        }

        private fun buildProgramBlocks(row: GuideRow, container: LinearLayout, ctx: Context) {
            val nowMs = System.currentTimeMillis()
            val endMs = startMs + (12 * 60 * 60_000L)
            val dpPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpPerMin, ctx.resources.displayMetrics)

            // Gap filler before first program
            if (row.programs.isNotEmpty()) {
                val firstStartMs = toMs(row.programs.first().startTimestamp)
                if (firstStartMs > startMs) {
                    val gapMin = ((firstStartMs - startMs) / 60_000f)
                    container.addView(makeGap((gapMin * dpPx).toInt(), ctx))
                }
            }

            row.programs.forEach { program ->
                val pStartMs = toMs(program.startTimestamp)
                val pStopMs = toMs(program.stopTimestamp)
                if (pStopMs < startMs || pStartMs > endMs) return@forEach

                val durationMin = ((pStopMs - pStartMs) / 60_000f).coerceAtLeast(5f)
                val widthPx = (durationMin * dpPx).toInt()
                val isNow = pStartMs <= nowMs && pStopMs > nowMs
                val isReplay = row.supportsReplay && program.hasArchive == 1

                val block = TextView(ctx).apply {
                    text = program.title
                    textSize = 11f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dpToPx(6f, ctx).toInt(), 0, dpToPx(6f, ctx).toInt(), 0)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val bgColor = when {
                        isNow -> 0xFF003366.toInt()
                        isReplay -> 0xFF1A2A1A.toInt()
                        else -> 0xFF1C1C1C.toInt()
                    }
                    setBackgroundColor(bgColor)
                    setTextColor(if (isNow) 0xFF00AAFF.toInt() else 0xFFCCCCCC.toInt())
                    layoutParams = LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        setMargins(dpToPx(1f, ctx).toInt(), dpToPx(2f, ctx).toInt(), dpToPx(1f, ctx).toInt(), dpToPx(2f, ctx).toInt())
                    }
                    setOnClickListener { onProgramClick(row, program) }
                    setOnLongClickListener {
                        onProgramLongPress(row, program)
                        true
                    }
                }
                container.addView(block)
            }
        }

        private fun makeGap(widthPx: Int, ctx: Context): View = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(0xFF111111.toInt())
        }

        private fun toMs(ts: Long) = if (ts < 100_000_000_000L) ts * 1000L else ts
        private fun dpToPx(dp: Float, ctx: Context) =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.resources.displayMetrics)
    }
}
