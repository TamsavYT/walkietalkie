package com.walkietalkie.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.walkietalkie.R
import com.walkietalkie.databinding.ItemDeviceBinding
import com.walkietalkie.networking.DeviceInfo

/**
 * RecyclerView adapter for the discovered-device list.
 *
 * Each row shows:
 *   • coloured status dot (green = connected, grey = idle)
 *   • device name and IP:port
 *   • Connect / Disconnect button
 */
class DeviceAdapter(
    private val onConnectClick: (DeviceInfo) -> Unit
) : ListAdapter<DeviceInfo, DeviceAdapter.DeviceViewHolder>(DiffCallback) {

    // ── ViewHolder ───────────────────────────────────────────────────────
    inner class DeviceViewHolder(
        private val binding: ItemDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: DeviceInfo) {
            binding.txtDeviceName.text = device.name
            binding.txtDeviceAddress.text = "${device.hostAddress}:${device.port}"

            // Status dot
            binding.viewStatusIndicator.setBackgroundResource(
                if (device.isConnected) R.drawable.status_connected
                else R.drawable.status_disconnected
            )

            // Button label
            binding.btnConnect.text = itemView.context.getString(
                if (device.isConnected) R.string.disconnect else R.string.connect
            )

            binding.btnConnect.setOnClickListener { onConnectClick(device) }
        }
    }

    // ── Adapter overrides ────────────────────────────────────────────────
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    // ── DiffUtil ─────────────────────────────────────────────────────────
    companion object DiffCallback : DiffUtil.ItemCallback<DeviceInfo>() {
        override fun areItemsTheSame(a: DeviceInfo, b: DeviceInfo) = a.id == b.id
        override fun areContentsTheSame(a: DeviceInfo, b: DeviceInfo) = a == b
    }
}
