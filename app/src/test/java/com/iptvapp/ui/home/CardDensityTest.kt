package com.iptvapp.ui.home

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.R
import com.iptvapp.data.home.CardDensity
import com.iptvapp.data.home.HomeCard
import com.iptvapp.data.home.HomeLayout
import com.iptvapp.data.home.HomeRow
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.search.SearchHit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Card Density & Size: the setting itself, and the sizes it gives Home's cards and the TV's grids. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class CardDensityTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val d get() = context.resources.displayMetrics.density

    @Test
    fun anUnknownOrMissingValueIsStandard() {
        assertEquals(CardDensity.STANDARD, CardDensity.fromId(null))
        assertEquals(CardDensity.STANDARD, CardDensity.fromId(""))
        assertEquals(CardDensity.STANDARD, CardDensity.fromId("huge"))
        assertEquals(CardDensity.COMPACT, CardDensity.fromId("CoMpAcT"))
        assertEquals(CardDensity.EXPANDED, CardDensity.fromId("expanded"))
        for (c in CardDensity.entries) assertEquals(c, CardDensity.fromId(c.id))
    }

    @Test
    fun scalesAndGridColumns() {
        assertEquals(0.82f, CardDensity.COMPACT.scaleMultiplier)
        assertEquals(1f, CardDensity.STANDARD.scaleMultiplier)
        assertEquals(1.22f, CardDensity.EXPANDED.scaleMultiplier)
        assertEquals(6, CardDensity.COMPACT.gridSpan())
        assertEquals(5, CardDensity.STANDARD.gridSpan())
        assertEquals(4, CardDensity.EXPANDED.gridSpan())
        // Kept between 4 and 7 whatever the base.
        assertEquals(4, CardDensity.EXPANDED.gridSpan(4))
        assertEquals(7, CardDensity.COMPACT.gridSpan(7))
        // Standard keeps the TV's row slot; Compact shrinks it and Expanded grows it.
        fun slot(c: CardDensity) = context.resources.getDimensionPixelSize(SmartHomeAdapter.tvRowSlotRes(c))
        assertEquals(context.resources.getDimensionPixelSize(R.dimen.tv_home_row_slot), slot(CardDensity.STANDARD))
        assertTrue(slot(CardDensity.COMPACT) < slot(CardDensity.STANDARD))
        assertTrue(slot(CardDensity.EXPANDED) > slot(CardDensity.STANDARD))
    }

    @Test
    fun theSettingIsSavedAndReadBack(): Unit = runBlocking {
        val prefs = PreferencesManager(context)
        prefs.setCardDensity(CardDensity.STANDARD)
        assertEquals(CardDensity.STANDARD, prefs.cardDensity.first())
        prefs.setCardDensity(CardDensity.EXPANDED)
        assertEquals(CardDensity.EXPANDED, PreferencesManager(context).cardDensity.first())
        prefs.setCardDensity(CardDensity.COMPACT)
        assertEquals(CardDensity.COMPACT, prefs.cardDensity.first())
        prefs.setCardDensity(CardDensity.STANDARD)
    }

    // ── Sizes ───────────────────────────────────────────────────────────

    private val card = HomeCard(
        SearchHit.Channel(null, null, "A provider with a long name"),
        line = "Line under the title", nextTitle = "Next show", nextStartMs = 0L,
    )

    private fun tvAdapter(density: CardDensity) = SmartHomeAdapter(
        onClick = {}, cardScale = SmartHomeAdapter.TV_CARD_SCALE, posterScale = SmartHomeAdapter.TV_POSTER_SCALE,
        hidePosterText = true, initialDensity = density,
    )

    /** A row laid out at 700dp wide: its height and its first card's size, in dp. */
    private fun measureRow(adapter: SmartHomeAdapter, style: HomeLayout.Style, id: String = "custom"): Triple<Float, Float, Float> {
        context.setTheme(R.style.Theme_IptvApp)
        adapter.submitList(listOf(HomeRow(kind = null, cards = List(8) { card }, id = id, title = "A row", style = style)))
        shadowOf(Looper.getMainLooper()).idle()
        val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(0)) as SmartHomeAdapter.RowHolder
        adapter.onBindViewHolder(holder, 0)
        shadowOf(Looper.getMainLooper()).idle()
        holder.itemView.measure(
            View.MeasureSpec.makeMeasureSpec((700 * d).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        holder.itemView.layout(0, 0, holder.itemView.measuredWidth, holder.itemView.measuredHeight)
        val c = holder.b.rvHomeRowCards.getChildAt(0)
        return Triple(holder.itemView.measuredHeight / d, c.width / d, c.height / d)
    }

    @Test
    fun everyTvRowFitsItsSlotAtEveryDensity() {
        val report = StringBuilder()
        val problems = ArrayList<String>()
        for (density in CardDensity.entries) {
            val slot = context.resources.getDimensionPixelSize(SmartHomeAdapter.tvRowSlotRes(density)) / d
            val rows = mapOf(
                "wide" to measureRow(tvAdapter(density), HomeLayout.Style.WIDE).first,
                "wide+next" to measureRow(tvAdapter(density), HomeLayout.Style.WIDE, HomeLayout.Builtin.TOP_FAVORITES.id).first,
                "poster" to measureRow(tvAdapter(density), HomeLayout.Style.POSTER).first,
                "compact" to measureRow(tvAdapter(density), HomeLayout.Style.COMPACT).first,
            )
            report.append("$density slot $slot dp: $rows\n")
            rows.forEach { (name, h) -> assertTrue("$density $name row is $h dp, the slot is $slot dp", h <= slot) }
        }
        println("TV rows by density:\n$report")
    }

    @Test
    fun compactCardsAreSmallerAndExpandedLarger() {
        val report = StringBuilder()
        for (style in HomeLayout.Style.entries) {
            for (phone in listOf(true, false)) {
                fun size(dens: CardDensity) = measureRow(if (phone) SmartHomeAdapter(onClick = {}, initialDensity = dens) else tvAdapter(dens), style)
                val c = size(CardDensity.COMPACT)
                val s = size(CardDensity.STANDARD)
                val e = size(CardDensity.EXPANDED)
                report.append("${if (phone) "phone" else "tv"} $style card w×h: compact ${c.second}×${c.third}, standard ${s.second}×${s.third}, expanded ${e.second}×${e.third}\n")
                assertTrue("$style compact ${c.second} < standard ${s.second}", c.second < s.second)
                assertTrue("$style expanded ${e.second} > standard ${s.second}", e.second > s.second)
                assertTrue("$style compact ${c.third} <= standard ${s.third}", c.third <= s.third)
                assertTrue("$style expanded ${e.third} > standard ${s.third}", e.third > s.third)
                // A compact card is still a comfortable tap.
                if (phone) assertTrue("$style compact card is ${c.third} dp tall", c.third >= 44f)
            }
        }
        println("Card sizes by density:\n$report")
    }

    @Test
    fun changingTheDensityRebuildsTheCards() {
        val adapter = tvAdapter(CardDensity.STANDARD)
        val before = measureRow(adapter, HomeLayout.Style.WIDE).second
        adapter.density = CardDensity.EXPANDED
        val after = measureRow(adapter, HomeLayout.Style.WIDE).second
        assertTrue("$before -> $after", after > before)
        // Cards of each size have their own view type, so none is reused at the wrong size.
        val types = CardDensity.entries.flatMap { dens -> HomeLayout.Style.entries.map { SmartHomeAdapter.cardViewType(it, dens) } }
        assertEquals(types.size, types.toSet().size)
    }

    @Test
    fun expandedStillLeavesThePlayerRoom() {
        context.setTheme(R.style.Theme_IptvApp)
        val b = com.iptvapp.databinding.ActivityTvHomeBinding.inflate(android.view.LayoutInflater.from(context))
        b.tvRvHome.visibility = View.VISIBLE
        b.tvRvHome.layoutParams = b.tvRvHome.layoutParams.apply { height = context.resources.getDimensionPixelSize(SmartHomeAdapter.tvRowSlotRes(CardDensity.EXPANDED)) }
        b.root.measure(
            View.MeasureSpec.makeMeasureSpec((960 * d).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((540 * d).toInt(), View.MeasureSpec.EXACTLY),
        )
        b.root.layout(0, 0, (960 * d).toInt(), (540 * d).toInt())
        val video = b.tvMiniPlayerContainer.height / d
        println("Expanded: row slot ${b.tvRvHome.height / d} dp, video $video dp")
        assertTrue("video $video dp", video >= 240f)
    }

    @Test
    fun gridPostersTakeTheHeightTheyAreGiven() {
        context.setTheme(R.style.Theme_IptvApp)
        val adapter = TvVodPosterAdapter(onVodClick = {})
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        val vod = com.iptvapp.data.local.entities.VodEntity(streamId = 1, name = "A film", streamIcon = null, categoryId = null, rating = null, containerExtension = "mp4", added = null)
        holder.bind(vod)
        assertEquals((280 * d).toInt(), holder.binding.frameVodPoster.layoutParams.height)
        adapter.posterHeightPx = (342 * d).toInt()
        holder.bind(vod)
        assertEquals((342 * d).toInt(), holder.binding.frameVodPoster.layoutParams.height)
    }
}
