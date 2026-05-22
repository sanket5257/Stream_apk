package com.streamforge.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.streamforge.app.databinding.ActivityMainBinding

/**
 * Entry screen. Will become the stream configuration screen in Phase 2B
 * (RTMP URL input, stream key input, resolution / bitrate selectors).
 *
 * For now it's a minimal stub that proves the project builds and installs.
 * Phase 2A: Added temporary "Open Stream" button to launch StreamActivity.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Phase 2A: Temporary button to launch StreamActivity
        binding.btnOpenStream.setOnClickListener {
            startActivity(Intent(this, StreamActivity::class.java))
        }
    }
}
