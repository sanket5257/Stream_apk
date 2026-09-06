package com.streamforge.app.ui

import android.annotation.SuppressLint
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
    private val onLockToggle: (OverlayItem) -> Unit = {},
    private val onDelete: (OverlayItem) -> Unit,
    private val onEdit: (OverlayItem) -> Unit = {},
    private val onScaleChange: (OverlayItem, Float) -> Unit = { _, _ -> },
    private val onScaleSettled: (OverlayItem, Float) -> Unit = { _, _ -> },
    private val onHeightScaleChange: (OverlayItem, Float) -> Unit = { _, _ -> },
    private val onHeightScaleSettled: (OverlayItem, Float) -> Unit = { _, _ -> },
    // Text overlays scale PROPORTIONALLY: one value drives width AND height together. These
    // must apply both in a single item copy — issuing separate width/height updates from the
    // same (stale) item makes the second overwrite the first, so the text never resizes.
    private val onProportionalScaleChange: (OverlayItem, Float) -> Unit = { _, _ -> },
    private val onProportionalScaleSettled: (OverlayItem, Float) -> Unit = { _, _ -> },
    // Called when the user touches a row's drag handle, so the host can start the drag.
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {}
) : RecyclerView.Adapter<OverlayListAdapter.OverlayViewHolder>() {

    private val items = mutableListOf<OverlayItem>()

    /**
     * Display name for a graphics pack, resolved from the asset catalogue. Cached per adapter
     * so binding a long list doesn't re-read the catalogue for every row.
     */
    private val packNames = mutableMapOf<String, String>()

    private fun packNameFor(packId: String): String = packNames.getOrPut(packId) {
        try {
            com.streamforge.app.packs.PackCatalog
                .loadBlocking(itemViewContext)
                .byId(packId)?.name ?: packId
        } catch (t: Throwable) {
            android.util.Log.w("OverlayListAdapter", "Couldn't resolve pack name for $packId", t)
            packId
        }
    }

    /**
     * Context for catalogue lookups. Set on first bind — an adapter has no Context of its own,
     * and the catalogue needs one to read from assets.
     */
    private lateinit var itemViewContext: android.content.Context

    // Ids of rows whose size panel is expanded. Rows are collapsed by default so many
    // overlays stay visible at once; the user expands only the one they're tuning.
    private val expandedIds = mutableSetOf<String>()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<OverlayItem>) {
        items.clear()
        items.addAll(list)
        // Drop expansion state for overlays that no longer exist.
        expandedIds.retainAll(list.mapTo(mutableSetOf()) { it.id })
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
        if (!::itemViewContext.isInitialized) itemViewContext = parent.context.applicationContext
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
                is OverlayItem.Pack -> {
                    // Name the graphic by its definition, falling back to the raw id if the
                    // pack was removed from assets — better a stale name than a blank row the
                    // user can't identify to delete.
                    binding.tvOverlayName.text = packNameFor(item.packId)
                    binding.ivOverlayIcon.setImageResource(R.drawable.ic_layers)
                }
            }
            bindDetails(item, item.scale, item.heightScale)

            binding.btnToggleVisibility.setIconResource(
                if (item.visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off
            )
            binding.btnToggleVisibility.setOnClickListener { onVisibilityToggle(item) }

            bindLock(item)

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
            applyExpansion(item)
        }

        /**
         * Show/hide the size panel per the row's expansion state. Browser overlays have no
         * size controls, so their expand toggle is hidden entirely. Runs after
         * [bindSizeControls] so it has the final say on the panel's visibility.
         */
        /**
         * Bind the lock toggle. A locked overlay ignores preview gestures and hides its size
         * controls, so a dialed-in layout can't be nudged by accident. Browser overlays are
         * inherently full-frame / non-movable, so they get no lock control.
         */
        private fun bindLock(item: OverlayItem) {
            if (item is OverlayItem.Browser) {
                binding.btnToggleLock.visibility = android.view.View.GONE
                return
            }
            binding.btnToggleLock.visibility = android.view.View.VISIBLE
            binding.btnToggleLock.setIconResource(
                if (item.locked) R.drawable.ic_lock else R.drawable.ic_lock_open
            )
            binding.btnToggleLock.setIconTintResource(
                if (item.locked) R.color.action_green else R.color.text_secondary
            )
            binding.btnToggleLock.contentDescription = binding.root.context.getString(
                if (item.locked) R.string.overlay_unlock else R.string.overlay_lock
            )
            binding.btnToggleLock.setOnClickListener { onLockToggle(item) }
        }

        private fun applyExpansion(item: OverlayItem) {
            if (item is OverlayItem.Browser || item.locked) {
                binding.btnExpandSize.visibility = android.view.View.GONE
                binding.sizeControls.visibility = android.view.View.GONE
                return
            }
            binding.btnExpandSize.visibility = android.view.View.VISIBLE
            val expanded = expandedIds.contains(item.id)
            binding.sizeControls.visibility =
                if (expanded) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnExpandSize.setIconResource(
                if (expanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down
            )
            binding.btnExpandSize.setOnClickListener {
                // Toggle the panel directly on the bound views instead of notifyItemChanged.
                // A rebind runs the RecyclerView change animation (a ~250ms cross-fade), which
                // made the size slider look like it "took time to appear" after tapping expand.
                val nowExpanded = if (expandedIds.contains(item.id)) {
                    expandedIds.remove(item.id); false
                } else {
                    expandedIds.add(item.id); true
                }
                binding.sizeControls.visibility =
                    if (nowExpanded) android.view.View.VISIBLE else android.view.View.GONE
                binding.btnExpandSize.setIconResource(
                    if (nowExpanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down
                )
            }
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
                binding.tvWidthLabel.text = "Size"
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
                        // One combined update sets width AND height together (proportional).
                        onProportionalScaleChange(item, scale)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {
                        onProportionalScaleSettled(item, progressToScale(sb.progress))
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
            onProportionalScaleChange(item, scale)
            onProportionalScaleSettled(item, scale)
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
                    "Text · Size %.1fx".format(scale)
                is OverlayItem.Video ->
                    transformDetails(scale, heightScale) + if (item.loop) " · loop" else ""
                else -> transformDetails(scale, heightScale)
            }
        }

        private fun transformDetails(scale: Float, heightScale: Float): String =
            "W %.1fx · H %.1fx".format(scale, heightScale)

        // Thumbnails are decoded small, off the main thread and cached — see OverlayThumbnails
        // for why doing it inline was both an OutOfMemoryError and a scroll stall.
        private fun loadImageThumbnail(uriString: String) {
            OverlayThumbnails.load(
                binding.ivOverlayIcon, uriString, isVideo = false, fallbackRes = R.drawable.ic_image
            )
        }

        private fun loadVideoThumbnail(uriString: String) {
            OverlayThumbnails.load(
                binding.ivOverlayIcon, uriString, isVideo = true, fallbackRes = R.drawable.ic_video
            )
        }
    }

    companion object {
        private const val MIN_SCALE = 0.2f
        private const val MAX_SCALE = 5.0f
        private const val SIZE_STEP = 5

        /**
         * Map a stored scale onto the 0..100 slider.
         *
         * The clamp happens BEFORE rounding on purpose: `Float.roundToInt()` throws
         * IllegalArgumentException("Cannot round NaN value") rather than returning anything,
         * and a NaN scale is reachable from a persisted overlay (a gesture computed against a
         * zero-width view divides by zero). Binding a row must never be able to throw.
         */
        fun scaleToProgress(scale: Float): Int {
            val safe = if (scale.isFinite()) scale else 1f
            return ((safe - MIN_SCALE) / (MAX_SCALE - MIN_SCALE) * 100f)
                .coerceIn(0f, 100f)
                .roundToInt()
        }

        fun progressToScale(progress: Int): Float =
            MIN_SCALE + (progress / 100f) * (MAX_SCALE - MIN_SCALE)
    }
}
