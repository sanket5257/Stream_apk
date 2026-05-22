package com.streamforge.app.stream

import com.pedro.encoder.input.audio.CustomAudioEffect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Phase 7 polish: pass-through audio effect that computes the RMS amplitude of every
 * PCM buffer RootEncoder hands us, exposing a 0–100 level for the on-screen meter.
 *
 * We don't mutate the buffer — bytes go in and out unchanged. The encoder still
 * receives the original audio; we just peek at it.
 */
class AudioLevelEffect : CustomAudioEffect() {

    @Volatile var levelPercent: Int = 0
        private set

    override fun process(pcmBuffer: ByteArray): ByteArray {
        levelPercent = computeRmsPercent(pcmBuffer)
        return pcmBuffer
    }

    private fun computeRmsPercent(buffer: ByteArray): Int {
        if (buffer.size < 2) return 0
        // 16-bit little-endian PCM.
        var sumSquares = 0.0
        var sampleCount = 0
        var i = 0
        while (i + 1 < buffer.size) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
            sampleCount++
            i += 2
        }
        if (sampleCount == 0) return 0
        val rms = sqrt(sumSquares / sampleCount)
        // Convert to dBFS, then map roughly -60..0 dBFS to 0..100 for a usable visual range.
        val dbfs = if (rms > 0.0) 20.0 * kotlin.math.log10(rms) else -100.0
        val pct = ((dbfs + 60.0) / 60.0 * 100.0).toInt()
        return max(0, min(100, pct))
    }
}
