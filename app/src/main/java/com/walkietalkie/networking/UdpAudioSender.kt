package com.walkietalkie.networking

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends PCM audio data as UDP packets to a target device.
 *
 * Each packet carries one audio buffer (~640 bytes = 20 ms of 16 kHz mono PCM16).
 * Designed for low-latency LAN streaming — no retransmission, no sequencing.
 */
class UdpAudioSender {

    companion object {
        private const val TAG = "UdpAudioSender"
    }

    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 0
    private val isActive = AtomicBoolean(false)

    /**
     * Open a UDP socket and set the target destination.
     *
     * @param hostAddress IP address of the receiving device
     * @param port        UDP port the receiver is listening on
     */
    fun start(hostAddress: String, port: Int) {
        try {
            targetAddress = InetAddress.getByName(hostAddress)
            targetPort = port
            socket = DatagramSocket().apply {
                // Small send buffer for minimal kernel-side latency
                sendBufferSize = 1280
            }
            isActive.set(true)
            Log.d(TAG, "Sender started → $hostAddress:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sender", e)
            isActive.set(false)
        }
    }

    /**
     * Send a PCM audio buffer as a UDP datagram.
     * Called from the audio-recording thread; must be non-blocking.
     *
     * @param data   PCM byte array
     * @param length number of valid bytes in the array
     */
    fun sendAudioData(data: ByteArray, length: Int) {
        if (!isActive.get()) return
        try {
            val addr = targetAddress ?: return
            val packet = DatagramPacket(data, length, addr, targetPort)
            socket?.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio", e)
        }
    }

    /** Close the socket and release resources. */
    fun stop() {
        isActive.set(false)
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }
        socket = null
        targetAddress = null
        Log.d(TAG, "Sender stopped")
    }

    fun isActive(): Boolean = isActive.get()
}
