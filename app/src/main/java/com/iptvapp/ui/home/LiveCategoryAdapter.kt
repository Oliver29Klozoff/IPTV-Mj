package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.databinding.ItemCategoryBinding
import com.iptvapp.ui.guide.providerColorFor

// Combined Live-tab category list — primary provider's categories plus every configured
// secondary provider's categories, each its own row (never merged across providers even if
// same-named — see LiveCategoryRow kdoc), color-coded by provider via the same
// providerColorFor palette Guide already uses, so a given provider reads as the same color
// everywhere in the app.
class LiveCategoryAdapter(
    private val onCategoryClick: (LiveCategoryRow) -> Unit,
    private val onCategoryLongClick: (LiveCategoryRow) -> Unit = {}
) : ListAdapter<LiveCategoryRow, LiveCategoryAdapter.ViewHolder>(DiffCallback()) {

    private var selectedId: String? = null
    private var favoriteKeys: Set<String> = emptySet()

    fun setSelectedId(id: String?) {
        val old = selectedId
        selectedId = id
        currentList.forEachIndexed { index, row -> if (row.id == old || row.id == id) notifyItemChanged(index) }
    }

    fun submitFavoriteKeys(keys: Set<String>) {
        favoriteKeys = keys
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemCategoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: LiveCategoryRow) {
            binding.tvCategoryName.text = item.name
            binding.ivCategoryStar.visibility =
                if (item.favoriteKey in favoriteKeys) android.view.View.VISIBLE else android.view.View.GONE
            binding.root.isSelected = item.id == selectedId
            binding.root.setOnClickListener { onCategoryClick(item) }
            binding.root.setOnLongClickListener { onCategoryLongClick(item); true }
            val stripe = binding.root.findViewById<android.view.View>(com.iptvapp.R.id.viewCategoryProviderStripe)
            stripe?.setBackgroundColor(providerColorFor(item.serverIndex) ?: 0x00000000)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class DiffCallback : DiffUtil.ItemCallback<LiveCategoryRow>() {
        override fun areItemsTheSame(a: LiveCategoryRow, b: LiveCategoryRow) = a.id == b.id
        override fun areContentsTheSame(a: LiveCategoryRow, b: LiveCategoryRow) = a == b
    }
}
