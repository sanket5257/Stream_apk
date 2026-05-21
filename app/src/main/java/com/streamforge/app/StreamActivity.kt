package com.streamforge.app

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.base.recording.RecordController
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.util.streamclient.RtmpStreamClient
import com.pedro.library.util.streamclient.StreamClientListener
import com.streamforge.app.databinding.ActivityStreamBinding
import com.streamforge.app.util.PermissionHelper
import com.pedro.common.ConnectChecker

/**
 * Phase 2A: Camera preview with front/back switching.
 * No streaming yet — that comes in Phase 3A.
 */
class StreamActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var binding: ActivityStreamBinding
    private lateinit var rtmpCamera: RtmpCamera2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on while streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Check permissions
        if (!PermissionHelper.hasCameraAndAudio(this)) {
            PermissionHelper.requestCameraAndAudio(this) { granted ->
                if (granted) {
                    initializeCamera()
                } else {
                    Toast.makeText(
                        this,
                        R.string.permission_required,
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        } else {
            initializeCamera()
        }
    }

    private fun initializeCamera() {
        // Initialize RtmpCamera2 with OpenGlView and ConnectChecker
        rtmpCamera = RtmpCamera2(binding.openGlView, this)

        // Set up surface callbacks
        binding.openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Start preview when surface is ready
                rtmpCamera.startPreview(CameraHelper.Facing.BACK)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                // Handle surface changes if needed
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Stop preview when surface is destroyed
                if (rtmpCamera.isOnPreview) {
                    rtmpCamera.stopPreview()
                }
            }
        })

        // Switch camera button
        binding.btnSwitchCamera.setOnClickListener {
            rtmpCamera.switchCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::rtmpCamera.isInitialized && rtmpCamera.isOnPreview) {
            rtmpCamera.stopPreview()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::rtmpCamera.isInitialized && !rtmpCamera.isOnPreview) {
            rtmpCamera.startPreview()
        }
    }

    // ConnectChecker interface methods (no-op for Phase 2A)
    override fun onConnectionStarted(url: String) {
        // Will be implemented in Phase 3A
    }

    override fun onConnectionSuccess() {
        // Will be implemented in Phase 3A
    }

    override fun onConnectionFailed(reason: String) {
        // Will be implemented in Phase 3A
    }

    override fun onNewBitrate(bitrate: Long) {
        // Will be implemented in Phase 3A
    }

    override fun onDisconnect() {
        // Will be implemented in Phase 3A
    }

    override fun onAuthError() {
        // Will be implemented in Phase 3A
    }

    override fun onAuthSuccess() {
        // Will be implemented in Phase 3A
    }
}


