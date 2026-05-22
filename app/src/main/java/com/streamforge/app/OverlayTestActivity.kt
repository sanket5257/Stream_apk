package com.streamforge.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.streamforge.app.databinding.ActivityOverlayTestBinding
import com.streamforge.app.overlay.OverlayItem
import java.util.UUID

/**
 * Phase 3B: Test harness for OverlayEditorView.
 * This activity is for development/testing only.
 */
class OverlayTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOverlayTestBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOverlayTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Add some test items
        addTestItems()

        // Set up listeners
        binding.overlayEditor.setSelectionListener { id ->
            if (id != null) {
                Toast.makeText(this, "Selected: $id", Toast.LENGTH_SHORT).show()
            }
        }

        binding.overlayEditor.setItemChangeListener { item ->
            // Item changed (dragged, scaled, rotated)
            // In real app, this would update the stream overlay
        }

        // Add item button
        binding.btnAddImage.setOnClickListener {
            val newItem = OverlayItem.Image(
                id = UUID.randomUUID().toString(),
                uri = "test://image",
                x = 0.5f,
                y = 0.5f,
                scale = 1.0f,
                rotation = 0f,
                zIndex = binding.overlayEditor.getItems().size
            )
            binding.overlayEditor.addItem(newItem)
            Toast.makeText(this, "Added image overlay", Toast.LENGTH_SHORT).show()
        }

        binding.btnAddText.setOnClickListener {
            val newItem = OverlayItem.Text(
                id = UUID.randomUUID().toString(),
                text = "LIVE",
                fontSizeSp = 32f,
                colorArgb = 0xFFFF0000.toInt(),
                x = 0.5f,
                y = 0.3f,
                scale = 1.0f,
                rotation = 0f,
                zIndex = binding.overlayEditor.getItems().size
            )
            binding.overlayEditor.addItem(newItem)
            Toast.makeText(this, "Added text overlay", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addTestItems() {
        // Add a test image overlay
        val imageOverlay = OverlayItem.Image(
            id = "test-image-1",
            uri = "test://logo",
            x = 0.2f,
            y = 0.2f,
            scale = 1.0f,
            rotation = 0f,
            zIndex = 0
        )
        binding.overlayEditor.addItem(imageOverlay)

        // Add a test text overlay
        val textOverlay = OverlayItem.Text(
            id = "test-text-1",
            text = "StreamForge",
            fontSizeSp = 24f,
            colorArgb = 0xFFFFFFFF.toInt(),
            x = 0.8f,
            y = 0.8f,
            scale = 1.0f,
            rotation = 0f,
            zIndex = 1
        )
        binding.overlayEditor.addItem(textOverlay)
    }
}
