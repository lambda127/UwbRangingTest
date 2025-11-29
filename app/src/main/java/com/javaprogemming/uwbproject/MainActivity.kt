package com.javaprogemming.uwbproject

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.javaprogemming.uwbproject.ui.theme.UwbProjectTheme
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.random.Random



class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager
    private lateinit var uwbManager: UwbManager
    private var discoveredDevices = mutableListOf<BluetoothDevice>()
    private var isController = true
    private var isWorking = false

    // UI Components
    private lateinit var startText: TextView
    private lateinit var endText: TextView
    private lateinit var followText: TextView
    private lateinit var loadingText: TextView
    private lateinit var overlayIntro: FrameLayout
    private lateinit var redDot: ImageView
    private lateinit var bottomPanel: RelativeLayout
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI
        startText = findViewById(R.id.startText)
        endText = findViewById(R.id.endText)
        followText = findViewById(R.id.followText)
        loadingText = findViewById(R.id.loadingText)
        overlayIntro = findViewById(R.id.overlayIntro)
        redDot = findViewById(R.id.redDot)
        bottomPanel = findViewById(R.id.bottomPanel)

        // Intro Animation
        if (followText != null) {
            followText.alpha = 1f
            followText.animate().scaleX(2f).scaleY(2f).alpha(0f).setDuration(4000).start()
        }

        if (loadingText != null) {
            handler.postDelayed({
                loadingText.alpha = 1f
                val blink = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
                loadingText.startAnimation(blink)
            }, 500)
        }

        handler.postDelayed({
            if (overlayIntro != null) overlayIntro.visibility = View.GONE
            if (redDot != null) redDot.visibility = View.VISIBLE
            if (bottomPanel != null) bottomPanel.visibility = View.VISIBLE
        }, 4000)

        startText.setOnClickListener { startAction() }
        endText.setOnClickListener { resetAction() }

        checkPermissions()

        uwbManager = UwbManager(this) { result ->
            val distance = result.position.distance?.value
            val azimuth = result.position.azimuth?.value
            val elevation = result.position.elevation?.value
            
            val sb = StringBuilder()
            if (distance != null) {
                sb.append("Dist: %.2f m".format(distance))
            }
            if (azimuth != null) {
                sb.append(", Az: %.0f°".format(Math.toDegrees(azimuth.toDouble())))
            }
            if (elevation != null) {
                sb.append(", El: %.0f°".format(Math.toDegrees(elevation.toDouble())))
            }
            
            Log.d("MainActivity", "Ranging result: $sb")
            // Update UI with ranging result if needed (e.g., Toast or TextView)
        }

        bleManager = BleManager(this,
            onDeviceFound = { device ->
                if (!discoveredDevices.any { it.address == device.address }) {
                    discoveredDevices.add(device)
                    Log.d("MainActivity", "Device found: ${device.address}")
                    // Auto-connect logic could go here if we want to automate it
                    // For now, we stick to the plan: Start button -> Scan -> (Implicitly connect?)
                    // The original plan was "Start -> Controller", "Stop -> Stop".
                    // We need to decide when to connect.
                    // Let's auto-connect to the first found device for now, to match the "Start" simplicity.
                    connectToDevice(device)
                }
            },
            onDataReceived = { address, data ->
                handleDataReceived(address, data)
            }
        )
    }

    private fun startAction() {
        if (!isWorking) {
            isWorking = true
            Toast.makeText(this, "Starting Controller...", Toast.LENGTH_SHORT).show()
            startWorking()
        }
    }

    private fun resetAction() {
        if (isWorking) {
            stopWorking()
            Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startWorking() {
        discoveredDevices.clear()
        
        if (isController) {
            Log.d("MainActivity", "Starting Controller: Scanning...")
            bleManager.startScanning()
        } else {
            // If we ever want to support Controlee via this UI, we'd need a switch.
            // For now, defaulting to Controller as per plan.
        }
    }

    private fun stopWorking() {
        isWorking = false
        Log.d("MainActivity", "Stopping...")
        bleManager.stopScanning()
        bleManager.stopAdvertising()
        uwbManager.stopRanging()
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Log.d("MainActivity", "Connecting to ${device.address}")
        bleManager.connectToDevice(device)
        
        // Wait for connection then Read
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000) // Wait for connection
            Log.d("MainActivity", "Reading UWB Address from ${device.address}")
            bleManager.readUwbAddress(device.address)
        }
    }

    private fun handleDataReceived(address: String, data: ByteArray) {
        try {
            Log.d("MainActivity", "Data received from $address: ${data.size} bytes")
            if (data.size == 2 || data.size == 8) { 
                // Peer Address (Read by Controller)
                val peerAddressBytes = data
                val sessionId = kotlin.math.abs(Random.nextInt())
                val sessionKey = Random.nextBytes(8) 
                
                Log.d("MainActivity", "Read Peer Address: ${peerAddressBytes.contentToString()}. Starting Controller...")
                
                uwbManager.startRangingAsController(peerAddressBytes, sessionId, sessionKey) { localAddress ->
                    lifecycleScope.launch {
                        val myAddrBytes = localAddress.address
                        val buffer = ByteBuffer.allocate(4 + 8 + 1 + myAddrBytes.size)
                        buffer.putInt(sessionId)
                        buffer.put(sessionKey)
                        buffer.put(myAddrBytes.size.toByte())
                        buffer.put(myAddrBytes)
                        Log.d("MainActivity", "Sending Params to $address")
                        bleManager.writeData(address, buffer.array())
                    }
                }
            } else {
                // Params (Received by Controlee) - Not expected in this Controller-only flow but kept for completeness
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error parsing data", e)
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.UWB_RANGING
        )
        
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        if (!permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 0)
        }
    }
}