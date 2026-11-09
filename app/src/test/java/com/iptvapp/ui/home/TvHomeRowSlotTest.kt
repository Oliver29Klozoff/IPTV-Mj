package com.iptvapp.ui.home

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.R
import com.iptvapp.data.home.HomeCard
import com.iptvapp.data.home.HomeLayout
import com.iptvapp.data.home.HomeRow
import com.iptvapp.data.search.SearchHit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The TV home's single-row slot under the mini player (activity_tv_home's tvRvHome) must hold
 * the tallest row whole: a row taller than the slot would show cut off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvHomeRowSlotTest {

    private fun rowHeightDp(style: HomeLayout.Style, id: String, customize: Boolean = false): Float {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(R.style.Theme_IptvApp)
        val card = HomeCard(
            SearchHit.Channel(null, null, "A provider with a long name"),
            line = "Line under the title", nextTitle = "Next show", nextStartMs = 0L,
        )
        val row = if (customize) HomeRow(kind = null, cards = emptyList(), customize = true)
        else HomeRow(kind = null, cards = List(6) { card }, id = id, title = "A row", style = style)
        val adapter = SmartHomeAdapter(onClick = {}, cardScale = SmartHomeAdapter.TV_CARD_SCALE, posterScale = SmartHomeAdapter.TV_POSTER_SCALE)
        adapter.submitList(listOf(row))
        shadowOf(Looper.getMainLooper()).idle()
        val parent = FrameLayout(context)
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        shadowOf(Looper.getMainLooper()).idle()
        val d = context.resources.displayMetrics.density
        holder.itemView.measure(
            View.MeasureSpec.makeMeasureSpec((700 * d).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return holder.itemView.measuredHeight / d
    }

    @Test
    fun everyRowFitsTheSlot() {
        val heights = mapOf(
            "wide" to rowHeightDp(HomeLayout.Style.WIDE, "custom"),
            "wide+next" to rowHeightDp(HomeLayout.Style.WIDE, HomeLayout.Builtin.TOP_FAVORITES.id),
            "poster" to rowHeightDp(HomeLayout.Style.POSTER, "custom"),
            "compact" to rowHeightDp(HomeLayout.Style.COMPACT, "custom"),
            "customize" to rowHeightDp(HomeLayout.Style.WIDE, "", customize = true),
        )
        println("TV home row heights (dp): $heights")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val slot = context.resources.getDimension(R.dimen.tv_home_row_slot) / context.resources.displayMetrics.density
        heights.forEach { (name, h) -> assertTrue("$name row is $h dp, the slot is $slot dp", h <= slot) }
    }

    @Test
    fun rowsFillTheSlotOnTvAndKeepTheirOwnHeightElsewhere() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(R.style.Theme_IptvApp)
        val adapter = SmartHomeAdapter(onClick = {})
        adapter.submitList(listOf(HomeRow(kind = null, cards = emptyList(), customize = true)))
        shadowOf(Looper.getMainLooper()).idle()
        val parent = FrameLayout(context)
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        org.junit.Assert.assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, holder.itemView.layoutParams.height)
        adapter.rowHeightPx = 500
        adapter.onBindViewHolder(holder, 0)
        org.junit.Assert.assertEquals(500, holder.itemView.layoutParams.height)
    }

    @Test
    fun thePlayerGetsTheRestOfTheColumnWithOneRowUnderIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(R.style.Theme_IptvApp)
        val b = com.iptvapp.databinding.ActivityTvHomeBinding.inflate(android.view.LayoutInflater.from(context))
        b.tvRvHome.visibility = View.VISIBLE
        b.tvTvChannelName.text = "ESPN HD"
        b.tvTvEpg.text = "NOW 7:00 - 9:00  Monday Night Countdown. Previews, analysis and highlights ahead of tonight's game, with reports from the stadium.\nNEXT 9:00  NFL Football"
        val d = context.resources.displayMetrics.density
        b.root.measure(
            View.MeasureSpec.makeMeasureSpec((960 * d).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((540 * d).toInt(), View.MeasureSpec.EXACTLY),
        )
        b.root.layout(0, 0, (960 * d).toInt(), (540 * d).toInt())
        val slot = b.tvRvHome.height / d
        val video = b.tvMiniPlayerContainer.height / d
        val footer = b.tvMiniPlayerFooter.height / d
        println("TV home column: video $video dp, footer $footer dp, row slot $slot dp")
        org.junit.Assert.assertEquals(174f, slot, 1f)
        assertTrue("video $video dp", video >= 285f)
    }
}
