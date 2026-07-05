package com.iptvapp.ui.settings

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.R

class TvSettingsAdapter(private val items: List<TvSettingItem>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TOGGLE = 1
        private const val TYPE_ACTION = 2
        private const val TYPE_INFO   = 3
    }

    override fun getItemViewType(pos: Int) = when (items[pos]) {
        is TvSettingItem.Header -> TYPE_HEADER
        is TvSettingItem.Toggle -> TYPE_TOGGLE
        is TvSettingItem.Action -> TYPE_ACTION
        is TvSettingItem.Info   -> TYPE_INFO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inf.inflate(R.layout.item_tv_setting_header, parent, false))
            TYPE_TOGGLE -> ToggleVH(inf.inflate(R.layout.item_tv_setting_toggle, parent, false))
            TYPE_ACTION -> ActionVH(inf.inflate(R.layout.item_tv_setting_action, parent, false))
            else        -> InfoVH(inf.inflate(R.layout.item_tv_setting_info, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        when (val item = items[pos]) {
            is TvSettingItem.Header -> (holder as HeaderVH).bind(item)
            is TvSettingItem.Toggle -> (holder as ToggleVH).bind(item)
            is TvSettingItem.Action -> (holder as ActionVH).bind(item)
            is TvSettingItem.Info   -> (holder as InfoVH).bind(item)
        }
    }

    override fun getItemCount() = items.size

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvSettingHeader)
        fun bind(item: TvSettingItem.Header) { tvTitle.text = item.title }
    }

    class ToggleVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle:    TextView = view.findViewById(R.id.tvToggleTitle)
        private val tvSubtitle: TextView = view.findViewById(R.id.tvToggleSubtitle)
        private val tvValue:    TextView = view.findViewById(R.id.tvToggleValue)

        fun bind(item: TvSettingItem.Toggle) {
            tvTitle.text = item.title
            if (item.subtitle.isBlank()) tvSubtitle.visibility = View.GONE
            else { tvSubtitle.visibility = View.VISIBLE; tvSubtitle.text = item.subtitle }
            applyState(item)
            itemView.setOnClickListener {
                item.checked = !item.checked
                applyState(item)
                item.onToggle(item.checked)
            }
        }

        private fun applyState(item: TvSettingItem.Toggle) {
            val on = item.checked
            tvValue.text    = if (on) item.valueOn else item.valueOff
            tvValue.setTextColor(if (on) Color.parseColor("#008CFF") else Color.parseColor("#555555"))
        }
    }

    class ActionVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvActionTitle)
        private val tvValue: TextView = view.findViewById(R.id.tvActionValue)

        fun bind(item: TvSettingItem.Action) {
            tvTitle.text = item.title
            tvTitle.setTextColor(if (item.danger) Color.parseColor("#FF6B6B") else Color.WHITE)
            tvValue.text = item.value
            tvValue.visibility = if (item.value.isBlank()) View.GONE else View.VISIBLE
            itemView.alpha      = if (item.enabled) 1f else 0.45f
            itemView.isFocusable = item.enabled
            itemView.setOnClickListener { if (item.enabled) item.onClick() }
        }
    }

    class InfoVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvText: TextView = view.findViewById(R.id.tvInfoText)

        fun bind(item: TvSettingItem.Info) {
            if (item.text.isBlank()) { itemView.visibility = View.GONE; return }
            itemView.visibility = View.VISIBLE
            tvText.text = item.text
        }
    }
}
