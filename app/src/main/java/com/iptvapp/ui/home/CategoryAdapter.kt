package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.data.local.entities.CategoryEntity
import com.iptvapp.databinding.ItemCategoryBinding

class CategoryAdapter(
    private val onCategoryClick: (CategoryEntity) -> Unit,
    private val onCategoryLongClick: (CategoryEntity) -> Unit = {}
) : ListAdapter<CategoryEntity, CategoryAdapter.ViewHolder>(DiffCallback()) {

    private var selectedPosition = 0
    private var favoriteCategoryIds: Set<String> = emptySet()
    // Providers > Movies/Series hidden categories — a separate concept from favoriting (see
    // HomeViewModel kdoc). Dimmed rather than removed here since this adapter only ever
    // receives hidden rows at all when the "show hidden" toggle is on (HomeActivity filters
    // them out of the list entirely otherwise) — reuses the exact same Set<String>-pushed-
    // from-Activity mechanism submitFavoriteCategoryIds already established.
    private var hiddenCategoryIds: Set<String> = emptySet()
    // Bulk-hide checkbox mode (Providers > Movies/Series category lists) — same shape as
    // ChannelAdapter/LiveChannelAdapter's bulk-select: a real checkbox on every row while
    // active, plain taps toggle instead of drilling into the category.
    private var bulkSelectedCategoryIds: Set<String> = emptySet()
    private var bulkSelectMode: Boolean = false

    fun resetSelection() {
        selectedPosition = 0
        notifyDataSetChanged()
    }

    fun submitFavoriteCategoryIds(ids: Set<String>) {
        favoriteCategoryIds = ids
        notifyDataSetChanged()
    }

    fun submitHiddenCategoryIds(ids: Set<String>) {
        hiddenCategoryIds = ids
        notifyDataSetChanged()
    }

    fun submitBulkSelection(ids: Set<String>) {
        bulkSelectedCategoryIds = ids
        bulkSelectMode = ids.isNotEmpty()
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemCategoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CategoryEntity, isSelected: Boolean) {
            binding.tvCategoryName.text = item.categoryName
            binding.ivCategoryStar.visibility =
                if (item.categoryId in favoriteCategoryIds) android.view.View.VISIBLE
                else android.view.View.GONE
            binding.root.alpha = if (item.categoryId in hiddenCategoryIds) 0.4f else 1f

            binding.root.isSelected = isSelected

            binding.tvCategoryName.setTextColor(
                if (isSelected) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
            )

            if (bulkSelectMode) {
                binding.cbCategoryBulkSelect?.visibility = android.view.View.VISIBLE
                binding.cbCategoryBulkSelect?.isChecked = item.categoryId in bulkSelectedCategoryIds
            } else {
                binding.cbCategoryBulkSelect?.visibility = android.view.View.GONE
            }

            binding.root.setOnClickListener {
                if (bulkSelectMode) {
                    onCategoryClick(item)
                    return@setOnClickListener
                }
                val prev = selectedPosition
                selectedPosition = bindingAdapterPosition
                notifyItemChanged(prev)
                notifyItemChanged(selectedPosition)
                onCategoryClick(item)
            }

            binding.root.setOnLongClickListener {
                onCategoryLongClick(item)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCategoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position == selectedPosition)
    }

    class DiffCallback : DiffUtil.ItemCallback<CategoryEntity>() {
        override fun areItemsTheSame(a: CategoryEntity, b: CategoryEntity) =
            a.categoryId == b.categoryId

        override fun areContentsTheSame(a: CategoryEntity, b: CategoryEntity) =
            a == b
    }
}
