package com.streamforge.app.ui

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamforge.app.databinding.ItemOverlayRowBinding
import com.streamforge.app.overlay.OverlayItem
import kotlin.math.roundToInt

/**
 * Phase 4B + 5B: RecyclerView adapter for the overlay list.
 * Shows row controls (visibility / delete) and routes taps to onEdit for text overlays.
 *
 * Each row also carries a size slider (+ step buttons) so overlays can be resized without
 * the on-preview pinch gesture. [onScaleChange] fires continuously for live preview;
 * [onScaleSettled] fires once when the user lets go, for persistence.
 */
class OverlayListAdapter(
    private val onVisibilityToggle: (OverlayItem) -> Unit,
    private val onDelete: (OverlayItem) -> Unit,
    private val onEdit: (OverlayItem) -> Unit = {},
    private val onScaleChange: (OverlayItem, Float) -> Unit = { _, _ -> },
    private val onScaleSettled: (OverlayItem, Float) -> Unit = { _, _ -> }
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
                    loadImageThumbnail(item.uri)
                }
                is OverlayItem.Text -> {
                    binding.tvOverlayName.text = item.text
                    binding.ivOverlayIcon.setImageResource(android.R.drawable.ic_menu_edit)
                }
                is OverlayItem.Gif -> {
                    binding.tvOverlayName.text = "GIF"
                    loadImageThumbnail(item.uri)
                }
                is OverlayItem.Video -> {
                    binding.tvOverlayName.text = "Video"
                    loadVideoThumbnail(item.uri)
                }
                is OverlayItem.Browser -> {
                    binding.tvOverlayName.text = item.url
                    binding.ivOverlayIcon.setImageResource(android.R.drawable.ic_menu_compass)
                }
            }
            bindDetails(item, item.scale)

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

            bindSizeControls(item)
        }

        private fun bindSizeControls(item: OverlayItem) {
            val seek = binding.seekSize
            // Detach any recycled listener before re-seeding progress so the programmatic
            // set can't be mistaken for user input on this rebound row.
            seek.setOnSeekBarChangeListener(null)
            seek.progress = scaleToProgress(item.scale)

            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val scale = progressToScale(progress)
                    bindDetails(item, scale)
                    onScaleChange(item, scale)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}

                override fun onStopTrackingTouch(sb: SeekBar) {
                    onScaleSettled(item, progressToScale(sb.progress))
                }
            })

            binding.btnSizeDown.setOnClickListener { stepSize(item, -SIZE_STEP) }
            binding.btnSizeUp.setOnClickListener { stepSize(item, SIZE_STEP) }
        }

        private fun stepSize(item: OverlayItem, delta: Int) {
            val progress = (binding.seekSize.progress + delta).coerceIn(0, 100)
            binding.seekSize.progress = progress
            val scale = progressToScale(progress)
            bindDetails(item, scale)
            onScaleChange(item, scale)
            onScaleSettled(item, scale)
        }

        private fun bindDetails(item: OverlayItem, scale: Float) {
            binding.tvOverlayDetails.text = when (item) {
                is OverlayItem.Text ->
                    "Text · ${item.fontSizeSp.toInt()}sp · " + transformDetails(scale, item.rotation)
                is OverlayItem.Video ->
                    transformDetails(scale, item.rotation) + if (item.loop) " · loop" else ""
                else -> transformDetails(scale, item.rotation)
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

    companion object {
        // Scale bounds mirror OverlayEditorView's pinch coerceIn so the slider and the
        // gesture can't disagree on the valid range.
        private const val MIN_SCALE = 0.2f
        private const val MAX_SCALE = 5.0f
        // One +/- tap moves the 0..100 slider by this much (~0.24x of scale).
        private const val SIZE_STEP = 5

        fun scaleToProgress(scale: Float): Int =
            ((scale - MIN_SCALE) / (MAX_SCALE - MIN_SCALE) * 100f).roundToInt().coerceIn(0, 100)

        fun progressToScale(progress: Int): Float =
            MIN_SCALE + (progress / 100f) * (MAX_SCALE - MIN_SCALE)
    }
}
