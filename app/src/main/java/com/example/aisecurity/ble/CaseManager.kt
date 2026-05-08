package com.example.aisecurity.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

@SuppressLint("MissingPermission")
object CaseManager {

    val isConnected = MutableLiveData<Boolean>(false)
    val liveStatus = MutableLiveData<String>("Disconnected")

    var connectedMacAddress: String? = null

    private val CASE_SERVICE_UUID    = UUID.fromString("8101a153-61b6-444a-a0f5-502a5e958742")
    private val CASE_COMMAND_UUID    = UUID.fromString("31f6c6d2-b016-43b9-b883-99b50db12f68")

    private var bluetoothGatt: BluetoothGatt? = null

    // ✅ FIX 1: Cache the characteristic once after service discovery.
    // Fetching it fresh on every write can return a stale reference on
    // some Android versions, causing the write to be silently dropped.
    private var cmdCharacteristic: BluetoothGattCharacteristic? = null

    private var currentContext: Context? = null

    fun connectToTarget(context: Context, macAddress: String) {
        currentContext = context.applicationContext
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            liveStatus.postValue("Error: Bluetooth is OFF")
            return
        }

        disconnect()
        liveStatus.postValue("Linking to Sentry Case...")

        val device = adapter.getRemoteDevice(macAddress)
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(currentContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(currentContext, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                liveStatus.postValue("Securing Link...")
                connectedMacAddress = gatt.device.address
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.e("BLE_CASE", "Case Disconnected. Status: $status")
                disconnect()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                CoroutineScope(Dispatchers.Main).launch {
                    delay(600)
                    gatt.discoverServices()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(CASE_SERVICE_UUID)
                if (service != null) {
                    // ✅ FIX 1 (continued): Cache the characteristic right here, once.
                    val characteristic = service.getCharacteristic(CASE_COMMAND_UUID)
                    if (characteristic != null) {
                        cmdCharacteristic = characteristic
                        isConnected.postValue(true)
                        liveStatus.postValue("Sentry Case Connected ✅")
                        Log.d("BLE_CASE", "Characteristic cached successfully.")
                    } else {
                        showToast("ERROR: Command characteristic UUID not found on ESP32!")
                        disconnect()
                    }
                } else {
                    showToast("ERROR: Service UUID not found on ESP32!")
                    disconnect()
                }
            }
        }
    }

    fun triggerLock() {
        sendCommand("<CMD:LOCK>")
    }

    fun triggerUnlock() {
        sendCommand("<CMD:UNLOCK>")
    }

    private fun sendCommand(command: String) {
        // ✅ FIX 2: Use Dispatchers.IO — using Main causes Android to silently drop the packet.
        CoroutineScope(Dispatchers.IO).launch {
            val gatt = bluetoothGatt
            if (gatt == null) {
                showToast("ERROR: GATT connection is null.")
                return@launch
            }

            // ✅ FIX 1 (continued): Use the cached characteristic instead of re-fetching.
            val cmdChar = cmdCharacteristic
            if (cmdChar == null) {
                showToast("ERROR: Characteristic not ready. Try reconnecting.")
                return@launch
            }

            val dataBytes = command.toByteArray(Charsets.UTF_8)
            val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeCharacteristic(cmdChar, dataBytes, writeType)
                Log.d("BLE_CASE", "Payload Sent (API33+): $command | Result code: $result")
            } else {
                @Suppress("DEPRECATION")
                cmdChar.value = dataBytes
                cmdChar.writeType = writeType
                @Suppress("DEPRECATION")
                val success = gatt.writeCharacteristic(cmdChar)
                Log.d("BLE_CASE", "Payload Sent (legacy): $command | Queued: $success")
            }
        }
    }

    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e("BLE_CASE", "Error closing GATT: ${e.message}")
        }
        bluetoothGatt      = null
        cmdCharacteristic  = null   // ✅ Clear the cache on disconnect
        connectedMacAddress = null
        isConnected.postValue(false)
        liveStatus.postValue("Case Disconnected")
    }

    private fun showToast(message: String) {
        currentContext?.let { ctx ->
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}