package com.walkietalkie.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.walkietalkie.R
import com.walkietalkie.audio.AudioPlayerManager
import com.walkietalkie.audio.AudioRecorderManager
import com.walkietalkie.discovery.NsdDiscoveryManager
import com.walkietalkie.networking.DeviceInfo
import com.walkietalkie.networking.UdpAudioReceiver
import com.walkietalkie.networking.UdpAudioSender

/**
 * Foreground service that orchestrates the entire walkie-talkie lifecycle:
 *
 *   • NSD discovery (register + scan)
 *   • Audio capture  (AudioRecord → PCM buffers)
 *   • UDP streaming  (PCM buffers → UDP packets)
 *   • Audio playback (UDP packets → AudioTrack)
 *
 * The service exposes a [LocalBinder] so the Activity and Widget
 * can control PTT state, connect/disconnect devices, and observe
 * discovered-device changes.
 */
class WalkieTalkieService : Service() {

    // ── Constants ────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "WalkieTalkieService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "walkie_talkie_channel"

        /** UDP port used for both sending and receiving audio. */
        const val SERVICE_PORT = 50222

        const val ACTION_START_SERVICE   = "com.walkietalkie.START_SERVICE"
        const val ACTION_STOP_SERVICE    = "com.walkietalkie.STOP_SERVICE"
        const val ACTION_START_TRANSMIT  = "com.walkietalkie.START_TRANSMIT"
        const val ACTION_STOP_TRANSMIT   = "com.walkietalkie.STOP_TRANSMIT"
    }

    // ── Components ───────────────────────────────────────────────────────
    private lateinit var audioRecorder: AudioRecorderManager
    private lateinit var audioPlayer: AudioPlayerManager
    private lateinit var udpSender: UdpAudioSender
    private lateinit var udpReceiver: UdpAudioReceiver
    private lateinit var discoveryManager: NsdDiscoveryManager

    // ── State ────────────────────────────────────────────────────────────
    private var isTransmitting = false
    private var connectedDevice: DeviceInfo? = null
    private val discoveredDevices = mutableListOf<DeviceInfo>()

    // ── UI callbacks (set by Activity when bound) ────────────────────────
    var onDeviceListChanged: (() -> Unit)? = null
    var onTransmissionStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // ── Binder ───────────────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): WalkieTalkieService = this@WalkieTalkieService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ═════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        initializeComponents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE   -> {
                startForegroundWithNotification()
                startDiscovery()
            }
            ACTION_STOP_SERVICE    -> stopSelf()
            ACTION_START_TRANSMIT  -> startTransmitting()
            ACTION_STOP_TRANSMIT   -> stopTransmitting()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTransmitting()
        udpSender.stop()
        udpReceiver.stop()
        audioRecorder.release()
        audioPlayer.release()
        discoveryManager.cleanup()
        Log.d(TAG, "Service destroyed")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Initialisation helpers
    // ═════════════════════════════════════════════════════════════════════

    private fun initializeComponents() {
        audioRecorder = AudioRecorderManager()
        audioPlayer   = AudioPlayerManager()
        udpSender     = UdpAudioSender()
        udpReceiver   = UdpAudioReceiver(SERVICE_PORT, audioPlayer)

        discoveryManager = NsdDiscoveryManager(
            context     = this,
            servicePort = SERVICE_PORT,
            listener    = discoveryCallback
        )

        // Pre-initialise audio so first PTT press is instant
        audioRecorder.initialize()
        audioPlayer.initialize()

        // Start the receiver and sender immediately.
        // By default, we are in "Channel Mode" (broacast to everyone).
        udpReceiver.start()
        udpSender.start()
        udpSender.setBroadcastMode(SERVICE_PORT)
    }

    /** NSD discovery callback wired into [discoveryManager]. */
    private val discoveryCallback = object : NsdDiscoveryManager.DiscoveryListener {
        override fun onDeviceFound(device: DeviceInfo) {
            synchronized(discoveredDevices) {
                discoveredDevices.removeAll { it.id == device.id }
                val isCurrentlyConnected = connectedDevice?.id == device.id
                discoveredDevices.add(device.copy(isConnected = isCurrentlyConnected))
            }
            onDeviceListChanged?.invoke()
            Log.d(TAG, "Device found: ${device.name} (${device.hostAddress})")
        }

        override fun onDeviceLost(device: DeviceInfo) {
            synchronized(discoveredDevices) {
                discoveredDevices.removeAll { it.hostAddress == device.hostAddress }
            }
            if (connectedDevice?.hostAddress == device.hostAddress) {
                connectedDevice = null
                // Return to broadcast mode if our specific peer is lost
                udpSender.setBroadcastMode(SERVICE_PORT)
            }
            onDeviceListChanged?.invoke()
            Log.d(TAG, "Device lost: ${device.name}")
        }

        override fun onError(message: String) {
            onError?.invoke(message)
            Log.e(TAG, "Discovery error: $message")
        }
    }

    private fun startDiscovery() {
        discoveryManager.registerService()
        discoveryManager.startDiscovery()
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Notification
    // ═════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Walkie Talkie", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active walkie-talkie service"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("Walkie Talkie is ready")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LAN Walkie Talkie")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Public API (called from Activity / Widget)
    // ═════════════════════════════════════════════════════════════════════

    /** Connect to a discovered device for private two-way audio. */
    fun connectToDevice(device: DeviceInfo) {
        val connected = device.copy(isConnected = true)
        connectedDevice = connected
        synchronized(discoveredDevices) {
            val updated = discoveredDevices.map { 
                it.copy(isConnected = it.id == device.id)
            }
            discoveredDevices.clear()
            discoveredDevices.addAll(updated)
        }
        
        // Switch from Broadcast to specific Unicast target
        udpSender.clearTargets()
        udpSender.addTarget(connected.hostAddress, connected.port)
        
        updateNotification("Private: ${connected.name}")
        onDeviceListChanged?.invoke()
        Log.d(TAG, "Focused on ${connected.name}")
    }

    /** disconnect from a private peer and return to channel broadcast. */
    fun disconnectFromDevice() {
        synchronized(discoveredDevices) {
            val updated = discoveredDevices.map { it.copy(isConnected = false) }
            discoveredDevices.clear()
            discoveredDevices.addAll(updated)
        }
        
        // Return to broadcast mode
        udpSender.setBroadcastMode(SERVICE_PORT)
        
        connectedDevice = null
        stopTransmitting()
        updateNotification("Walkie Talkie: Channel")
        onDeviceListChanged?.invoke()
        Log.d(TAG, "Returned to Channel Broadcast")
    }

    /** Begin transmitting audio (PTT press). */
    fun startTransmitting() {
        if (isTransmitting) return

        // Ensure sender socket is open (should be already)
        if (!udpSender.isActive()) {
            udpSender.start()
            if (connectedDevice == null) {
                udpSender.setBroadcastMode(SERVICE_PORT)
            }
        }

        isTransmitting = true
        audioPlayer.isMuted = true
        audioRecorder.startRecording(object : AudioRecorderManager.AudioDataCallback {
            override fun onAudioData(data: ByteArray, length: Int) {
                udpSender.sendAudioData(data, length)
            }
        })
        updateNotification("Transmitting…")
        onTransmissionStateChanged?.invoke(true)
        Log.d(TAG, "TX started")
    }

    /** Stop transmitting audio (PTT release). */
    fun stopTransmitting() {
        if (!isTransmitting) return
        isTransmitting = false
        audioPlayer.isMuted = false
        audioRecorder.stopRecording()
        updateNotification(
            connectedDevice?.let { "Private: ${it.name}" } ?: "Walkie Talkie: Channel"
        )
        onTransmissionStateChanged?.invoke(false)
        Log.d(TAG, "TX stopped")
    }

    /** Snapshot of discovered devices. */
    fun getDevices(): List<DeviceInfo> = synchronized(discoveredDevices) {
        discoveredDevices.toList()
    }

    /** Currently connected peer (null if none). */
    fun getConnectedDevice(): DeviceInfo? = connectedDevice

    /** Whether we are currently sending audio. */
    fun isTransmitting(): Boolean = isTransmitting
}
