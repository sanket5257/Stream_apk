package com.streamforge.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamforge.app.R
import com.streamforge.app.databinding.ItemOverlayRowBinding
import com.streamforge.app.overlay.OverlayItem
import java.io.InputStream

/**
 * Phase 4B: RecyclerView adapter for overlay list.
 * Shows active overlays with show/hide/delete controls.
 */
class OverlayListAdapter(
    private val onVisibilityToggle: (OverlayItem) -> Unit,
    private val onDelete: (OverlayItem) -> Unit
) : ListAdapter<OverlayItem, OverlayListAdapter.OverlayViewHolder>(OverlayDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OverlayViewHolder {
        val binding = ItemOverlayRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OverlayViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OverlayViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class OverlayViewHolder(
        private val binding: ItemOverlayRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: OverlayItem) {
            when (item) {
                is OverlayItem.Image -> {
                    binding.tvOverlayName.text = "Image Overlay"
                    binding.tvOverlayDetails.text = "Scale: ${String.format("%.1f", item.scale)}x, " +
                            "Rotation: ${item.rotation.toInt()}°"
                    
                    // Try to load thumbnail from URI
                    try {
                        val uri = Uri.parse(item.uri)
                        val inputStream: InputStream? = binding.root.context.contentResolver.openInputStream(uri)
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        binding.ivOverlayIcon.setImageBitmap(bitmap)
                        inputStream?.close()
                    } catch (e: Exception) {
                        // Fallback to placeholder
                        binding.ivOverlayIcon.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                }
                is OverlayItem.Text -> {
                    binding.tvOverlayName.text = item.text
                    binding.tvOverlayDetails.text = "Size: ${item.fontSizeSp.toInt()}sp, " +
                            "Scale: ${String.format("%.1f", item.scale)}x"
                    binding.ivOverlayIcon.setImageResource(android.R.drawable.ic_menu_edit)
                }
            }

            // Update visibility icon
            val visibilityIcon = if (item.visible) {
                android.R.drawable.ic_menu_view
            } else {
                android.R.drawable.ic_menu_close_clear_cancel
            }
            binding.btnToggleVisibility.setIconResource(visibilityIcon)

            // Set click listeners
            binding.btnToggleVisibility.setOnClickListener {
                onVisibilityToggle(item)
            }

            binding.btnDelete.setOnClickListener {
                onDelete(item)
            }
        }
    }

    private class OverlayDiffCallback : DiffUtil.ItemCallback<OverlayItem>() {
        override fun areItemsTheSame(oldItem: OverlayItem, newItem: OverlayItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: OverlayItem, newItem: OverlayItem): Boolean {
            return oldItem == newItem
        }
    }
}
