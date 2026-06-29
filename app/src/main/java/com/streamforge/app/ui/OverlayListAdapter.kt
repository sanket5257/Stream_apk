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
 * Each row carries independent Width and Height sliders (+ step buttons) so overlays can be
 * resized without the on-preview pinch gesture. [onScaleChange]/[onHeightScaleChange] fire
 * continuously for live preview; [onScaleSettled]/[onHeightScaleSettled] fire once when the
 * user lets go, for persistence.
 */
class OverlayListAdapter(
    private val onVisibilityToggle: (OverlayItem) -> Unit,
    private val onDelete: (OverlayItem) -> Unit,
    private val onEdit: (OverlayItem) -> Unit = {},
    private val onScaleChange: (OverlayItem, Float) -> Unit = { _, _ -> },
    private val onScaleSettled: (OverlayItem, Float) -> Unit = { _, _ -> },
    private val onHeightScaleChange: (OverlayItem, Float) -> Unit = { _, _ -> },
    private val onHeightScaleSettled: (OverlayItem, Float) -> Unit = { _, _ -> },
    // Reorder the overlay's stacking (z-order). The list is shown front-most first, so
    // "up" raises an overlay toward the front and "down" sends it toward the back.
    private val onMoveUp: (OverlayItem) -> Unit = {},
    private val onMoveDown: (OverlayItem) -> Unit = {}
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
            bindDetails(item, item.scale, item.heightScale)

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

            // Reorder controls — greyed out at the ends of the list where there's nowhere to go.
            val pos = adapterPosition
            val canMoveUp = pos != RecyclerView.NO_POSITION && pos > 0
            val canMoveDown = pos != RecyclerView.NO_POSITION && pos < itemCount - 1
            binding.btnMoveUp.isEnabled = canMoveUp
            binding.btnMoveDown.isEnabled = canMoveDown
            binding.btnMoveUp.alpha = if (canMoveUp) 1f else 0.3f
            binding.btnMoveDown.alpha = if (canMoveDown) 1f else 0.3f
            binding.btnMoveUp.setOnClickListener { onMoveUp(item) }
            binding.btnMoveDown.setOnClickListener { onMoveDown(item) }

            bindSizeControls(item)
        }

        private fun bindSizeControls(item: OverlayItem) {
            // All overlays (Image/Gif/Video/Browser) are independently resizable with W/H.
            // TEXT overlays only get a single proportional Scale slider.
            if (item is OverlayItem.Text) {
                // Text: single proportional scale
                binding.sizeControls.visibility = ViewGroup.VISIBLE
                // Change label to "Scale" for text
                binding.root.findViewById<android.widget.TextView>(
                    binding.root.context.resources.getIdentifier(
                        "tvWidthLabel", "id", binding.root.context.packageName
                    )
                )?.text = "Scale"
                // Hide height controls for text
                binding.seekHeight.visibility = android.view.View.GONE
                binding.btnHeightDown.visibility = android.view.View.GONE
                binding.btnHeightUp.visibility = android.view.View.GONE
                
                val seekW = binding.seekWidth
                seekW.setOnSeekBarChangeListener(null)
                seekW.progress = scaleToProgress(item.scale)
                seekW.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val scale = progressToScale(progress)
                        // For text, scale both dimensions proportionally
                        bindDetails(item, scale, scale)
                        onScaleChange(item, scale)
                        // Also update height scale to match for text
                        onHeightScaleChange(item, scale)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {
                        val scale = progressToScale(sb.progress)
                        onScaleSettled(item, scale)
                        onHeightScaleSettled(item, scale)
                    }
                })
                binding.btnWidthDown.setOnClickListener { stepTextScale(item, -SIZE_STEP) }
                binding.btnWidthUp.setOnClickListener { stepTextScale(item, SIZE_STEP) }
                return
            }

            // Images/GIFs/Videos/Browser overlays get independent W/H sliders
            binding.sizeControls.visibility = ViewGroup.VISIBLE
            // Reset label to "Width" for non-text overlays
            binding.root.findViewById<android.widget.TextView>(
                binding.root.context.resources.getIdentifier(
                    "tvWidthLabel", "id", binding.root.context.packageName
                )
            )?.text = "Width"
            binding.seekHeight.visibility = android.view.View.VISIBLE
            binding.btnHeightDown.visibility = android.view.View.VISIBLE
            binding.btnHeightUp.visibility = android.view.View.VISIBLE

            // --- Width slider (drives item.scale) ---
            val seekW = binding.seekWidth
            // Detach any recycled listener before re-seeding progress so the programmatic
            // set can't be mistaken for user input on this rebound row.
            seekW.setOnSeekBarChangeListener(null)
            seekW.progress = scaleToProgress(item.scale)
            seekW.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val scale = progressToScale(progress)
                    bindDetails(item, scale, progressToScale(binding.seekHeight.progress))
                    onScaleChange(item, scale)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    onScaleSettled(item, progressToScale(sb.progress))
                }
            })
            binding.btnWidthDown.setOnClickListener { stepWidth(item, -SIZE_STEP) }
            binding.btnWidthUp.setOnClickListener { stepWidth(item, SIZE_STEP) }

            // --- Height slider (drives item.heightScale) ---
            val seekH = binding.seekHeight
            seekH.setOnSeekBarChangeListener(null)
            seekH.progress = scaleToProgress(item.heightScale)
            seekH.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val heightScale = progressToScale(progress)
                    bindDetails(item, progressToScale(binding.seekWidth.progress), heightScale)
                    onHeightScaleChange(item, heightScale)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    onHeightScaleSettled(item, progressToScale(sb.progress))
                }
            })
            binding.btnHeightDown.setOnClickListener { stepHeight(item, -SIZE_STEP) }
            binding.btnHeightUp.setOnClickListener { stepHeight(item, SIZE_STEP) }
        }
        
        private fun stepTextScale(item: OverlayItem, delta: Int) {
            val progress = (binding.seekWidth.progress + delta).coerceIn(0, 100)
            binding.seekWidth.progress = progress
            val scale = progressToScale(progress)
            bindDetails(item, scale, scale)
            onScaleChange(item, scale)
            onScaleSettled(item, scale)
            onHeightScaleChange(item, scale)
            onHeightScaleSettled(item, scale)
        }

        private fun stepWidth(item: OverlayItem, delta: Int) {
            val progress = (binding.seekWidth.progress + delta).coerceIn(0, 100)
            binding.seekWidth.progress = progress
            val scale = progressToScale(progress)
            bindDetails(item, scale, progressToScale(binding.seekHeight.progress))
            onScaleChange(item, scale)
            onScaleSettled(item, scale)
        }

        private fun stepHeight(item: OverlayItem, delta: Int) {
            val progress = (binding.seekHeight.progress + delta).coerceIn(0, 100)
            binding.seekHeight.progress = progress
            val heightScale = progressToScale(progress)
            bindDetails(item, progressToScale(binding.seekWidth.progress), heightScale)
            onHeightScaleChange(item, heightScale)
            onHeightScaleSettled(item, heightScale)
        }

        private fun bindDetails(item: OverlayItem, scale: Float, heightScale: Float) {
            binding.tvOverlayDetails.text = when (item) {
                is OverlayItem.Text ->
                    "Text · ${item.fontSizeSp.toInt()}sp · Scale %.1fx".format(scale)
                is OverlayItem.Video ->
                    transformDetails(scale, heightScale) + if (item.loop) " · loop" else ""
                else -> transformDetails(scale, heightScale)
            }
        }

        private fun transformDetails(scale: Float, heightScale: Float): String =
            "W %.1fx · H %.1fx".format(scale, heightScale)

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
