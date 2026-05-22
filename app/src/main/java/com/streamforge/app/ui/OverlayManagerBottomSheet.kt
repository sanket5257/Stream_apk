package com.streamforge.app.ui

import android.app.Activity
import android.content.Intent
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
 * Phase 4B: Bottom sheet for managing overlays.
 * Allows adding images, toggling visibility, and deleting overlays.
 */
class OverlayManagerBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetOverlayManagerBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var overlayStore: OverlayStore
    private lateinit var adapter: OverlayListAdapter
    
    private var onOverlaysChanged: ((List<OverlayItem>) -> Unit)? = null
    
    // Image picker launcher
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // Take persistable permission
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                
                // Create new image overlay
                val imageOverlay = OverlayItem.Image(
                    id = UUID.randomUUID().toString(),
                    uri = uri.toString()
                )
                
                lifecycleScope.launch {
                    overlayStore.addOverlay(imageOverlay)
                    loadOverlays()
                }
            }
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
        
        overlayStore = OverlayStore(requireContext())
        
        setupRecyclerView()
        setupButtons()
        loadOverlays()
    }

    private fun setupRecyclerView() {
        adapter = OverlayListAdapter(
            onVisibilityToggle = { item ->
                lifecycleScope.launch {
                    item.visible = !item.visible
                    overlayStore.updateOverlay(item)
                    loadOverlays()
                }
            },
            onDelete = { item ->
                lifecycleScope.launch {
                    overlayStore.removeOverlay(item.id)
                    loadOverlays()
                }
            }
        )
        
        binding.rvOverlays.layoutManager = LinearLayoutManager(requireContext())
        binding.rvOverlays.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnClose.setOnClickListener {
            dismiss()
        }
        
        binding.btnAddImage.setOnClickListener {
            openImagePicker()
        }
        
        binding.btnAddText.setOnClickListener {
            // Phase 5B will implement text overlay dialog
            // For now, add a simple text overlay
            val textOverlay = OverlayItem.Text(
                id = UUID.randomUUID().toString(),
                text = "Sample Text",
                fontSizeSp = 32f,
                colorArgb = 0xFFFFFFFF.toInt()
            )
            
            lifecycleScope.launch {
                overlayStore.addOverlay(textOverlay)
                loadOverlays()
            }
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        imagePickerLauncher.launch(intent)
    }

    private fun loadOverlays() {
        lifecycleScope.launch {
            val overlays = overlayStore.loadOverlays()
            adapter.submitList(overlays)
            
            // Show/hide empty state
            binding.tvEmptyState.isVisible = overlays.isEmpty()
            binding.rvOverlays.isVisible = overlays.isNotEmpty()
            
            // Notify parent activity
            onOverlaysChanged?.invoke(overlays)
        }
    }

    fun setOnOverlaysChangedListener(listener: (List<OverlayItem>) -> Unit) {
        this.onOverlaysChanged = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "OverlayManagerBottomSheet"
        
        fun newInstance(): OverlayManagerBottomSheet {
            return OverlayManagerBottomSheet()
        }
    }
}
