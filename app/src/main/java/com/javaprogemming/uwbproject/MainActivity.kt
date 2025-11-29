package com.javaprogemming.uwbproject

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
    private lateinit var overlayIntro: RelativeLayout
    private lateinit var redDot: View
    private lateinit var targetDot: ImageView
    private lateinit var bottomPanel: View
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
        targetDot = findViewById(R.id.targetDot)
        bottomPanel = findViewById(R.id.bottomPanel)

        // Intro Animation
        if (followText != null) {
            followText.alpha = 1f
            followText.animate().scaleX(2f).scaleY(2f).alpha(0f).setDuration(4000).start()
        }
        if (loadingText != null) {
            loadingText.alpha = 1f
            loadingText.animate().alpha(0f).setDuration(4000).start()
        }
        
        handler.postDelayed({
            if (overlayIntro != null) {
                overlayIntro.animate().alpha(0f).setDuration(1000).withEndAction {
                    overlayIntro.visibility = View.GONE
                    redDot.visibility = View.VISIBLE
                    bottomPanel.visibility = View.VISIBLE
                }.start()
            }
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
            
            val status = sb.toString()
            Log.d("MainActivity", "Ranging result: $status")
            runOnUiThread {
                loadingText.text = status
                loadingText.alpha = 1f
                loadingText.visibility = View.VISIBLE
                
                if (distance != null && azimuth != null) {
                    updateTargetDotPosition(distance, azimuth)
                }
            }
        }

        bleManager = BleManager(this,
            onDeviceFound = { device ->
                if (isWorking && isController) { // Only if we haven't decided role or are Controller
                    if (!discoveredDevices.any { it.address == device.address }) {
                        discoveredDevices.add(device)
                        Log.d("MainActivity", "Device found: ${device.address}. Becoming Controller.")
                        
                        // We found a device first! We are Controller.
                        // Stop advertising so we don't confuse others (optional, but good practice)
                        bleManager.stopAdvertising()
                        
                        connectToDevice(device)
                    }
                }
            },
            onDataReceived = { address, data ->
                handleDataReceived(address, data)
            }
        )
    }

    private fun startAction() {
        Toast.makeText(this, "Start Button Clicked", Toast.LENGTH_SHORT).show()
        startText.setBackgroundResource(R.drawable.btn_green) // Visual feedback if needed
        startWorking()
        startDotAnimations()
    }

    private fun resetAction() {
        Toast.makeText(this, "Stop Button Clicked", Toast.LENGTH_SHORT).show()
        stopWorking()
        stopDotAnimations()
        // Reset UI state if needed
        targetDot.visibility = View.INVISIBLE
    }

    private fun startWorking() {
        isWorking = true
        discoveredDevices.clear()
        
        // Dynamic Role Switching:
        // 1. Start Advertising (to be found -> become Controlee)
        // 2. Start Scanning (to find others -> become Controller)
        
        // Default state: We are open to being either.
        // Let's assume isController = true initially to allow scanning logic, 
        // but we will flip it if we receive data first.
        isController = true 

        Log.d("MainActivity", "Starting Dynamic Role Search...")

        // Start Advertising (Controlee side)
        lifecycleScope.launch {
            try {
                val localAddress = uwbManager.prepareControleeSession()
                bleManager.setLocalUwbAddress(localAddress.address)
                bleManager.startAdvertising()
                Log.d("MainActivity", "Advertising started (Candidate Controlee)")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start advertising", e)
            }
        }

        // Start Scanning (Controller side)
        bleManager.startScanning()
        Log.d("MainActivity", "Scanning started (Candidate Controller)")
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
                // Peer Address (Controller logic)
                val peerAddressBytes = data
                val sessionId = kotlin.math.abs(Random.nextInt())
                val sessionKey = Random.nextBytes(8)
                
                Log.d("MainActivity", "Read Peer Address. Starting Controller...")
                
                uwbManager.startRangingAsController(peerAddressBytes, sessionId, sessionKey) { localAddress ->
                    lifecycleScope.launch {
                        val myAddrBytes = localAddress.address
                        val buffer = ByteBuffer.allocate(4 + 8 + 1 + myAddrBytes.size)
                        buffer.putInt(sessionId)
                        buffer.put(sessionKey)
                        buffer.put(myAddrBytes.size.toByte())
                        buffer.put(myAddrBytes)
                        bleManager.writeData(address, buffer.array())
                    }
                }
            } else {
                // Params (Controlee logic - if we were controlee)
                val buffer = ByteBuffer.wrap(data)
                val sessionId = buffer.int
                val sessionKey = ByteArray(8)
                buffer.get(sessionKey)
                val addrLen = buffer.get().toInt()
                val addrBytes = ByteArray(addrLen)
                buffer.get(addrBytes)
                
                Log.d("MainActivity", "Received Params. Becoming Controlee...")
                
                // We received data! We are Controlee.
                isController = false
                bleManager.stopScanning() // Stop scanning since we are being controlled
                
                uwbManager.startRangingWithPreparedSession(addrBytes, sessionId, sessionKey)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error parsing data", e)
        }
    }

    private fun startDotAnimations() {
        val dotIds = intArrayOf(
            R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4,
            R.id.dot5, R.id.dot6, R.id.dot7, R.id.dot8
        )
        val animRes = intArrayOf(
            R.anim.dot_rotate_0, R.anim.dot_rotate_45, R.anim.dot_rotate_90, R.anim.dot_rotate_135,
            R.anim.dot_rotate_180, R.anim.dot_rotate_225, R.anim.dot_rotate_270, R.anim.dot_rotate_315
        )

        for (i in dotIds.indices) {
            val dot = findViewById<View>(dotIds[i])
            if (dot != null) {
                val a = AnimationUtils.loadAnimation(this, animRes[i])
                dot.startAnimation(a)
            }
        }
    }

    private fun stopDotAnimations() {
        val dotIds = intArrayOf(
            R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4,
            R.id.dot5, R.id.dot6, R.id.dot7, R.id.dot8
        )
        for (id in dotIds) {
            val dot = findViewById<View>(id)
            dot?.clearAnimation()
        }
    }

    private fun updateTargetDotPosition(distance: Float, azimuth: Float) {
        val maxDistance = 5.0f // Max distance in meters to map to screen edge
        val screenCenterX = redDot.x + redDot.width / 2
        val screenCenterY = redDot.y + redDot.height / 2
        val maxScreenRadius = 300f // Approximate radius in pixels

        // Calculate position offset
        // Azimuth is in radians. 0 is forward? Let's assume standard math: 0 is East, but UWB might be different.
        // Usually UWB azimuth 0 is straight ahead (North in screen coordinates if phone is portrait).
        // Let's assume 0 is Up (Negative Y).
        
        // Convert azimuth to screen coordinates
        // x = distance * sin(azimuth)
        // y = distance * cos(azimuth)
        // We need to scale distance.
        
        val scale = Math.min(distance, maxDistance) / maxDistance * maxScreenRadius
        
        val offsetX = scale * kotlin.math.sin(azimuth.toDouble()).toFloat()
        val offsetY = -scale * kotlin.math.cos(azimuth.toDouble()).toFloat() // Negative Y is Up

        targetDot.x = screenCenterX + offsetX - targetDot.width / 2
        targetDot.y = screenCenterY + offsetY - targetDot.height / 2
        targetDot.visibility = View.VISIBLE
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

        val launcher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { perms ->
            val allGranted = perms.values.all { it }
            if (!allGranted) {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
            }
        }

        if (!permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            launcher.launch(permissions.toTypedArray())
        }
    }
}