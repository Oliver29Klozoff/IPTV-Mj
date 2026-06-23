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

    fun resetSelection() {
        selectedPosition = 0
        notifyDataSetChanged()
    }

    fun submitFavoriteCategoryIds(ids: Set<String>) {
        favoriteCategoryIds = ids
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemCategoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CategoryEntity, isSelected: Boolean) {
            binding.tvCategoryName.text = item.categoryName
            binding.ivCategoryStar.visibility =
                if (item.categoryId in favoriteCategoryIds) android.view.View.VISIBLE
                else android.view.View.GONE

            binding.root.setBackgroundColor(
                if (isSelected) 0x3300E5FF.toInt() else android.graphics.Color.TRANSPARENT
            )

            binding.tvCategoryName.setTextColor(
                if (isSelected) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
            )

            binding.root.setOnClickListener {
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
