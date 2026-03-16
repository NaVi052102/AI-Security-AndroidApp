package com.example.aisecurity.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import java.util.*
import kotlin.math.pow
import kotlin.math.round

@SuppressLint("MissingPermission")
object WatchManager {

    // --- GLOBALLY ACCESSIBLE LIVE DATA ---
    val liveDistance = MutableLiveData<Double>(0.0)
    val liveStatus = MutableLiveData<String>("Disconnected")
    val isConnected = MutableLiveData<Boolean>(false)
    val watchPayload = MutableLiveData<String>("")

    // --- WATCH SECRETS ---
    private const val WATCH_NAME = "Security Watch"
    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    private val TX_CHAR_UUID = UUID.fromString("abcdef12-1234-1234-1234-123456789abc")
    private val RX_CHAR_UUID = UUID.fromString("abcdef13-1234-1234-1234-123456789abc")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- ENGINES ---
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var rssiPollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var currentContext: Context? = null

    // --- THE STABILIZER ---
    private val distanceFilter = KalmanFilter(processNoise = 0.008, measurementNoise = 0.5)

    // =====================================================================
    // CONNECTION LOGIC
    // =====================================================================

    fun connectToTarget(context: Context, macAddress: String) {
        currentContext = context.applicationContext
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null) {
            liveStatus.postValue("Error: Bluetooth is OFF")
            return
        }

        stopScan()
        liveStatus.postValue("Linking to Watch...")

        val device = adapter.getRemoteDevice(macAddress)

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(currentContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(currentContext, false, gattCallback)
        }
    }

    fun startScan(context: Context) {
        currentContext = context.applicationContext
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner

        if (scanner == null) {
            liveStatus.postValue("Error: Bluetooth is OFF")
            return
        }

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        liveStatus.postValue("Scanning for watch...")
        isScanning = true
        scanner.startScan(null, settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            if (device.name == WATCH_NAME) {
                stopScan()
                liveStatus.postValue("Watch Found! Connecting...")

                currentContext?.let { ctx ->
                    bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(ctx, false, gattCallback)
                    }
                }
            }
        }
    }

    // =====================================================================
    // GATT COMMUNICATION (The Data Bridge)
    // =====================================================================

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                liveStatus.postValue("Connected! Setting up...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val txChar = service?.getCharacteristic(TX_CHAR_UUID)

                if (txChar != null) {
                    gatt.setCharacteristicNotification(txChar, true)
                    val descriptor = txChar.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }

                isConnected.postValue(true)
                liveStatus.postValue("Secure Link Established ✅")

                distanceFilter.reset()
                startRssiPolling()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            if (char.uuid == TX_CHAR_UUID) {
                watchPayload.postValue(char.getStringValue(0))
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val rawDistance = calculateDistance(rssi)
                val stableDistance = distanceFilter.update(rawDistance)

                // Round to 2 decimal places for a clean UI (e.g., 1.45 instead of 1.458291)
                val cleanDistance = round(stableDistance * 100) / 100.0

                liveDistance.postValue(cleanDistance)
                sendRadarCommandToWatch(cleanDistance)
            }
        }
    }

    private fun startRssiPolling() {
        rssiPollingJob?.cancel()
        rssiPollingJob = scope.launch {
            // FIX 1: Give the BLE Stack 2 seconds to finish writing the Descriptor before we start pinging it!
            delay(2000)

            while (isActive && bluetoothGatt != null) {
                bluetoothGatt?.readRemoteRssi()
                delay(1000) // Update distance every 1 second
            }
        }
    }

    private fun sendRadarCommandToWatch(distance: Double) {
        // FIX 2: Launch this in a Coroutine with a tiny delay so it doesn't collide with the RSSI read!
        scope.launch {
            delay(100)
            val gatt = bluetoothGatt ?: return@launch
            val rxChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(RX_CHAR_UUID) ?: return@launch

            rxChar.value = "<RADAR:${(distance * 10).toInt()}>".toByteArray()

            // Suppressing deprecation warning for older method signature
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(rxChar)
        }
    }

    fun disconnect() {
        stopScan()
        rssiPollingJob?.cancel()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected.postValue(false)
        liveStatus.postValue("Watch Disconnected")
        liveDistance.postValue(0.0)
        distanceFilter.reset()
    }

    private fun stopScan() {
        if (!isScanning) return
        val scanner = (currentContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner
        scanner?.stopScan(scanCallback)
        isScanning = false
    }

    private fun calculateDistance(rssi: Int): Double = 10.0.pow((-59 - rssi).toDouble() / 20.0)
}