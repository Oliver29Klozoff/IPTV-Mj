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

    private val nowMs get() = System.currentTimeMillis()
    private val startMs get() = run {
        val cal = java.util.Calendar.getInstance()
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
        binding.btnTimelineNow.setOnClickListener { scrollToNow() }

        buildTimeHeader()
        observeGuide()
    }

    private fun observeGuide() {
        binding.timelineProgress.visibility = View.VISIBLE
        viewModel.loadGuide()
        lifecycleScope.launch {
            viewModel.guideRows.collect { rows ->
                if (rows.isNotEmpty()) {
                    binding.timelineProgress.visibility = View.GONE
                    adapter.submitList(rows)
                    binding.rvTimeline.post { scrollToNow() }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.loading.collect { loading ->
                if (rows().isEmpty()) binding.timelineProgress.visibility =
                    if (loading) View.VISIBLE else View.GONE
            }
        }
    }

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

    private fun showTimerDialog(row: GuideRow, program: EpgEntity) {
        val startSec = if (program.startTimestamp < 100_000_000_000L) program.startTimestamp else program.startTimestamp / 1000L
        val startMs = startSec * 1000L
        if (startMs <= nowMs) return  // already started
        val timeStr = SimpleDateFormat("h:mm a", Locale.US).format(Date(startMs))
        AlertDialog.Builder(this)
            .setTitle("Set Reminder")
            .setMessage("Remind me when \"${program.title}\" starts on ${row.channel.name} at $timeStr?")
            .setPositiveButton("Set Reminder") { _, _ ->
                ChannelTimerScheduler.schedule(this, row.channel.streamId, row.channel.name, program.title, startMs)
                android.widget.Toast.makeText(this, "Reminder set for $timeStr", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun playChannel(row: GuideRow) {
        setResult(RESULT_OK, Intent().putExtra("stream_id", row.channel.streamId))
        finish()
    }

    private fun handleProgramClick(row: GuideRow, program: EpgEntity) {
        val nowMs = System.currentTimeMillis()
        val pStartMs = if (program.startTimestamp < 100_000_000_000L) program.startTimestamp * 1000L else program.startTimestamp
        val pStopMs = if (program.stopTimestamp < 100_000_000_000L) program.stopTimestamp * 1000L else program.stopTimestamp

        when {
            pStartMs <= nowMs && pStopMs > nowMs -> {
                // Currently airing — return to home and play in mini player
                playChannel(row)
            }
            pStartMs > nowMs -> {
                // Upcoming — offer to set a reminder (stay in grid)
                showTimerDialog(row, program)
            }
            row.channel.tvArchive == 1 && program.hasArchive == 1 -> {
                // Past with replay archive — return to home and play timeshift in mini player
                lifecycleScope.launch {
                    val startSec = if (program.startTimestamp < 100_000_000_000L) program.startTimestamp
                    else program.startTimestamp / 1000L
                    val durationMin = ((pStopMs - pStartMs) / 60_000L).toInt().coerceAtLeast(1)
                    val url = viewModel.getTimeshiftUrl(row.channel.streamId, startSec, durationMin)
                    setResult(RESULT_OK, Intent()
                        .putExtra("stream_id", row.channel.streamId)
                        .putExtra("timeshift_url", url)
                        .putExtra("timeshift_title", "${row.channel.name} — ${program.title}"))
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
    private val startMs: Long,
    private val onScrollChanged: (Int) -> Unit,
    private val onChannelClick: (GuideRow) -> Unit,
    private val onProgramClick: (GuideRow, EpgEntity) -> Unit,
    private val onProgramLongPress: (GuideRow, EpgEntity) -> Unit
) : RecyclerView.Adapter<TimelineAdapter.ViewHolder>() {

    private var rows: List<GuideRow> = emptyList()
    private var sharedScrollX = 0
    private val scrollViews = mutableListOf<HorizontalScrollView>()
    private var isSyncing = false

    fun submitList(list: List<GuideRow>) {
        rows = list
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

        fun bind(row: GuideRow) {
            tvName.text = row.channel.name
            tvName.setOnClickListener { onChannelClick(row) }
            if (!row.channel.streamIcon.isNullOrBlank()) {
                ivLogo.visibility = View.VISIBLE
                Glide.with(itemView.context).load(row.channel.streamIcon)
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
                val isReplay = row.channel.tvArchive == 1 && program.hasArchive == 1

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
