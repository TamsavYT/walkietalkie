package com.walkietalkie.networking

import android.util.Log
import com.walkietalkie.audio.AudioPlayerManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens for incoming UDP audio packets and feeds them through a small
 * jitter buffer into [AudioPlayerManager] for playback.
 *
 * Jitter buffer smooths out network timing variations while keeping
 * end-to-end latency below ~60 ms (3 packets × 20 ms each).
 *
 * @property port        UDP port to listen on
 * @property audioPlayer AudioTrack wrapper for playback
 */
class UdpAudioReceiver(
    private val port: Int,
    private val audioPlayer: AudioPlayerManager
) {

    companion object {
        private const val TAG = "UdpAudioReceiver"
        /** Max bytes per UDP packet (~20 ms of 16 kHz mono PCM16 = 640 B, doubled for safety). */
        private const val PACKET_BUFFER_SIZE = 1280
        /** Number of packets to buffer before playback begins. Smooths out WiFi jitter. */
        private const val JITTER_BUFFER_SIZE = 8
    }

    // ── State ────────────────────────────────────────────────────────────
    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var playbackThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    // ── Simple ring-buffer jitter buffer ─────────────────────────────────
    private val jitterBuffer = Array(JITTER_BUFFER_SIZE) { ByteArray(PACKET_BUFFER_SIZE) }
    private val jitterLengths = IntArray(JITTER_BUFFER_SIZE)
    private var writeIndex = 0
    private var readIndex = 0
    private var bufferedCount = 0
    private val bufferLock = Object()

    // ── Public API ───────────────────────────────────────────────────────

    /** Start listening for incoming audio packets. */
    fun start() {
        if (isRunning.get()) return
        try {
            socket = DatagramSocket(port).apply {
                reuseAddress = true
                // Large receive buffer to prevent OS-level drops during spikes
                receiveBufferSize = 64 * 1024 
                soTimeout = 1000  // 1 s timeout so we can check isRunning periodically
            }
            isRunning.set(true)

            // Playback consumer thread
            playbackThread = Thread(::playbackLoop, "AudioPlayback").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            // Network receive thread
            receiveThread = Thread(::receiveLoop, "UdpReceiver").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.d(TAG, "Receiver started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start receiver", e)
            isRunning.set(false)
        }
    }

    /** Stop listening, drain buffers, release resources. */
    fun stop() {
        isRunning.set(false)
        try { socket?.close() } catch (_: Exception) {}
        receiveThread?.interrupt()
        synchronized(bufferLock) { bufferLock.notifyAll() }  // wake playback thread
        receiveThread = null
        playbackThread = null
        socket = null
        resetBuffer()
        Log.d(TAG, "Receiver stopped")
    }

    fun isRunning(): Boolean = isRunning.get()

    // ── Internal loops ───────────────────────────────────────────────────

    /** Receive UDP packets and push them into the jitter buffer. */
    private fun receiveLoop() {
        val buf = ByteArray(PACKET_BUFFER_SIZE)
        val packet = DatagramPacket(buf, buf.size)

        while (isRunning.get()) {
            try {
                packet.length = buf.size // Ensure length is reset for each receive
                socket?.receive(packet)
                val len = packet.length
                if (len <= 0) continue

                synchronized(bufferLock) {
                    System.arraycopy(buf, 0, jitterBuffer[writeIndex], 0, len)
                    jitterLengths[writeIndex] = len
                    writeIndex = (writeIndex + 1) % JITTER_BUFFER_SIZE

                    if (bufferedCount < JITTER_BUFFER_SIZE) {
                        bufferedCount++
                    } else {
                        // Overwrite oldest → advance read pointer
                        readIndex = (readIndex + 1) % JITTER_BUFFER_SIZE
                    }
                    bufferLock.notify()
                }
            } catch (_: SocketTimeoutException) {
                // Expected — lets us re-check isRunning
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "Receive error", e)
            }
        }
    }

    /** Consume from the jitter buffer and write to AudioTrack. */
    private fun playbackLoop() {
        audioPlayer.start()

        while (isRunning.get()) {
            var data: ByteArray? = null
            var length = 0

            synchronized(bufferLock) {
                // PRIMING: If we run dry, wait for multiple packets to accumulate
                // to smooth out network jitter before resuming playback.
                val primingThreshold = JITTER_BUFFER_SIZE / 2
                while (bufferedCount < primingThreshold && isRunning.get()) {
                    try { bufferLock.wait(200) } catch (_: InterruptedException) { return }
                }
                
                if (bufferedCount > 0) {
                    data = jitterBuffer[readIndex]
                    length = jitterLengths[readIndex]
                    readIndex = (readIndex + 1) % JITTER_BUFFER_SIZE
                    bufferedCount--
                }
            }

            data?.let { audioPlayer.write(it, length) }
        }

        audioPlayer.stop()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun resetBuffer() {
        synchronized(bufferLock) {
            writeIndex = 0
            readIndex = 0
            bufferedCount = 0
        }
    }
}
