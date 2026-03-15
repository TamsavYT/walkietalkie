package com.walkietalkie.networking

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends PCM audio data as UDP packets to one or more target devices.
 * Supports broadcasting to the entire LAN segment.
 */
class UdpAudioSender {

    companion object {
        private const val TAG = "UdpAudioSender"
        private const val BROADCAST_ADDR = "255.255.255.255"
    }

    private var socket: DatagramSocket? = null
    private val targetAddresses = mutableSetOf<Pair<InetAddress, Int>>()
    private val isActive = AtomicBoolean(false)

    /**
     * Start the sender. Opens a UDP socket with broadcast enabled.
     */
    fun start() {
        if (isActive.get()) return
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                // Small send buffer for minimal kernel-side latency
                sendBufferSize = 1280
            }
            isActive.set(true)
            Log.d(TAG, "Sender started (broadcast enabled)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sender", e)
            isActive.set(false)
        }
    }

    /**
     * Add a target destination for audio packets.
     * @param hostAddress IP address
     * @param port        UDP port
     */
    fun addTarget(hostAddress: String, port: Int) {
        try {
            val addr = InetAddress.getByName(hostAddress)
            synchronized(targetAddresses) {
                targetAddresses.add(addr to port)
            }
            Log.d(TAG, "Target added: $hostAddress:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add target: $hostAddress", e)
        }
    }

    /**
     * Remove a target destination.
     */
    fun removeTarget(hostAddress: String) {
        synchronized(targetAddresses) {
            targetAddresses.removeAll { it.first.hostAddress == hostAddress }
        }
        Log.d(TAG, "Target removed: $hostAddress")
    }

    /**
     * Clear all specific targets and set the broadcast address as the only target.
     */
    fun setBroadcastMode(port: Int) {
        clearTargets()
        addTarget(BROADCAST_ADDR, port)
    }

    /** Remove all targets. */
    fun clearTargets() {
        synchronized(targetAddresses) {
            targetAddresses.clear()
        }
    }

    /** Send audio data to all added targets. */
    fun sendAudioData(data: ByteArray, length: Int) {
        if (!isActive.get()) return
        val sock = socket ?: return

        val targets = synchronized(targetAddresses) { targetAddresses.toList() }
        for ((addr, port) in targets) {
            try {
                val packet = DatagramPacket(data, length, addr, port)
                sock.send(packet)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to $addr:$port", e)
            }
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
        clearTargets()
        Log.d(TAG, "Sender stopped")
    }

    fun isActive(): Boolean = isActive.get()
}

