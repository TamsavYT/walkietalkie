package com.walkietalkie.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.walkietalkie.networking.DeviceInfo

/**
 * Manages Network Service Discovery (NSD / mDNS) for finding other
 * WalkieTalkie devices on the same local WiFi network.
 *
 * Lifecycle:
 *   1. [registerService] – advertise our own service so others can find us
 *   2. [startDiscovery] – scan for other devices
 *   3. [cleanup]        – tear everything down
 */
class NsdDiscoveryManager(
    private val context: Context,
    private val servicePort: Int,
    private val listener: DiscoveryListener
) {

    // ── Constants ────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "NsdDiscovery"
        private const val SERVICE_TYPE = "_walkietalkie._udp."
        private const val SERVICE_NAME_PREFIX = "WalkieTalkie"
    }

    // ── Callback interface ───────────────────────────────────────────────
    interface DiscoveryListener {
        fun onDeviceFound(device: DeviceInfo)
        fun onDeviceLost(device: DeviceInfo)
        fun onError(message: String)
    }

    // ── Internal state ───────────────────────────────────────────────────
    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registeredServiceName: String? = null

    private val discoveredDevices = mutableMapOf<String, DeviceInfo>()
    private var isDiscovering = false
    private var isRegistered = false

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Build a user-friendly device name from manufacturer + model. */
    private fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model
        else "$manufacturer $model"
    }

    // ── Service Registration ─────────────────────────────────────────────

    /** Register this device's walkie-talkie service on the network. */
    fun registerService() {
        if (isRegistered) return

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${SERVICE_NAME_PREFIX}_${getDeviceName()}"
            serviceType = SERVICE_TYPE
            port = servicePort
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                isRegistered = true
                Log.d(TAG, "Service registered: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
                Log.e(TAG, "Registration failed: $errorCode")
                listener.onError("Failed to register service (error $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                isRegistered = false
                Log.d(TAG, "Service unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
                listener.onError("Failed to unregister service (error $errorCode)")
            }
        }

        try {
            nsdManager.registerService(
                serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error registering service", e)
            listener.onError("Error registering service: ${e.message}")
        }
    }

    // ── Discovery ────────────────────────────────────────────────────────

    /** Start scanning for other walkie-talkie services on the LAN. */
    fun startDiscovery() {
        if (isDiscovering) return

        discoveryListener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(serviceType: String) {
                isDiscovering = true
                Log.d(TAG, "Discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")

                // Skip our own service
                if (serviceInfo.serviceName == registeredServiceName) {
                    Log.d(TAG, "Ignoring own service")
                    return
                }
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                discoveredDevices.remove(serviceInfo.serviceName)?.let {
                    listener.onDeviceLost(it)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
                Log.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                Log.e(TAG, "Start discovery failed: $errorCode")
                listener.onError("Discovery failed (error $errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(
                SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting discovery", e)
            listener.onError("Error starting discovery: ${e.message}")
        }
    }

    /** Resolve a discovered service to obtain its IP address and port. */
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host?.hostAddress ?: return
                val port = resolved.port
                val name = resolved.serviceName
                    .removePrefix("${SERVICE_NAME_PREFIX}_")
                    .ifEmpty { resolved.serviceName }

                Log.d(TAG, "Resolved: $name at $host:$port")

                val device = DeviceInfo(
                    name = name,
                    hostAddress = host,
                    port = port
                )
                discoveredDevices[resolved.serviceName] = device
                listener.onDeviceFound(device)
            }
        })
    }

    // ── Teardown ─────────────────────────────────────────────────────────

    /** Stop scanning. */
    fun stopDiscovery() {
        if (!isDiscovering) return
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        }
        isDiscovering = false
    }

    /** Unregister our advertised service. */
    fun unregisterService() {
        if (!isRegistered) return
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering service", e)
        }
        isRegistered = false
    }

    /** Clean up everything. */
    fun cleanup() {
        stopDiscovery()
        unregisterService()
        discoveredDevices.clear()
    }

    /** Snapshot of currently discovered devices. */
    fun getDiscoveredDevices(): List<DeviceInfo> = discoveredDevices.values.toList()
}
