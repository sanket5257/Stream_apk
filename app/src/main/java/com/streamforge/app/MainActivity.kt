package com.streamforge.app

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streamforge.app.databinding.ActivityMainBinding
import com.streamforge.app.storage.StreamConfig
import com.streamforge.app.storage.StreamPrefs
import kotlinx.coroutines.launch

/**
 * Phase 2B: Stream configuration screen.
 * User enters RTMP URL, stream key, and quality settings.
 * Settings are persisted using DataStore and EncryptedSharedPreferences.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var streamPrefs: StreamPrefs
    private var isInitialLoad = true

    // Resolution options
    private data class Resolution(val label: String, val width: Int, val height: Int)
    
    private val resolutions = listOf(
        Resolution("854x480 (480p)", 854, 480),
        Resolution("1280x720 (720p)", 1280, 720),
        Resolution("1920x1080 (1080p)", 1920, 1080)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamPrefs = StreamPrefs(this)

        setupResolutionDropdown()
        setupBitrateSliders()
        setupSaveButton()
        setupDevMenu()
    }

    override fun onResume() {
        super.onResume()
        loadConfig()
    }

    private fun setupResolutionDropdown() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            resolutions.map { it.label }
        )
        binding.actvResolution.setAdapter(adapter)
        
        // Set default selection
        binding.actvResolution.setText(resolutions[1].label, false) // 720p default
    }

    private fun setupBitrateSliders() {
        // Video bitrate slider
        binding.sliderVideoBitrate.addOnChangeListener { _, value, _ ->
            binding.tvVideoBitrateLabel.text = 
                getString(R.string.video_bitrate_label, value.toInt())
        }
        
        // Audio bitrate slider
        binding.sliderAudioBitrate.addOnChangeListener { _, value, _ ->
            binding.tvAudioBitrateLabel.text = 
                getString(R.string.audio_bitrate_label, value.toInt())
        }
        
        // Set initial labels
        binding.tvVideoBitrateLabel.text = 
            getString(R.string.video_bitrate_label, binding.sliderVideoBitrate.value.toInt())
        binding.tvAudioBitrateLabel.text = 
            getString(R.string.audio_bitrate_label, binding.sliderAudioBitrate.value.toInt())
    }

    private fun setupSaveButton() {
        binding.btnSaveAndGoLive.setOnClickListener {
            if (validateInputs()) {
                saveConfigAndLaunchStream()
            }
        }
    }

    private fun setupDevMenu() {
        // Long-press on title to open overlay test activity (dev feature)
        binding.tvTitle.setOnLongClickListener {
            startActivity(Intent(this, OverlayTestActivity::class.java))
            Toast.makeText(this, "Opening Overlay Test", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun loadConfig() {
        lifecycleScope.launch {
            try {
                val config = streamPrefs.load()
                
                // Populate form with saved values
                binding.etRtmpUrl.setText(config.rtmpUrl)
                binding.etStreamKey.setText(config.streamKey)
                
                // Set resolution
                val resolutionIndex = resolutions.indexOfFirst { 
                    it.width == config.width && it.height == config.height 
                }
                if (resolutionIndex >= 0) {
                    binding.actvResolution.setText(resolutions[resolutionIndex].label, false)
                }
                
                // Set bitrates
                binding.sliderVideoBitrate.value = config.videoBitrateKbps.toFloat()
                binding.sliderAudioBitrate.value = config.audioBitrateKbps.toFloat()
                
                isInitialLoad = false
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error loading config: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun validateInputs(): Boolean {
        var isValid = true

        // Validate RTMP URL
        val url = binding.etRtmpUrl.text.toString().trim()
        when {
            url.isEmpty() -> {
                binding.tilRtmpUrl.error = getString(R.string.error_empty_url)
                isValid = false
            }
            !url.startsWith("rtmp://") && !url.startsWith("rtmps://") -> {
                binding.tilRtmpUrl.error = getString(R.string.error_invalid_url)
                isValid = false
            }
            else -> {
                binding.tilRtmpUrl.error = null
            }
        }

        // Validate stream key
        val key = binding.etStreamKey.text.toString().trim()
        if (key.isEmpty()) {
            binding.tilStreamKey.error = getString(R.string.error_empty_key)
            isValid = false
        } else {
            binding.tilStreamKey.error = null
        }

        return isValid
    }

    private fun saveConfigAndLaunchStream() {
        lifecycleScope.launch {
            try {
                // Get selected resolution
                val selectedResolutionLabel = binding.actvResolution.text.toString()
                val resolution = resolutions.find { it.label == selectedResolutionLabel } 
                    ?: resolutions[1] // Default to 720p

                // Build config
                val config = StreamConfig(
                    rtmpUrl = binding.etRtmpUrl.text.toString().trim(),
                    streamKey = binding.etStreamKey.text.toString().trim(),
                    width = resolution.width,
                    height = resolution.height,
                    fps = 30,
                    videoBitrateKbps = binding.sliderVideoBitrate.value.toInt(),
                    audioBitrateKbps = binding.sliderAudioBitrate.value.toInt()
                )

                // Save config
                streamPrefs.save(config)
                
                Toast.makeText(this@MainActivity, R.string.config_saved, Toast.LENGTH_SHORT).show()

                // Launch stream activity
                startActivity(Intent(this@MainActivity, StreamActivity::class.java))
                
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity, 
                    "Error saving config: ${e.message}", 
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
