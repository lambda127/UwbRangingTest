package com.javaprogemming.uwbproject

import android.content.Context
import android.util.Log
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbControleeSessionScope
import androidx.core.uwb.UwbControllerSessionScope
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class UwbManager(private val context: Context, private val onRangingResult: (Float) -> Unit) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var rangingJob: Job? = null
    private lateinit var uwbManager: UwbManager

    init {
        scope.launch {
            uwbManager = UwbManager.createInstance(context)
        }
    }

    companion object {
        private const val TAG = "UwbManager"
    }

    fun startRangingAsController(peerAddressBytes: ByteArray, sessionId: Int, onSessionStarted: (UwbAddress) -> Unit) {
        scope.launch {
            try {
                // Ensure manager is initialized
                if (!::uwbManager.isInitialized) {
                    uwbManager = UwbManager.createInstance(context)
                }
                
                val clientSessionScope = uwbManager.controllerSessionScope()
                val localAddress = clientSessionScope.localAddress
                Log.d(TAG, "Controller Local Address: $localAddress")
                onSessionStarted(localAddress)
                
                val peerAddress = UwbAddress(peerAddressBytes)
                val uwbDevice = UwbDevice(peerAddress)
                
                val parameters = RangingParameters(
                    uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                    sessionId = sessionId,
                    subSessionId = 0,
                    sessionKeyInfo = null,
                    subSessionKeyInfo = null,
                    complexChannel = null,
                    peerDevices = listOf(uwbDevice),
                    updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
                )

                clientSessionScope.prepareSession(parameters)
                    .onEach { result ->
                        when (result) {
                            is RangingResult.RangingResultPosition -> {
                                result.position.distance?.let {
                                    onRangingResult(it.value)
                                }
                            }
                            is RangingResult.RangingResultPeerDisconnected -> {
                                Log.d(TAG, "Peer disconnected")
                            }
                        }
                    }
                    .launchIn(this)

            } catch (e: Exception) {
                Log.e(TAG, "Error starting ranging as controller", e)
            }
        }
    }

    private var controleeSessionScope: UwbControleeSessionScope? = null

    suspend fun prepareControleeSession(): UwbAddress {
        if (!::uwbManager.isInitialized) {
            uwbManager = UwbManager.createInstance(context)
        }
        val scope = uwbManager.controleeSessionScope()
        controleeSessionScope = scope
        Log.d(TAG, "Controlee Local Address: ${scope.localAddress}")
        return scope.localAddress
    }

    fun startRangingWithPreparedSession(peerAddressBytes: ByteArray, sessionId: Int) {
        scope.launch {
            try {
                val clientSessionScope = controleeSessionScope ?: run {
                     if (!::uwbManager.isInitialized) {
                        uwbManager = UwbManager.createInstance(context)
                    }
                    uwbManager.controleeSessionScope()
                }
                
                val peerAddress = UwbAddress(peerAddressBytes)
                val uwbDevice = UwbDevice(peerAddress)

                val parameters = RangingParameters(
                    uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                    sessionId = sessionId,
                    subSessionId = 0,
                    sessionKeyInfo = null,
                    subSessionKeyInfo = null,
                    complexChannel = null,
                    peerDevices = listOf(uwbDevice),
                    updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
                )

                clientSessionScope.prepareSession(parameters)
                    .onEach { result ->
                        when (result) {
                            is RangingResult.RangingResultPosition -> {
                                result.position.distance?.let {
                                    onRangingResult(it.value)
                                }
                            }
                             is RangingResult.RangingResultPeerDisconnected -> {
                                Log.d(TAG, "Peer disconnected")
                            }
                        }
                    }
                    .launchIn(this)

            } catch (e: Exception) {
                Log.e(TAG, "Error starting ranging with prepared session", e)
            }
        }
    }
    
    suspend fun getLocalAddress(): UwbAddress {
        if (!::uwbManager.isInitialized) {
            uwbManager = UwbManager.createInstance(context)
        }
        return uwbManager.controleeSessionScope().localAddress
    }
}
