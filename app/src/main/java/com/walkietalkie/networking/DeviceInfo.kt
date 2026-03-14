package com.walkietalkie.networking

/**
 * Represents a discovered device on the local network.
 *
 * @property name    Human-readable device name (manufacturer + model)
 * @property hostAddress  IP address of the device
 * @property port    UDP port the device is listening on
 * @property isConnected  Whether we are currently connected to this device
 */
data class DeviceInfo(
    val name: String,
    val hostAddress: String,
    val port: Int,
    var isConnected: Boolean = false
) {
    /** Unique identifier based on network address. */
    val id: String get() = "$hostAddress:$port"
}
