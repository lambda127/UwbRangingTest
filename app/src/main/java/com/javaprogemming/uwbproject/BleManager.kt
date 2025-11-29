package com.javaprogemming.uwbproject

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

@SuppressLint("MissingPermission") // Permissions are checked in MainActivity
class BleManager(
    private val context: Context,
    private val onDeviceFound: (BluetoothDevice, Int) -> Unit,
    private val onDataReceived: (String, ByteArray) -> Unit,
    private val onClientConnected: (BluetoothDevice) -> Unit = {}
) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
    private val scanner = bluetoothAdapter?.bluetoothLeScanner
    private var gattServer: BluetoothGattServer? = null

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private const val TAG = "BleManager"
    }

    private val connectedGatts = mutableMapOf<String, BluetoothGatt>()
    private var pendingLocalAddress: ByteArray? = null

    fun startAdvertising(deviceId: Int) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), java.nio.ByteBuffer.allocate(4).putInt(deviceId).array())
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        if (advertiser == null) {
            Log.e(TAG, "Bluetooth LE Advertiser is null")
            return
        }
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        setupGattServer()
    }

    fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        // Do NOT close GATT server here. We might need it for incoming connections.
    }

    fun closeServer() {
        gattServer?.close()
    }

    fun startScanning() {
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        if (scanner == null) {
            Log.e(TAG, "Bluetooth LE Scanner is null")
            return
        }
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScanning() {
        scanner?.stopScan(scanCallback)
    }

    fun connectToDevice(device: BluetoothDevice) {
        device.connectGatt(context, false, gattCallback)
    }

    fun writeData(deviceAddress: String, data: ByteArray) {
        val gatt = connectedGatts[deviceAddress]
        if (gatt != null) {
            val service = gatt.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic != null) {
                characteristic.value = data
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(characteristic)
            }
        }
    }

    fun setLocalUwbAddress(address: ByteArray) {
        if (gattServer == null) {
            Log.e(TAG, "GATT Server is null. Cannot set local UWB address. Make sure to start advertising first.")
            return
        }
        val service = gattServer?.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.w(TAG, "Characteristic not found in GATT Server. Storing address as pending.")
            pendingLocalAddress = address
            return
        }
        characteristic.value = address
        Log.d(TAG, "Local UWB Address set in GATT Server: ${address.size} bytes")
    }

    fun readUwbAddress(deviceAddress: String) {
        val gatt = connectedGatts[deviceAddress]
        if (gatt != null) {
            val service = gatt.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic != null) {
                gatt.readCharacteristic(characteristic)
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val serviceData = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
                val remoteDeviceId = if (serviceData != null && serviceData.size == 4) {
                    java.nio.ByteBuffer.wrap(serviceData).int
                } else {
                    -1
                }
                onDeviceFound(device, remoteDeviceId)
            }
        }
    }

    private fun setupGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            if (status == BluetoothGatt.GATT_SUCCESS && service?.uuid == SERVICE_UUID) {
                Log.d(TAG, "Service added successfully.")
                pendingLocalAddress?.let { address ->
                    val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        characteristic.value = address
                        Log.d(TAG, "Pending Local UWB Address set in GATT Server: ${address.size} bytes")
                        pendingLocalAddress = null
                    } else {
                        Log.e(TAG, "Characteristic not found in added service.")
                    }
                }
            } else {
                Log.e(TAG, "Failed to add service: status=$status")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.d(TAG, "Server connection state for ${device?.address}: $newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && device != null) {
                onClientConnected(device)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            if (characteristic?.uuid == CHARACTERISTIC_UUID) {
                value?.let {
                    Log.d(TAG, "Server received write request from ${device?.address}: ${it.size} bytes")
                    onDataReceived(device?.address ?: "", it)
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
        
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
             super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
             if (characteristic?.uuid == CHARACTERISTIC_UUID) {
                 gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, characteristic.value)
             }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server: ${gatt?.device?.address}")
                gatt?.discoverServices()
                connectedGatts[gatt?.device?.address ?: ""] = gatt!!
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server: ${gatt?.device?.address}")
                connectedGatts.remove(gatt?.device?.address)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.d(TAG, "Services discovered for ${gatt?.device?.address}: status=$status")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHARACTERISTIC_UUID) {
                Log.d(TAG, "Read characteristic from ${gatt.device.address}: ${characteristic.value.size} bytes")
                onDataReceived(gatt.device.address, characteristic.value) // Reuse onDataReceived for Read result too?
                // Or maybe a separate callback?
                // For simplicity, let's assume onDataReceived handles both "Received via Write" and "Received via Read".
                // But wait, the format might be different.
                // Advertiser shares ONLY Address.
                // Controller shares Address + SessionID.
                // We can distinguish by length or context.
            }
        }
    }
}
