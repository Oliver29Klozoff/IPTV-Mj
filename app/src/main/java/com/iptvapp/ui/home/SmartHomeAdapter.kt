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
    /** Picture size for the cards: 1 on the phone; the TV home uses [TV_CARD_SCALE] so its row fits under a bigger player. */
    private val cardScale: Float = 1f,
    /** Poster size on its own: the TV home shrinks posters more ([TV_POSTER_SCALE]) so a poster row is no taller than a channel row. */
    private val posterScale: Float = cardScale,
    /**
     * The TV home: posters show no text under them (so they fit its one-row slot at a bigger size).
     * The focused poster's title and line show next to its row's name instead.
     */
    private val hidePosterText: Boolean = false,
) : ListAdapter<HomeRow, RecyclerView.ViewHolder>(RowDiff) {

    // Each row keeps its own scroll position while the list refreshes.
    private val pool = RecyclerView.RecycledViewPool()

    inner class RowHolder(val b: ItemHomeRowBinding) : RecyclerView.ViewHolder(b.root) {
        val cards = CardAdapter(
            onClick, cardScale, posterScale,
            onLongClick = { card -> currentRow()?.let { onLongClick(it, card) } },
            hidePosterText = hidePosterText,
            onTextlessFocus = { text -> showFocused(text) },
        ).also {
            b.rvHomeRowCards.layoutManager = LinearLayoutManager(b.root.context, LinearLayoutManager.HORIZONTAL, false)
            b.rvHomeRowCards.adapter = it
            b.rvHomeRowCards.setRecycledViewPool(pool)
        }

        private fun currentRow() = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { getItem(it) }

        /** The focused poster's title and line beside the row name; null clears it. */
        fun showFocused(text: String?) {
            b.tvHomeRowFocus.text = text?.let { "  ·  $it" }
            b.tvHomeRowFocus.visibility = if (text == null) View.GONE else View.VISIBLE
        }

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

    /**
     * Each row's height in pixels, or 0 for its own height. The TV home sets it to its single-row
     * slot, so one row fills the space under the player and the next one is never half in view.
     */
    var rowHeightPx = 0
        set(value) {
            if (field == value) return
            field = value
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        val wanted = if (rowHeightPx > 0) rowHeightPx else ViewGroup.LayoutParams.WRAP_CONTENT
        holder.itemView.layoutParams?.let { lp -> if (lp.height != wanted) { lp.height = wanted; holder.itemView.layoutParams = lp } }
        if (holder !is RowHolder) return
        // A recycled row keeps no other row's poster title; a row refreshed under focus keeps its own.
        if (!holder.itemView.hasFocus()) holder.showFocused(null)
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

    class CardAdapter(
        private val onClick: (HomeCard) -> Unit,
        private val scale: Float = 1f,
        private val posterScale: Float = scale,
        private val onLongClick: (HomeCard) -> Unit = {},
        /** Posters with no title or line under them; [onTextlessFocus] gets that text while one has focus, null when it loses it. */
        private val hidePosterText: Boolean = false,
        private val onTextlessFocus: (String?) -> Unit = {},
    ) :
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
            val scale = if (cardStyle == HomeLayout.Style.POSTER) posterScale else scale
            b.homeCardImageFrame.layoutParams = b.homeCardImageFrame.layoutParams.apply {
                width = (imageWidth * scale * d).toInt()
                height = (imageHeight * scale * d).toInt()
            }
            // A horizontal row measures this card with no width bound. The title is match_parent
            // inside a wrap_content root, so pin the card to the picture plus its own padding.
            b.root.layoutParams.width = (imageWidth * scale * d).toInt() + b.root.paddingLeft + b.root.paddingRight
            // Channel logos are shown whole on a dark card; posters fill theirs.
            b.ivHomeCard.scaleType = if (logoLike) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            if (logoLike) {
                val pad = if (cardStyle == HomeLayout.Style.COMPACT) 6 else 12
                b.ivHomeCard.setPadding((pad * d).toInt(), (pad * d * 2 / 3).toInt(), (pad * d).toInt(), (pad * d * 2 / 3).toInt())
            }
            // Small tiles: the name only.
            if (cardStyle == HomeLayout.Style.COMPACT) b.tvHomeCardLine.visibility = View.GONE
            val textless = hidePosterText && cardStyle == HomeLayout.Style.POSTER
            if (textless) {
                b.tvHomeCardTitle.visibility = View.GONE
                b.tvHomeCardLine.visibility = View.GONE
            }
            return CardHolder(b)
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val card = getItem(position)
            val hit = card.hit
            val context = holder.b.root.context
            val (title, line) = titleAndLine(context, card)
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
            // Set here, not when the card is made: the rows share their cards (one pool), so a
            // card can move to another row and must tell that row's header.
            val textless = hidePosterText && style == HomeLayout.Style.POSTER
            holder.b.root.setOnFocusChangeListener { v, focused ->
                // A little lift under the D-pad, like the movie posters.
                v.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start()
                if (textless) onTextlessFocus(if (focused) headerText(v.context, card) else null)
            }
            // Refreshed under focus: the row's header follows.
            if (textless && holder.b.root.isFocused) onTextlessFocus(headerText(context, card))
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

    companion object {
        /** The TV home's card pictures, a little smaller than the phone's (TvHomeRowSlotTest). */
        const val TV_CARD_SCALE = 0.8f
        /** The TV home's posters (no text under them there): as big as still fits the channel-row slot (TvHomeRowSlotTest). */
        const val TV_POSTER_SCALE = 0.95f
        private const val TYPE_ROW = 0
        private const val TYPE_CUSTOMIZE = 1

        /** A card's title (the series for an episode) and the line under it. */
        private fun titleAndLine(context: android.content.Context, card: HomeCard): Pair<String, String> {
            val hit = card.hit
            val title = if (hit is SearchHit.Episode) hit.series.title else hit.title
            val line = card.line?.takeIf { it.isNotBlank() } ?: when {
                hit is SearchHit.Program && hit.kind == SearchHit.Kind.ON_NOW -> hit.channelName
                hit is SearchHit.Channel -> hit.provider.orEmpty()
                else -> SearchHitUi.detailOf(context, hit)
            }
            return title to line
        }

        /** What a textless poster shows beside its row's name: "Title  ·  line". */
        private fun headerText(context: android.content.Context, card: HomeCard): String {
            val (title, line) = titleAndLine(context, card)
            return listOf(title, line).filter { it.isNotBlank() }.joinToString("  ·  ")
        }
    }
}
