package com.streamforge.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.streamforge.app.auth.AuthManager
import com.streamforge.app.auth.DeviceHelper
import com.streamforge.app.auth.LoginActivity
import com.streamforge.app.databinding.ActivityProfileBinding
import com.streamforge.app.storage.StreamPrefs
import kotlinx.coroutines.launch

/**
 * Profile / account screen. Surfaces account info and the operations a user
 * actually needs: YouTube connection, stream settings, app theme, and log out.
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var authManager: AuthManager
    private val streamPrefs by lazy { StreamPrefs(this) }
    private val uiPrefs by lazy { getSharedPreferences("ui_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = AuthManager(this)

        binding.btnBack.setOnClickListener { finish() }

        val username = authManager.getUsername() ?: "Account"
        binding.tvUsername.text = username
        binding.tvAccountUsername.text = username
        binding.tvAccountDevice.text = DeviceHelper.getDeviceName()
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        binding.rowYoutube.setOnClickListener {
            YoutubeKeyDialog.show(this) { refreshYoutubeState() }
        }
        binding.rowStreamSettings.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        binding.rowTheme.setOnClickListener { showThemeDialog() }
        binding.btnLogout.setOnClickListener { confirmLogout() }
    }

    override fun onResume() {
        super.onResume()
        binding.tvThemeState.text = themeLabel(savedThemeMode())
        refreshYoutubeState()
    }

    private fun refreshYoutubeState() {
        lifecycleScope.launch {
            val key = streamPrefs.load().streamKey
            binding.tvYoutubeState.text =
                if (key.isNotBlank()) "Stream key saved · ••••${key.takeLast(4)}"
                else "No stream key — tap to add"
        }
    }

    private fun savedThemeMode(): Int =
        uiPrefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    private fun themeLabel(mode: Int): String = when (mode) {
        AppCompatDelegate.MODE_NIGHT_NO -> "Light"
        AppCompatDelegate.MODE_NIGHT_YES -> "Dark"
        else -> "System default"
    }

    private fun showThemeDialog() {
        val modes = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES
        )
        val labels = arrayOf("System default", "Light", "Dark")
        val current = modes.indexOf(savedThemeMode()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Theme")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val mode = modes[which]
                uiPrefs.edit().putInt(KEY_NIGHT_MODE, mode).apply()
                AppCompatDelegate.setDefaultNightMode(mode)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Log out")
            .setMessage("You'll need to log in again to stream from this device.")
            .setPositiveButton("Log out") { _, _ ->
                lifecycleScope.launch {
                    authManager.logout()
                    val intent = Intent(this@ProfileActivity, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val KEY_NIGHT_MODE = "night_mode"
    }
}
