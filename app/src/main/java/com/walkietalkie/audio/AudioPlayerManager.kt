package com.walkietalkie.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Low-latency audio playback wrapper around [AudioTrack].
 *
 * Configuration matches [AudioRecorderManager]:
 *   • PCM 16-bit, 16 kHz, mono
 *   • PERFORMANCE_MODE_LOW_LATENCY
 *   • USAGE_VOICE_COMMUNICATION
 */
class AudioPlayerManager {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = AudioRecorderManager.SAMPLE_RATE
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    // ── Initialization ───────────────────────────────────────────────────

    /** Create and initialise the [AudioTrack]. */
    fun initialize(): Boolean {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuffer, AudioRecorderManager.BUFFER_SIZE_BYTES * 2)

        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val fmt = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize")
                audioTrack?.release()
                audioTrack = null
                false
            } else {
                Log.d(TAG, "AudioTrack ready (buffer=$bufferSize B)")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Init error", e)
            false
        }
    }

    // ── Playback control ─────────────────────────────────────────────────

    /** Begin playback. Call [write] to feed PCM data. */
    fun start() {
        if (isPlaying) return
        if (audioTrack == null && !initialize()) return
        try {
            audioTrack?.play()
            isPlaying = true
            Log.d(TAG, "Playback started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting playback", e)
        }
    }

    /** Write PCM data for immediate playback. */
    fun write(data: ByteArray, length: Int) {
        if (!isPlaying) return
        try {
            audioTrack?.write(data, 0, length)
        } catch (e: Exception) {
            Log.e(TAG, "Write error", e)
        }
    }

    /** Stop playback and flush buffers. */
    fun stop() {
        if (!isPlaying) return
        try {
            audioTrack?.stop()
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback", e)
        }
        isPlaying = false
        Log.d(TAG, "Playback stopped")
    }

    /** Release all native resources. */
    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        Log.d(TAG, "AudioTrack released")
    }

    fun isPlaying(): Boolean = isPlaying
}
