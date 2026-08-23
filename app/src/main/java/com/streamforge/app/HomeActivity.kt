package com.streamforge.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.streamforge.app.databinding.ActivityHomeBinding
import com.streamforge.app.storage.StreamPrefs
import com.streamforge.app.update.UpdateFlow
import com.streamforge.app.util.CrashDialog
import com.streamforge.app.util.safeLaunch

/**
 * Home / landing screen (post-login). A focused streaming launchpad:
 * Go Live, plus the two real setup pieces (YouTube stream key, stream quality).
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val streamPrefs by lazy { StreamPrefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ivAvatar.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        binding.btnGoLive.setOnClickListener { goLive() }
        binding.cardKey.setOnClickListener {
            YoutubeKeyDialog.show(this) { refreshKeyStatus() }
        }
        binding.cardQuality.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // If the last session ended in a crash, say so here rather than letting the user
        // guess why they were suddenly back at the login screen.
        CrashDialog.showIfCrashed(this)

        // Sideloaded builds have nobody to tell them an update exists, so check here — quietly,
        // at most twice a day, and never while the user is mid-stream (this screen isn't).
        UpdateFlow.checkSilently(this)
    }

    override fun onResume() {
        super.onResume()
        refreshKeyStatus()
    }

    private fun refreshKeyStatus() {
        // Guarded: reading the key goes through DataStore + the Android keystore, both of
        // which can throw on a device whose encrypted prefs got out of sync. A failed status
        // label must not be fatal.
        safeLaunch(TAG, "reading stream key status") {
            val key = streamPrefs.load().streamKey
            binding.tvKeyStatus.text =
                if (key.isNotBlank()) "Saved · ••••${key.takeLast(4)}"
                else "Not set — tap to add"
        }
    }

    /** Go live: requires a stream key; otherwise prompt to add one first. */
    private fun goLive() {
        safeLaunch(TAG, "starting the live flow") {
            val key = streamPrefs.load().streamKey
            if (key.isBlank()) {
                Snackbar.make(binding.root, "Add your YouTube stream key first", Snackbar.LENGTH_SHORT).show()
                YoutubeKeyDialog.show(this@HomeActivity) { refreshKeyStatus() }
            } else {
                startActivity(Intent(this@HomeActivity, StreamActivity::class.java))
            }
        }
    }

    private companion object {
        const val TAG = "HomeActivity"
    }
}
