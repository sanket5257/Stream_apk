package com.streamforge.app.ui

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamforge.app.databinding.ItemOverlayRowBinding
import com.streamforge.app.overlay.OverlayItem

/**
 * Phase 4B + 5B: RecyclerView adapter for the overlay list.
 * Shows row controls (visibility / delete) and routes taps to onEdit for text overlays.
 */
class OverlayListAdapter(
    private val onVisibilityToggle: (OverlayItem) -> Unit,
    private val onDelete: (OverlayItem) -> Unit,
    private val onEdit: (OverlayItem) -> Unit = {}
) : ListAdapter<OverlayItem, OverlayListAdapter.OverlayViewHolder>(OverlayDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OverlayViewHolder {
        val binding = ItemOverlayRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
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
                    binding.tvOverlayName.text = "Image"
                    binding.tvOverlayDetails.text = transformDetails(item.scale, item.rotation)
                    loadImageThumbnail(item.uri)
                }
                is OverlayItem.Text -> {
                    binding.tvOverlayName.text = item.text
                    binding.tvOverlayDetails.text = "Text · ${item.fontSizeSp.toInt()}sp · " +
                            transformDetails(item.scale, item.rotation)
                    binding.ivOverlayIcon.setImageResource(android.R.drawable.ic_menu_edit)
                }
                is OverlayItem.Gif -> {
                    binding.tvOverlayName.text = "GIF"
                    binding.tvOverlayDetails.text = transformDetails(item.scale, item.rotation)
                    loadImageThumbnail(item.uri)
                }
                is OverlayItem.Video -> {
                    binding.tvOverlayName.text = "Video"
                    binding.tvOverlayDetails.text = transformDetails(item.scale, item.rotation) +
                            if (item.loop) " · loop" else ""
                    loadVideoThumbnail(item.uri)
                }
            }

            val visibilityIcon = if (item.visible) {
                android.R.drawable.ic_menu_view
            } else {
                android.R.drawable.ic_menu_close_clear_cancel
            }
            binding.btnToggleVisibility.setIconResource(visibilityIcon)

            binding.btnToggleVisibility.setOnClickListener { onVisibilityToggle(item) }
            binding.btnDelete.setOnClickListener { onDelete(item) }
            binding.root.setOnClickListener {
                if (item is OverlayItem.Text) onEdit(item)
            }
        }

        private fun transformDetails(scale: Float, rotation: Float): String =
            "Scale %.1fx · %d°".format(scale, rotation.toInt())

        private fun loadImageThumbnail(uriString: String) {
            try {
                val uri = Uri.parse(uriString)
                binding.root.context.contentResolver.openInputStream(uri).use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        binding.ivOverlayIcon.setImageBitmap(bitmap)
                    } else {
                        binding.ivOverlayIcon.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                }
            } catch (_: Exception) {
                binding.ivOverlayIcon.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

        private fun loadVideoThumbnail(uriString: String) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(binding.root.context, Uri.parse(uriString))
                val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    binding.ivOverlayIcon.setImageBitmap(frame)
                } else {
                    binding.ivOverlayIcon.setImageResource(android.R.drawable.ic_media_play)
                }
            } catch (_: Exception) {
                binding.ivOverlayIcon.setImageResource(android.R.drawable.ic_media_play)
            } finally {
                try { retriever.release() } catch (_: Exception) { }
            }
        }
    }

    private class OverlayDiffCallback : DiffUtil.ItemCallback<OverlayItem>() {
        override fun areItemsTheSame(oldItem: OverlayItem, newItem: OverlayItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: OverlayItem, newItem: OverlayItem): Boolean =
            oldItem == newItem
    }
}
