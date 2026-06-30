package com.streamforge.app.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.recyclerview.widget.RecyclerView
import com.streamforge.app.R
import com.streamforge.app.databinding.ItemOverlayRowBinding
import com.streamforge.app.overlay.OverlayItem
import kotlin.math.roundToInt

/**
 * RecyclerView adapter for the overlay list.
 *
 * Stacking (z-order) is changed by DRAG-to-reorder via the drag handle — the list is
 * shown front-most first, so dragging a row up brings it toward the front. The host
 * persists the new order (re-stamped z-index) when the drag settles.
 *
 * Backed by a plain mutable list (not ListAdapter) so drag moves can mutate order
 * directly without fighting an async differ.
 */
class OverlayListAdapter(
    private val onVisibilityToggle: (OverlayItem) -> Unit,
    private val onDelete: (OverlayItem) -> Unit,
    private val onEdit: (OverlayItem) -> Unit = {},
    private val onScaleChange: (OverlayItem, Float) -> Unit = { _, _ -> },
    private val onScaleSettled: (OverlayItem, Float) -> Unit = { _, _ -> },
    private val onHeightScaleChange: (OverlayItem, Float) -> Unit = { _, _ -> },
    private val onHeightScaleSettled: (OverlayItem, Float) -> Unit = { _, _ -> },
    // Called when the user touches a row's drag handle, so the host can start the drag.
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {}
) : RecyclerView.Adapter<OverlayListAdapter.OverlayViewHolder>() {

    private val items = mutableListOf<OverlayItem>()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<OverlayItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** Reorder during a drag gesture (front-most first display order). */
    fun moveItem(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
    }

    /** Current display order (front-most first), for persistence on drop. */
    fun currentOrder(): List<OverlayItem> = items.toList()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OverlayViewHolder {
        val binding = ItemOverlayRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return OverlayViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OverlayViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class OverlayViewHolder(
        private val binding: ItemOverlayRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: OverlayItem) {
            when (item) {
                is OverlayItem.Image -> {
                    binding.tvOverlayName.text = "Image"
                    loadImageThumbnail(item.uri)
                }
                is OverlayItem.Text -> {
                    binding.tvOverlayName.text = item.text
                    binding.ivOverlayIcon.setImageResource(R.drawable.ic_text)
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
                    binding.ivOverlayIcon.setImageResource(R.drawable.ic_link)
                }
            }
            bindDetails(item, item.scale, item.heightScale)

            binding.btnToggleVisibility.setIconResource(
                if (item.visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off
            )
            binding.btnToggleVisibility.setOnClickListener { onVisibilityToggle(item) }
            binding.btnDelete.setOnClickListener { onDelete(item) }
            binding.root.setOnClickListener {
                if (item is OverlayItem.Text) onEdit(item)
            }

            // Drag handle starts a reorder drag.
            binding.ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
            }

            bindSizeControls(item)
        }

        private fun bindSizeControls(item: OverlayItem) {
            // Browser/URL overlays render full-frame automatically — no size controls.
            if (item is OverlayItem.Browser) {
                binding.sizeControls.visibility = android.view.View.GONE
                return
            }
            // Image/Gif/Video are independently resizable with W/H.
            // TEXT overlays only get a single proportional Scale slider.
            if (item is OverlayItem.Text) {
                binding.sizeControls.visibility = ViewGroup.VISIBLE
                binding.tvWidthLabel.text = "Scale"
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
                        bindDetails(item, scale, scale)
                        onScaleChange(item, scale)
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

            binding.sizeControls.visibility = ViewGroup.VISIBLE
            binding.tvWidthLabel.text = "Width"
            binding.seekHeight.visibility = android.view.View.VISIBLE
            binding.btnHeightDown.visibility = android.view.View.VISIBLE
            binding.btnHeightUp.visibility = android.view.View.VISIBLE

            val seekW = binding.seekWidth
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
                is OverlayItem.Browser -> "Web overlay · full screen"
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
                        binding.ivOverlayIcon.setImageResource(R.drawable.ic_image)
                    }
                }
            } catch (_: Exception) {
                binding.ivOverlayIcon.setImageResource(R.drawable.ic_image)
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
                    binding.ivOverlayIcon.setImageResource(R.drawable.ic_video)
                }
            } catch (_: Exception) {
                binding.ivOverlayIcon.setImageResource(R.drawable.ic_video)
            } finally {
                try { retriever.release() } catch (_: Exception) { }
            }
        }
    }

    companion object {
        private const val MIN_SCALE = 0.2f
        private const val MAX_SCALE = 5.0f
        private const val SIZE_STEP = 5

        fun scaleToProgress(scale: Float): Int =
            ((scale - MIN_SCALE) / (MAX_SCALE - MIN_SCALE) * 100f).roundToInt().coerceIn(0, 100)

        fun progressToScale(progress: Int): Float =
            MIN_SCALE + (progress / 100f) * (MAX_SCALE - MIN_SCALE)
    }
}
