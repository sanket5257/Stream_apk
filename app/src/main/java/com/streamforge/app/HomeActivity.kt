package com.streamforge.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.streamforge.app.databinding.ActivityHomeBinding
import com.streamforge.app.storage.StreamPrefs
import com.streamforge.app.update.UpdateFlow
import kotlinx.coroutines.launch

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

        // Sideloaded builds have nobody to tell them an update exists, so check here — quietly,
        // at most twice a day, and never while the user is mid-stream (this screen isn't).
        UpdateFlow.checkSilently(this)
    }

    override fun onResume() {
        super.onResume()
        refreshKeyStatus()
    }

    private fun refreshKeyStatus() {
        lifecycleScope.launch {
            val key = streamPrefs.load().streamKey
            binding.tvKeyStatus.text =
                if (key.isNotBlank()) "Saved · ••••${key.takeLast(4)}"
                else "Not set — tap to add"
        }
    }

    /** Go live: requires a stream key; otherwise prompt to add one first. */
    private fun goLive() {
        lifecycleScope.launch {
            val key = streamPrefs.load().streamKey
            if (key.isBlank()) {
                Snackbar.make(binding.root, "Add your YouTube stream key first", Snackbar.LENGTH_SHORT).show()
                YoutubeKeyDialog.show(this@HomeActivity) { refreshKeyStatus() }
            } else {
                startActivity(Intent(this@HomeActivity, StreamActivity::class.java))
            }
        }
    }
}
