package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.home.HomeCard
import com.iptvapp.data.home.HomeRow
import com.iptvapp.data.search.SearchHit
import com.iptvapp.databinding.ItemHomeCardBinding
import com.iptvapp.databinding.ItemHomeRowBinding
import com.iptvapp.ui.search.SearchHitUi

/** The Smart Home rows: a title and a sideways list of cards each. Used by both home screens. */
class SmartHomeAdapter(private val onClick: (HomeCard) -> Unit) :
    ListAdapter<HomeRow, SmartHomeAdapter.RowHolder>(RowDiff) {

    // Each row keeps its own scroll position while the list refreshes.
    private val pool = RecyclerView.RecycledViewPool()

    inner class RowHolder(val b: ItemHomeRowBinding) : RecyclerView.ViewHolder(b.root) {
        val cards = CardAdapter(onClick).also {
            b.rvHomeRowCards.layoutManager = LinearLayoutManager(b.root.context, LinearLayoutManager.HORIZONTAL, false)
            b.rvHomeRowCards.adapter = it
            b.rvHomeRowCards.setRecycledViewPool(pool)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        RowHolder(ItemHomeRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val row = getItem(position)
        holder.b.tvHomeRowTitle.text = row.kind.title
        holder.cards.wide = row.kind.wide
        holder.cards.submitList(row.cards)
    }

    class CardAdapter(private val onClick: (HomeCard) -> Unit) :
        ListAdapter<HomeCard, CardAdapter.CardHolder>(CardDiff) {

        var wide = true

        inner class CardHolder(val b: ItemHomeCardBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemViewType(position: Int) = if (wide) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val b = ItemHomeCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val d = parent.resources.displayMetrics.density
            val wideCard = viewType == 1
            val imageWidth = if (wideCard) 144 else 92
            val imageHeight = if (wideCard) 81 else 138
            b.homeCardImageFrame.layoutParams = b.homeCardImageFrame.layoutParams.apply {
                width = (imageWidth * d).toInt()
                height = (imageHeight * d).toInt()
            }
            // A horizontal row measures this card with no width bound. The title is match_parent
            // inside a wrap_content root, so pin the card to the picture plus its own padding.
            b.root.layoutParams.width = (imageWidth * d).toInt() + b.root.paddingLeft + b.root.paddingRight
            // Channel logos are shown whole on a dark card; posters fill theirs.
            b.ivHomeCard.scaleType = if (wideCard) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            if (wideCard) b.ivHomeCard.setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            // A little lift under the D-pad, like the movie posters.
            b.root.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start()
            }
            return CardHolder(b)
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val card = getItem(position)
            val hit = card.hit
            val context = holder.b.root.context
            val title = if (hit is SearchHit.Episode) hit.series.title else hit.title
            val line = card.line?.takeIf { it.isNotBlank() } ?: when {
                hit is SearchHit.Program && hit.kind == SearchHit.Kind.ON_NOW -> hit.channelName
                hit is SearchHit.Channel -> hit.provider.orEmpty()
                else -> SearchHitUi.detailOf(context, hit)
            }
            holder.b.tvHomeCardTitle.text = title
            holder.b.tvHomeCardLine.text = line
            holder.b.root.contentDescription = "$title, $line"
            val badge = when {
                hit is SearchHit.Program && hit.kind == SearchHit.Kind.ON_NOW -> "Live"
                hit is SearchHit.Recording && hit.recording.status == "RECORDING" -> "Recording"
                else -> null
            }
            holder.b.tvHomeCardBadge.text = badge
            holder.b.tvHomeCardBadge.visibility = if (badge == null) View.GONE else View.VISIBLE
            val progress = card.progress
            holder.b.pbHomeCard.visibility = if (progress == null) View.GONE else View.VISIBLE
            if (progress != null) holder.b.pbHomeCard.progress = progress
            Glide.with(holder.b.ivHomeCard).load(SearchHitUi.imageOf(hit))
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(holder.b.ivHomeCard)
            holder.b.root.setOnClickListener { onClick(card) }
        }
    }

    private object RowDiff : DiffUtil.ItemCallback<HomeRow>() {
        override fun areItemsTheSame(a: HomeRow, b: HomeRow) = a.kind == b.kind
        override fun areContentsTheSame(a: HomeRow, b: HomeRow) = a == b
    }

    private object CardDiff : DiffUtil.ItemCallback<HomeCard>() {
        override fun areItemsTheSame(a: HomeCard, b: HomeCard) = a.hit == b.hit
        override fun areContentsTheSame(a: HomeCard, b: HomeCard) = a == b
    }
}
