package com.walkietalkie.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.walkietalkie.R
import com.walkietalkie.databinding.ActivityMainBinding
import com.walkietalkie.service.WalkieTalkieService

/**
 * Main UI — shows discovered devices, connection status,
 * and a press-and-hold Push-to-Talk button.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: WalkieTalkieService? = null
    private var isBound = false
    private lateinit var deviceAdapter: DeviceAdapter

    // ── Service connection ───────────────────────────────────────────────
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as WalkieTalkieService.LocalBinder).getService()
            isBound = true
            setupServiceCallbacks()
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
        }
    }

    // ── Permission handling ──────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startAndBindService()
        } else {
            Toast.makeText(
                this, "Microphone permission is required", Toast.LENGTH_LONG
            ).show()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
        requestPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            service?.onDeviceListChanged = null
            service?.onTransmissionStateChanged = null
            service?.onError = null
            unbindService(serviceConnection)
            isBound = false
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  UI setup
    // ═════════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        // ── Device list ──────────────────────────────────────────────────
        deviceAdapter = DeviceAdapter { device ->
            service?.let { svc ->
                if (device.isConnected) svc.disconnectFromDevice()
                else svc.connectToDevice(device)
            }
        }
        binding.recyclerDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }

        // ── Push-to-Talk (press & hold) ──────────────────────────────────
        binding.btnPushToTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    service?.startTransmitting()
                    binding.btnPushToTalk.text = getString(R.string.transmitting)
                    binding.btnPushToTalk.isSelected = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    service?.stopTransmitting()
                    binding.btnPushToTalk.text = getString(R.string.push_to_talk)
                    binding.btnPushToTalk.isSelected = false
                    true
                }
                else -> false
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Service ↔ UI wiring
    // ═════════════════════════════════════════════════════════════════════

    private fun setupServiceCallbacks() {
        service?.onDeviceListChanged = {
            runOnUiThread { updateDeviceList() }
        }
        service?.onTransmissionStateChanged = { tx ->
            runOnUiThread {
                binding.btnPushToTalk.isSelected = tx
                binding.btnPushToTalk.text = getString(
                    if (tx) R.string.transmitting else R.string.push_to_talk
                )
                updateStatusText()
            }
        }
        service?.onError = { msg ->
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  UI updates
    // ═════════════════════════════════════════════════════════════════════

    private fun updateUI() {
        updateDeviceList()
        updateStatusText()
    }

    private fun updateDeviceList() {
        val devices = service?.getDevices() ?: emptyList()
        deviceAdapter.submitList(devices.toList())
        binding.txtStatus.text = if (devices.isEmpty()) {
            getString(R.string.searching_devices)
        } else {
            getString(R.string.devices_found, devices.size)
        }
    }

    private fun updateStatusText() {
        val conn = service?.getConnectedDevice()
        binding.txtConnectionStatus.text = if (conn != null) {
            getString(R.string.connected_to, conn.name)
        } else {
            getString(R.string.not_connected)
        }
        binding.btnPushToTalk.isEnabled = conn != null
        binding.btnPushToTalk.alpha = if (conn != null) 1.0f else 0.5f
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Permissions & service start
    // ═════════════════════════════════════════════════════════════════════

    private fun requestPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startAndBindService()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startAndBindService() {
        val intent = Intent(this, WalkieTalkieService::class.java).apply {
            action = WalkieTalkieService.ACTION_START_SERVICE
        }
        ContextCompat.startForegroundService(this, intent)
        bindService(
            Intent(this, WalkieTalkieService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }
}
