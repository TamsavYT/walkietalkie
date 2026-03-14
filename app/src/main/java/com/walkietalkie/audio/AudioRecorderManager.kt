package com.walkietalkie.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-latency audio capture wrapper around [AudioRecord].
 *
 * Configuration:
 *   • PCM 16-bit
 *   • 16 kHz sample rate
 *   • Mono channel
 *   • ~20 ms buffer (640 bytes)
 *
 * Audio data is delivered via [AudioDataCallback] on a dedicated
 * high-priority thread.
 */
class AudioRecorderManager {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        /** ~20 ms of audio at 16 kHz mono PCM16 = 640 bytes. */
        const val BUFFER_SIZE_BYTES = 640
    }

    /** Callback delivering raw PCM buffers. */
    interface AudioDataCallback {
        fun onAudioData(data: ByteArray, length: Int)
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private var callback: AudioDataCallback? = null

    // ── Initialization ───────────────────────────────────────────────────

    /**
     * Create and initialise the [AudioRecord] instance.
     * @return `true` if the recorder is ready to use.
     */
    fun initialize(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuffer, BUFFER_SIZE_BYTES * 2)

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                false
            } else {
                Log.d(TAG, "AudioRecord ready (buffer=$bufferSize B)")
                true
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission missing", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Init error", e)
            false
        }
    }

    // ── Recording control ────────────────────────────────────────────────

    /** Start capturing audio and delivering buffers to [dataCallback]. */
    fun startRecording(dataCallback: AudioDataCallback) {
        if (isRecording.get()) return
        if (audioRecord == null && !initialize()) return

        callback = dataCallback
        isRecording.set(true)
        audioRecord?.startRecording()

        recordingThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            recordLoop()
        }, "AudioRecordThread").also { it.start() }

        Log.d(TAG, "Recording started")
    }

    /** Stop capturing. Safe to call even if not recording. */
    fun stopRecording() {
        isRecording.set(false)
        try { audioRecord?.stop() } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        recordingThread?.join(500)
        recordingThread = null
        callback = null
        Log.d(TAG, "Recording stopped")
    }

    /** Release all native resources. */
    fun release() {
        stopRecording()
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "AudioRecord released")
    }

    fun isRecording(): Boolean = isRecording.get()

    // ── Internal ─────────────────────────────────────────────────────────

    private fun recordLoop() {
        val buf = ByteArray(BUFFER_SIZE_BYTES)
        while (isRecording.get()) {
            val read = audioRecord?.read(buf, 0, BUFFER_SIZE_BYTES) ?: -1
            if (read > 0) {
                callback?.onAudioData(buf.copyOf(read), read)
            } else if (read < 0) {
                Log.e(TAG, "Read error: $read")
                break
            }
        }
    }
}
