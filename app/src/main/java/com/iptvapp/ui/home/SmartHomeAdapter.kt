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
import com.iptvapp.data.home.HomeLayout
import com.iptvapp.data.home.HomeRow
import com.iptvapp.data.search.SearchHit
import com.iptvapp.databinding.ItemHomeCardBinding
import com.iptvapp.databinding.ItemHomeCustomizeBinding
import com.iptvapp.databinding.ItemHomeRowBinding
import com.iptvapp.ui.search.SearchHitUi

/**
 * The Smart Home rows: a title and a sideways list of cards each, in the order the Home layout
 * gives them (HomeLayout), ending with Customize Home. Used by both home screens. Holding a card
 * (OK on the remote, a long-press on a touch screen) calls [onLongClick] with its row.
 */
class SmartHomeAdapter(
    private val onClick: (HomeCard) -> Unit,
    private val onCustomize: () -> Unit = {},
    private val onLongClick: (HomeRow, HomeCard) -> Unit = { _, _ -> },
) : ListAdapter<HomeRow, RecyclerView.ViewHolder>(RowDiff) {

    // Each row keeps its own scroll position while the list refreshes.
    private val pool = RecyclerView.RecycledViewPool()

    inner class RowHolder(val b: ItemHomeRowBinding) : RecyclerView.ViewHolder(b.root) {
        val cards = CardAdapter(onClick) { card -> currentRow()?.let { onLongClick(it, card) } }.also {
            b.rvHomeRowCards.layoutManager = LinearLayoutManager(b.root.context, LinearLayoutManager.HORIZONTAL, false)
            b.rvHomeRowCards.adapter = it
            b.rvHomeRowCards.setRecycledViewPool(pool)
        }

        private fun currentRow() = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { getItem(it) }

        init {
            // A touch screen: holding a row's title opens Customize Home, as the last row does.
            b.tvHomeRowTitle.setOnLongClickListener { onCustomize(); true }
        }
    }

    inner class CustomizeHolder(b: ItemHomeCustomizeBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.btnHomeCustomize.setOnClickListener { onCustomize() }
            b.btnHomeCustomize.contentDescription = "Customize Home: arrange, hide, and add rows"
        }
    }

    override fun getItemViewType(position: Int) = if (getItem(position).customize) TYPE_CUSTOMIZE else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_CUSTOMIZE) CustomizeHolder(ItemHomeCustomizeBinding.inflate(inflater, parent, false))
        else RowHolder(ItemHomeRowBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        if (holder !is RowHolder) return
        holder.b.tvHomeRowTitle.text = row.title
        holder.b.tvHomeRowTitle.contentDescription = "${row.title}, row"
        holder.cards.style = row.style
        holder.cards.showsNext = row.id == HomeLayout.Builtin.TOP_FAVORITES.id
        holder.cards.submitList(row.cards)
        val empty = row.cards.isEmpty()
        holder.b.rvHomeRowCards.visibility = if (empty) View.GONE else View.VISIBLE
        holder.b.tvHomeRowEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        holder.b.tvHomeRowEmpty.text = row.emptyText.orEmpty()
        holder.b.tvHomeRowEmpty.contentDescription = "${row.title}: ${row.emptyText.orEmpty()}"
    }

    class CardAdapter(private val onClick: (HomeCard) -> Unit, private val onLongClick: (HomeCard) -> Unit = {}) :
        ListAdapter<HomeCard, CardAdapter.CardHolder>(CardDiff) {

        /** Cards keep a line for the channel's next show (blank when the guide has none), so the row lines up. */
        var showsNext = false
            set(value) {
                if (field == value) return
                field = value
                @Suppress("NotifyDataSetChanged")
                notifyDataSetChanged()
            }

        var style = HomeLayout.Style.WIDE
            set(value) {
                if (field == value) return
                field = value
                // Another size of card: the old ones can't be rebound in place.
                @Suppress("NotifyDataSetChanged")
                notifyDataSetChanged()
            }

        inner class CardHolder(val b: ItemHomeCardBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemViewType(position: Int) = style.ordinal

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val b = ItemHomeCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val d = parent.resources.displayMetrics.density
            val cardStyle = HomeLayout.Style.entries[viewType]
            val (imageWidth, imageHeight) = when (cardStyle) {
                HomeLayout.Style.WIDE -> 144 to 81
                HomeLayout.Style.POSTER -> 92 to 138
                HomeLayout.Style.COMPACT -> 104 to 58
            }
            val logoLike = cardStyle != HomeLayout.Style.POSTER
            b.homeCardImageFrame.layoutParams = b.homeCardImageFrame.layoutParams.apply {
                width = (imageWidth * d).toInt()
                height = (imageHeight * d).toInt()
            }
            // A horizontal row measures this card with no width bound. The title is match_parent
            // inside a wrap_content root, so pin the card to the picture plus its own padding.
            b.root.layoutParams.width = (imageWidth * d).toInt() + b.root.paddingLeft + b.root.paddingRight
            // Channel logos are shown whole on a dark card; posters fill theirs.
            b.ivHomeCard.scaleType = if (logoLike) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            if (logoLike) {
                val pad = if (cardStyle == HomeLayout.Style.COMPACT) 6 else 12
                b.ivHomeCard.setPadding((pad * d).toInt(), (pad * d * 2 / 3).toInt(), (pad * d).toInt(), (pad * d * 2 / 3).toInt())
            }
            // Small tiles: the name only.
            if (cardStyle == HomeLayout.Style.COMPACT) b.tvHomeCardLine.visibility = View.GONE
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
            val next = card.nextTitle?.takeIf { it.isNotBlank() }?.let { t ->
                card.nextStartMs?.let { "Next ${android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it))} · $t" } ?: "Next: $t"
            }
            holder.b.tvHomeCardNext.text = next.orEmpty()
            holder.b.tvHomeCardNext.visibility = when {
                next != null && holder.b.tvHomeCardLine.visibility == View.VISIBLE -> View.VISIBLE
                showsNext && holder.b.tvHomeCardLine.visibility == View.VISIBLE -> View.INVISIBLE
                else -> View.GONE
            }
            val spoken = listOf(title, line, next.orEmpty()).filter { it.isNotBlank() }.joinToString(", ")
            holder.b.root.contentDescription = spoken
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
            holder.b.root.setOnLongClickListener { onLongClick(card); true }
        }
    }

    private object RowDiff : DiffUtil.ItemCallback<HomeRow>() {
        override fun areItemsTheSame(a: HomeRow, b: HomeRow) = a.id == b.id
        override fun areContentsTheSame(a: HomeRow, b: HomeRow) = a == b
    }

    private object CardDiff : DiffUtil.ItemCallback<HomeCard>() {
        override fun areItemsTheSame(a: HomeCard, b: HomeCard) = a.hit == b.hit
        override fun areContentsTheSame(a: HomeCard, b: HomeCard) = a == b
    }

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_CUSTOMIZE = 1
    }
}
