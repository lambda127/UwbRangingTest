package com.javaprogemming.uwbproject

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    private var discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private var rangingDistance by mutableStateOf<Float?>(null)
    private var statusMessage by mutableStateOf("Idle")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        uwbManager = UwbManager(this) { distance ->
            rangingDistance = distance
            statusMessage = "Ranging: ${"%.2f".format(distance)} m"
        }

        bleManager = BleManager(this,
            onDeviceFound = { device ->
                if (!discoveredDevices.any { it.address == device.address }) {
                    discoveredDevices.add(device)
                }
            },
            onDataReceived = { address, data ->
                try {
                    // Distinguish between Read Result (Peer Address) and Write Result (Params)
                    if (data.size == 2 || data.size == 8) {
                        // This is Peer Address (Read by Controller)
                        val peerAddressBytes = data
                        val sessionId = Random.nextInt()
                        
                        statusMessage = "Read Peer Address. Starting Controller..."
                        
                        // 1. Start Ranging as Controller
                        uwbManager.startRangingAsController(peerAddressBytes, sessionId)
                        
                        // 2. Send Local Address + SessionID to Peer
                        lifecycleScope.launch {
                            val localAddress = uwbManager.getLocalAddress() // This might need a prepared session too? 
                            // Controller creates session on the fly. 
                            // Wait, Controller needs to send ITS address.
                            // uwbManager.controllerSessionScope().localAddress
                            // But startRangingAsController creates the scope.
                            // I need to get the address FROM the scope created in startRangingAsController.
                            // This is tricky with the current UwbManager design.
                            // Let's modify UwbManager to return the local address from startRangingAsController?
                            // OR, just send the address we prepared for advertising?
                            // NO, Controller uses a different scope usually.
                            // But maybe we can use the SAME address?
                            // If we use the same address, we need to ensure the system allows it.
                            // Actually, for simplicity, let's assume Controller uses the address from `prepareControleeSession`?
                            // No, that's a Controlee scope.
                            
                            // Let's assume for now we send the address we have (from prepareControleeSession).
                            // If the library requires the specific session address, we are in trouble.
                            // But usually, the Controller's address is just for the Peer to know who to talk to.
                            // Let's try sending the address we have.
                            
                            val myAddrBytes = localAddress.address
                            val buffer = ByteBuffer.allocate(4 + 1 + myAddrBytes.size)
                            buffer.putInt(sessionId)
                            buffer.put(myAddrBytes.size.toByte())
                            buffer.put(myAddrBytes)
                            bleManager.writeData(address, buffer.array())
                        }
                    } else {
                        // This is Params (Received by Controlee)
                        val buffer = ByteBuffer.wrap(data)
                        val sessionId = buffer.int
                        val addrLen = buffer.get().toInt()
                        val addrBytes = ByteArray(addrLen)
                        buffer.get(addrBytes)
                        
                        statusMessage = "Received params. Starting Controlee..."
                        uwbManager.startRangingWithPreparedSession(addrBytes, sessionId)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error parsing data", e)
                }
            }
        )

        setContent {
            UwbProjectTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }

    @Composable
    fun MainScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        
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

        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { perms ->
            val allGranted = perms.values.all { it }
            if (allGranted) {
                scope.launch {
                    val addr = uwbManager.prepareControleeSession()
                    bleManager.setLocalUwbAddress(addr.address)
                    bleManager.startScanning()
                    bleManager.startAdvertising()
                    statusMessage = "Scanning & Advertising..."
                }
            } else {
                Toast.makeText(context, "Permissions required", Toast.LENGTH_SHORT).show()
            }
        }

        LaunchedEffect(Unit) {
            if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                scope.launch {
                    val addr = uwbManager.prepareControleeSession()
                    bleManager.setLocalUwbAddress(addr.address)
                    bleManager.startScanning()
                    bleManager.startAdvertising()
                    statusMessage = "Scanning & Advertising..."
                }
            } else {
                launcher.launch(permissions.toTypedArray())
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Status: $statusMessage", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(text = "Discovered Devices:", style = MaterialTheme.typography.titleMedium)
            LazyColumn {
                items(discoveredDevices) { device ->
                    DeviceItem(device) {
                        statusMessage = "Connecting to ${device.name ?: device.address}..."
                        bleManager.connectToDevice(device)
                        
                        // Wait for connection then Read
                        scope.launch {
                            kotlinx.coroutines.delay(2000) // Wait for connection
                            bleManager.readUwbAddress(device.address)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun DeviceItem(device: BluetoothDevice, onClick: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable(onClick = onClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = device.name ?: "Unknown Device", style = MaterialTheme.typography.bodyLarge)
                Text(text = device.address, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}