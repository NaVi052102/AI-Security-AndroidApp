package com.example.aisecurity.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
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

    // --- TARGET HARDWARE SECRETS ---
    private const val WATCH_NAME = "Watch Pro"
    private const val WATCH_MAC = "FC:01:2C:FD:DD:76" // THE MASTER KEY

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    private val TX_CHAR_UUID = UUID.fromString("abcdef12-1234-1234-1234-123456789abc")
    private val RX_CHAR_UUID = UUID.fromString("abcdef13-1234-1234-1234-123456789abc")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- ENGINES ---
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var rssiPollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
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
        liveStatus.postValue("Linking to Watch Pro...")

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
        liveStatus.postValue("Scanning for Watch Pro...")
        isScanning = true
        scanner.startScan(null, settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return

            if (device.address == WATCH_MAC || device.name == WATCH_NAME) {
                stopScan()
                liveStatus.postValue("Watch Pro Found! Connecting...")

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
                liveStatus.postValue("Connected! Requesting Bandwidth...")
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnect()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                liveStatus.postValue("Bandwidth Secured! Setting up...")
                gatt.discoverServices()
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

                // --- FETCH REAL INTERNET WEATHER BASED ON GPS ---
                scope.launch {
                    delay(500)
                    fetchLiveWeatherAndSend()
                }
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
                val cleanDistance = round(stableDistance * 100) / 100.0
                liveDistance.postValue(cleanDistance)
                sendRadarCommandToWatch(cleanDistance)
            }
        }
    }

    private fun startRssiPolling() {
        rssiPollingJob?.cancel()
        rssiPollingJob = scope.launch {
            delay(1000)
            while (isActive && bluetoothGatt != null) {
                bluetoothGatt?.readRemoteRssi()
                delay(200)
            }
        }
    }

    // =====================================================================
    // LIVE INTERNET WEATHER FETCHER (WITH GPS & CITY NAME FIX)
    // =====================================================================
    @SuppressLint("MissingPermission")
    private fun fetchLiveWeatherAndSend() {
        val context = currentContext ?: return

        // 1. Grab phone coordinates
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)

        val lat = location?.latitude ?: 10.3157
        val lon = location?.longitude ?: 123.8854

        // 2. REVERSE GEOCODE: Force Android to find the real "High Class" City name
        var cityName = "Unknown"
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            if (addresses != null && addresses.isNotEmpty()) {
                // Locality returns the City Name (e.g. Mandaue City)
                cityName = addresses[0].locality ?: addresses[0].subAdminArea ?: "Unknown City"
            }
        } catch (e: Exception) {
            Log.e("BLE_WATCH", "Geocoder failed, falling back to API")
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val apiKey = "ba42da8fa99d8f431eadd7c83b9fd110"
                val url = "https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lon&units=metric&appid=$apiKey"

                val response = java.net.URL(url).readText()
                val json = org.json.JSONObject(response)

                val main = json.getJSONObject("main")
                val temp = main.getDouble("temp").toInt()
                val humidity = main.getInt("humidity")

                val weatherArray = json.getJSONArray("weather")
                val desc = weatherArray.getJSONObject(0).getString("main")

                val windInfo = json.getJSONObject("wind")
                val windSpeed = (windInfo.getDouble("speed") * 3.6).toInt()

                val clouds = json.getJSONObject("clouds")
                val cloudCover = clouds.getInt("all")

                // 3. Fallback to API city name ONLY if Geocoder returned nothing
                if (cityName == "Unknown") {
                    cityName = json.getString("name")
                }

                Log.d("BLE_WATCH", "Weather for $cityName: $temp°C, $desc")

                // 4. Send the cleaned data to the watch
                sendWeatherToWatch(temp, desc, humidity, windSpeed, cloudCover, cityName)

            } catch (e: Exception) {
                Log.e("BLE_WATCH", "Failed to fetch weather: ${e.message}")
            }
        }
    }

    private fun sendWeatherToWatch(temp: Int, desc: String, humidity: Int, wind: Int, rainChance: Int, city: String) {
        scope.launch(Dispatchers.Main) {
            val gatt = bluetoothGatt ?: return@launch
            val rxChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(RX_CHAR_UUID) ?: return@launch

            // Format: <W:temp,desc,hum,wind,rain,city>
            val commandString = "<W:$temp,$desc,$humidity,$wind,$rainChance,$city>"
            val payload = commandString.toByteArray()

            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(rxChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                rxChar.value = payload
                rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(rxChar)
            }

            if (success) {
                Log.d("BLE_WATCH", "Sent Weather to Watch: $commandString")
            }
        }
    }

    private fun sendRadarCommandToWatch(distance: Double) {
        scope.launch(Dispatchers.Main) {
            val gatt = bluetoothGatt
            if (gatt == null) return@launch

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) return@launch

            val rxChar = service.getCharacteristic(RX_CHAR_UUID)
            if (rxChar == null) return@launch

            val commandString = "<RADAR:${(distance * 100).toInt()}>"
            val payload = commandString.toByteArray()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(rxChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                rxChar.value = payload
                rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(rxChar)
            }
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