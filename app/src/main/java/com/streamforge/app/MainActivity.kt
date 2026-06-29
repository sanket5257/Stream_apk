package com.streamforge.app

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streamforge.app.auth.AuthManager
import com.streamforge.app.auth.LoginActivity
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
    private lateinit var authManager: AuthManager
    private var isInitialLoad = true

    // Resolution options
    private data class Resolution(val label: String, val width: Int, val height: Int)
    
    private val resolutions = listOf(
        Resolution("854x480 (480p)", 854, 480),
        Resolution("1280x720 (720p)", 1280, 720),
        Resolution("1920x1080 (1080p)", 1920, 1080)
    )

    private data class MicOption(val label: String, val deviceId: Int)
    private var micOptions: List<MicOption> = listOf(MicOption("Default (system mic)", 0))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamPrefs = StreamPrefs(this)
        authManager = AuthManager(this)

        setupToolbar()
        setupResolutionDropdown()
        setupBitrateSliders()
        setupMicDropdown()
        setupSaveButton()
        setupDevMenu()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                showLogoutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout? This will free up your device slot and you'll need to login again.")
            .setPositiveButton("Logout") { _, _ ->
                lifecycleScope.launch {
                    authManager.logout()
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Only load config on first launch, not every time we return from StreamActivity
        if (isInitialLoad) {
            loadConfig()
        }
    }

    private fun setupResolutionDropdown() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            resolutions.map { it.label }
        )
        binding.actvResolution.setAdapter(adapter)

        // Set default selection
        binding.actvResolution.setText(resolutions[2].label, false) // 1080p default
    }

    private fun setupBitrateSliders() {
        // Video bitrate slider
        binding.sliderVideoBitrate.addOnChangeListener { _, value, _ ->
            binding.tvVideoBitrateValue.text = "${value.toInt()} kbps"
        }
        
        // Audio bitrate slider
        binding.sliderAudioBitrate.addOnChangeListener { _, value, _ ->
            binding.tvAudioBitrateValue.text = "${value.toInt()} kbps"
        }
        
        // Set initial labels
        binding.tvVideoBitrateValue.text = "${binding.sliderVideoBitrate.value.toInt()} kbps"
        binding.tvAudioBitrateValue.text = "${binding.sliderAudioBitrate.value.toInt()} kbps"
    }

    private fun setupMicDropdown() {
        micOptions = enumerateMicOptions()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            micOptions.map { it.label }
        )
        binding.actvMicSource.setAdapter(adapter)
        binding.actvMicSource.setText(micOptions[0].label, false)
    }

    private fun enumerateMicOptions(): List<MicOption> {
        val list = mutableListOf(MicOption(getString(R.string.mic_source_default), 0))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return list
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = try {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        } catch (_: Exception) {
            return list
        }
        devices.forEach { d ->
            val label = "${describeDeviceType(d.type)} · ${d.productName}"
            list += MicOption(label, d.id)
        }
        return list
    }

    private fun describeDeviceType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        else -> "Mic"
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
                binding.etBackupRtmpUrl.setText(config.backupRtmpUrl)

                // Mic source — fall back to "Default" if the saved device is gone.
                val micIndex = micOptions.indexOfFirst { it.deviceId == config.preferredMicId }
                binding.actvMicSource.setText(
                    micOptions[if (micIndex >= 0) micIndex else 0].label,
                    false
                )
                
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
            } catch (e: Throwable) {
                android.util.Log.e("MainActivity", "Error loading config", e)
                val detail = e.message ?: e.javaClass.simpleName
                Toast.makeText(this@MainActivity, "Error loading config: $detail", Toast.LENGTH_SHORT).show()
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
                    ?: resolutions[2] // Default to 1080p

                // Selected mic option
                val micLabel = binding.actvMicSource.text.toString()
                val micId = micOptions.firstOrNull { it.label == micLabel }?.deviceId ?: 0

                // Build config
                val config = StreamConfig(
                    rtmpUrl = binding.etRtmpUrl.text.toString().trim(),
                    streamKey = binding.etStreamKey.text.toString().trim(),
                    width = resolution.width,
                    height = resolution.height,
                    fps = 30,
                    videoBitrateKbps = binding.sliderVideoBitrate.value.toInt(),
                    audioBitrateKbps = binding.sliderAudioBitrate.value.toInt(),
                    backupRtmpUrl = binding.etBackupRtmpUrl.text.toString().trim(),
                    preferredMicId = micId
                )

                // Save config
                streamPrefs.save(config)
                
                Toast.makeText(this@MainActivity, R.string.config_saved, Toast.LENGTH_SHORT).show()

                // Launch stream activity
                startActivity(Intent(this@MainActivity, StreamActivity::class.java))
                
            } catch (e: Throwable) {
                android.util.Log.e("MainActivity", "Error saving config", e)
                val detail = e.message ?: e.javaClass.simpleName
                Toast.makeText(
                    this@MainActivity,
                    "Error saving config: $detail",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
