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
import androidx.compose.ui.Alignment
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
    private var isController by mutableStateOf(true)
    private var isWorking by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        uwbManager = UwbManager(this) { distance ->
            rangingDistance = distance
            statusMessage = "Ranging: ${"%.2f".format(distance)} m"
            Log.d("MainActivity", "Ranging result: $distance")
        }

        bleManager = BleManager(this,
            onDeviceFound = { device ->
                if (!discoveredDevices.any { it.address == device.address }) {
                    discoveredDevices.add(device)
                    Log.d("MainActivity", "Device found: ${device.address}")
                }
            },
            onDataReceived = { address, data ->
                try {
                    Log.d("MainActivity", "Data received from $address: ${data.size} bytes")
                    // Distinguish between Read Result (Peer Address) and Write Result (Params)
                    // Distinguish between Read Result (Peer Address) and Write Result (Params)
                    if (data.size == 2 || data.size == 8) { // Address size can vary (2 for short, 8 for extended)
                        // This is Peer Address (Read by Controller)
                        val peerAddressBytes = data
                        val sessionId = Random.nextInt()
                        val sessionKey = Random.nextBytes(8) // Generate 8-byte session key
                        
                        statusMessage = "Read Peer Address. Starting Controller..."
                        Log.d("MainActivity", "Read Peer Address: ${peerAddressBytes.contentToString()}. Starting Controller with Session ID: $sessionId")
                        
                        // 1. Start Ranging as Controller
                        uwbManager.startRangingAsController(peerAddressBytes, sessionId, sessionKey) { localAddress ->
                            // 2. Send Local Address + SessionID + SessionKey to Peer
                            lifecycleScope.launch {
                                val myAddrBytes = localAddress.address
                                val buffer = ByteBuffer.allocate(4 + 8 + 1 + myAddrBytes.size) // Int(4) + Key(8) + Len(1) + Addr(var)
                                buffer.putInt(sessionId)
                                buffer.put(sessionKey)
                                buffer.put(myAddrBytes.size.toByte())
                                buffer.put(myAddrBytes)
                                Log.d("MainActivity", "Sending Params to $address: SessionID=$sessionId, Key=${sessionKey.contentToString()}, MyAddr=${myAddrBytes.contentToString()}")
                                bleManager.writeData(address, buffer.array())
                            }
                        }
                    } else {
                        // This is Params (Received by Controlee)
                        val buffer = ByteBuffer.wrap(data)
                        val sessionId = buffer.int
                        val sessionKey = ByteArray(8)
                        buffer.get(sessionKey)
                        val addrLen = buffer.get().toInt()
                        val addrBytes = ByteArray(addrLen)
                        buffer.get(addrBytes)
                        
                        statusMessage = "Received params. Starting Controlee..."
                        Log.d("MainActivity", "Received Params: SessionID=$sessionId, Key=${sessionKey.contentToString()}, PeerAddr=${addrBytes.contentToString()}. Starting Controlee...")
                        uwbManager.startRangingWithPreparedSession(addrBytes, sessionId, sessionKey)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error parsing data", e)
                    statusMessage = "Error parsing data"
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
            if (!allGranted) {
                Toast.makeText(context, "Permissions required", Toast.LENGTH_SHORT).show()
            }
        }

        LaunchedEffect(Unit) {
            if (!permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                launcher.launch(permissions.toTypedArray())
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "UWB Ranging", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Role: ${if (isController) "Controller" else "Controlee"}")
                Spacer(modifier = Modifier.width(8.dp))
                Switch(checked = isController, onCheckedChange = { 
                    isController = it 
                    stopWorking()
                })
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(onClick = {
                if (isWorking) {
                    stopWorking()
                } else {
                    startWorking()
                }
            }) {
                Text(text = if (isWorking) "Stop" else "Start")
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Text(text = "Status: $statusMessage", style = MaterialTheme.typography.bodyLarge)
            
            if (isController) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Discovered Devices:", style = MaterialTheme.typography.titleMedium)
                LazyColumn {
                    items(discoveredDevices) { device ->
                        DeviceItem(device) {
                            statusMessage = "Connecting to ${device.name ?: device.address}..."
                            Log.d("MainActivity", "Connecting to ${device.address}")
                            bleManager.connectToDevice(device)
                            
                            // Wait for connection then Read
                            scope.launch {
                                kotlinx.coroutines.delay(2000) // Wait for connection
                                Log.d("MainActivity", "Reading UWB Address from ${device.address}")
                                bleManager.readUwbAddress(device.address)
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun startWorking() {
        isWorking = true
        statusMessage = "Starting..."
        discoveredDevices.clear()
        
        if (isController) {
            Log.d("MainActivity", "Starting Controller: Scanning...")
            bleManager.startScanning()
            statusMessage = "Scanning..."
        } else {
            Log.d("MainActivity", "Starting Controlee: Advertising...")
            lifecycleScope.launch {
                val addr = uwbManager.prepareControleeSession()
                Log.d("MainActivity", "Local UWB Address: ${addr.address.contentToString()}")
                bleManager.startAdvertising()
                bleManager.setLocalUwbAddress(addr.address)
                statusMessage = "Advertising..."
            }
        }
    }

    private fun stopWorking() {
        isWorking = false
        statusMessage = "Stopped"
        Log.d("MainActivity", "Stopping...")
        bleManager.stopScanning()
        bleManager.stopAdvertising()
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