package com.streamforge.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.streamforge.app.databinding.BottomSheetOverlayManagerBinding
import com.streamforge.app.overlay.OverlayItem
import com.streamforge.app.overlay.OverlayStore
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Phase 4B + 5B: Bottom sheet for managing overlays.
 * Supports adding Image, Text, GIF, and Video overlays, toggling visibility, and deletion.
 */
class OverlayManagerBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetOverlayManagerBinding? = null
    private val binding get() = _binding!!

    private lateinit var overlayStore: OverlayStore
    private lateinit var adapter: OverlayListAdapter

    private var onOverlaysChanged: ((List<OverlayItem>) -> Unit)? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handlePickerResult(result.resultCode, result.data) { uri ->
            OverlayItem.Image(id = UUID.randomUUID().toString(), uri = uri.toString())
        }
    }

    private val gifPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handlePickerResult(result.resultCode, result.data) { uri ->
            OverlayItem.Gif(id = UUID.randomUUID().toString(), uri = uri.toString())
        }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handlePickerResult(result.resultCode, result.data) { uri ->
            OverlayItem.Video(id = UUID.randomUUID().toString(), uri = uri.toString())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetOverlayManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Configure bottom sheet behavior
        dialog?.setOnShowListener { dialogInterface ->
            val bottomSheet = (dialogInterface as? com.google.android.material.bottomsheet.BottomSheetDialog)
                ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.peekHeight = 800
                behavior.isDraggable = true
            }
        }
        
        overlayStore = OverlayStore(requireContext())
        setupRecyclerView()
        setupButtons()
        loadOverlays()
    }

    private fun setupRecyclerView() {
        adapter = OverlayListAdapter(
            onVisibilityToggle = { item ->
                lifecycleScope.launch {
                    val toggled: OverlayItem = when (item) {
                        is OverlayItem.Image -> item.copy().also { it.visible = !item.visible }
                        is OverlayItem.Text -> item.copy().also { it.visible = !item.visible }
                        is OverlayItem.Gif -> item.copy().also { it.visible = !item.visible }
                        is OverlayItem.Video -> item.copy().also { it.visible = !item.visible }
                    }
                    overlayStore.updateOverlay(toggled)
                    loadOverlays()
                }
            },
            onDelete = { item ->
                lifecycleScope.launch {
                    overlayStore.removeOverlay(item.id)
                    loadOverlays()
                }
            },
            onEdit = { item ->
                if (item is OverlayItem.Text) showTextDialog(item)
            }
        )
        binding.rvOverlays.layoutManager = LinearLayoutManager(requireContext())
        binding.rvOverlays.adapter = adapter
        binding.rvOverlays.isNestedScrollingEnabled = true
    }

    private fun setupButtons() {
        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnAddImage.setOnClickListener { openPicker("image/*", imagePickerLauncher) }
        binding.btnAddGif.setOnClickListener { openPicker("image/gif", gifPickerLauncher) }
        binding.btnAddVideo.setOnClickListener { openPicker("video/*", videoPickerLauncher) }
        binding.btnAddText.setOnClickListener { showTextDialog(null) }
    }

    private fun showTextDialog(existing: OverlayItem.Text?) {
        val dialog = TextOverlayDialog()
        existing?.let { dialog.setExisting(it) }
        dialog.setOnResult { result ->
            lifecycleScope.launch {
                if (existing != null) overlayStore.updateOverlay(result)
                else overlayStore.addOverlay(result)
                loadOverlays()
            }
        }
        dialog.show(parentFragmentManager, TextOverlayDialog.TAG)
    }

    private fun openPicker(
        mimeType: String,
        launcher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        launcher.launch(intent)
    }

    private fun handlePickerResult(
        resultCode: Int,
        data: Intent?,
        build: (Uri) -> OverlayItem
    ) {
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers don't grant persistable perms; URI may still work for this session.
        }
        val overlay = build(uri)
        lifecycleScope.launch {
            overlayStore.addOverlay(overlay)
            loadOverlays()
        }
    }

    private fun loadOverlays() {
        lifecycleScope.launch {
            val overlays = overlayStore.loadOverlays()
            adapter.submitList(overlays)
            binding.tvEmptyState.isVisible = overlays.isEmpty()
            binding.rvOverlays.isVisible = overlays.isNotEmpty()
            onOverlaysChanged?.invoke(overlays)
        }
    }

    fun setOnOverlaysChangedListener(listener: (List<OverlayItem>) -> Unit) {
        onOverlaysChanged = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "OverlayManagerBottomSheet"
        fun newInstance(): OverlayManagerBottomSheet = OverlayManagerBottomSheet()
    }
}
