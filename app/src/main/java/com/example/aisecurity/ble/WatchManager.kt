package com.example.aisecurity.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.MutableLiveData
import com.example.aisecurity.ai.BehavioralAuthClassifier
import com.example.aisecurity.ai.SecurityDatabase
import com.example.aisecurity.ui.LiveLogger
import com.example.aisecurity.ui.LockOverlayService
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.math.round

@SuppressLint("MissingPermission")
object WatchManager {

    val liveDistance = MutableLiveData<Double>(0.0)
    val liveStatus = MutableLiveData<String>("Disconnected")
    val isConnected = MutableLiveData<Boolean>(false)
    val watchPayload = MutableLiveData<String>("")

    // 🚨 SENSOR VARIABLES
    val liveRSSI = MutableLiveData<Int>(0)
    val liveHeartRate = MutableLiveData<String>("--")
    val wristStatus = MutableLiveData<String>("Unknown")
    val touchStatus = MutableLiveData<String>("Idle")

    private const val WATCH_NAME = "Watch Pro"
    private const val WATCH_MAC = "FC:01:2C:FD:DD:76"

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    private val TX_CHAR_UUID = UUID.fromString("abcdef12-1234-1234-1234-123456789abc")
    private val RX_CHAR_UUID = UUID.fromString("abcdef13-1234-1234-1234-123456789abc")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    private var rssiPollingJob: Job? = null
    private var bioSyncJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main)
    private var currentContext: Context? = null

    private val distanceFilter = KalmanFilter(processNoise = 0.008, measurementNoise = 0.5)

    private var lastLockTime = 0L
    private const val LOCK_COOLDOWN_MS = 5000L

    fun connectToTarget(context: Context, macAddress: String) {
        currentContext = context.applicationContext
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            liveStatus.postValue("Error: Bluetooth is OFF")
            isConnected.postValue(false)
            return
        }

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null

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

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                liveStatus.postValue("Connected! Requesting Bandwidth...")
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("BLE", "Connection dropped or failed. Status: $status")
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
                startBiometricsSync()

                scope.launch {
                    delay(500)
                    fetchLiveWeatherAndSend()
                    delay(500)
                    WatchMediaService.syncCurrentMedia()
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            if (char.uuid == TX_CHAR_UUID) {
                val payload = char.getStringValue(0)
                watchPayload.postValue(payload)

                // 🚨 THIS PIPES THE WATCH SENSORS TO YOUR UI DASHBOARD
                if (payload.startsWith("<HR:")) {
                    liveHeartRate.postValue(payload.substringAfter(":").replace(">", ""))
                } else if (payload.startsWith("<WRIST:")) {
                    wristStatus.postValue(payload.substringAfter(":").replace(">", ""))
                } else if (payload.startsWith("<TOUCH:")) {
                    touchStatus.postValue(payload.substringAfter(":").replace(">", ""))
                }

                currentContext?.let { ctx ->
                    when (payload) {
                        "<CMD:PLAY>" -> WatchMediaService.sendMediaCommand(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        "<CMD:PREV>" -> WatchMediaService.sendMediaCommand(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                        "<CMD:NEXT>" -> WatchMediaService.sendMediaCommand(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)

                        "<CMD:BIO_ACTION>" -> handleBioAction(ctx)
                        "<CMD:BIO_USE_AI>" -> handleBioUseAi(ctx)
                        "<CMD:BIO_RESET>" -> handleBioReset(ctx)
                    }
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {

                // 🚨 THIS IS THE FIX FOR THE GRAPH: IT NOW RECEIVES THE RSSI NUMBER!
                liveRSSI.postValue(rssi)

                val rawDistance = calculateDistance(rssi)
                val stableDistance = distanceFilter.update(rawDistance)
                val cleanDistance = round(stableDistance * 100) / 100.0

                liveDistance.postValue(cleanDistance)
                sendRadarCommandToWatch(cleanDistance)

                currentContext?.let { checkDistanceAndLock(it, cleanDistance.toFloat()) }
            } else {
                disconnect()
            }
        }
    }

    private fun handleBioAction(context: Context) {
        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val isReady = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", true)
        val editor = prefs.edit()

        if (isReady) {
            editor.putBoolean("ai_ready", false)
            editor.putBoolean("training_paused", false)
            editor.putLong("session_start_time", System.currentTimeMillis())
        } else {
            if (isPaused) {
                editor.putBoolean("training_paused", false)
                editor.putLong("session_start_time", System.currentTimeMillis())
            } else {
                val sessionStart = prefs.getLong("session_start_time", 0L)
                var accumulated = prefs.getLong("accumulated_time", 0L)
                if (sessionStart > 0) {
                    accumulated += (System.currentTimeMillis() - sessionStart)
                }
                editor.putLong("accumulated_time", accumulated)
                editor.putLong("session_start_time", 0L)
                editor.putBoolean("training_paused", true)
            }
        }
        editor.apply()
    }

    private fun handleBioUseAi(context: Context) {
        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        val accumulatedTime = prefs.getLong("accumulated_time", 0L)
        val sessionStart = prefs.getLong("session_start_time", 0L)
        var totalTime = accumulatedTime

        if (!prefs.getBoolean("training_paused", true) && sessionStart > 0 && !prefs.getBoolean("ai_ready", false)) {
            totalTime += (System.currentTimeMillis() - sessionStart)
        }

        prefs.edit()
            .putLong("accumulated_time", totalTime)
            .putLong("session_start_time", 0L)
            .putBoolean("training_paused", true)
            .putBoolean("ai_ready", true)
            .apply()

        CoroutineScope(Dispatchers.IO).launch {
            val db = SecurityDatabase.get(context)
            val data = db.dao().getTrainingData()
            val classifier = BehavioralAuthClassifier(context)
            val errors = data.map { classifier.getError(floatArrayOf(it.velocityX, 0.5f, 0.1f, 0.1f)) }
            val newThreshold = BehavioralAuthClassifier.calculateThreshold(errors)
            prefs.edit().putFloat("threshold", newThreshold).apply()
        }
    }

    private fun handleBioReset(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = SecurityDatabase.get(context)
            val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

            db.dao().wipeTotalData()

            prefs.edit()
                .putBoolean("ai_ready", false)
                .putBoolean("training_paused", true)
                .putInt("current_risk", 0)
                .putLong("accumulated_time", 0L)
                .putLong("session_start_time", 0L)
                .remove("threshold")
                .apply()

            LiveLogger.clear()
            val classifier = BehavioralAuthClassifier(context)
            classifier.wipeMemory()
        }
    }

    private fun startRssiPolling() {
        rssiPollingJob?.cancel()
        rssiPollingJob = scope.launch {
            delay(1000)
            var failedPings = 0
            while (isActive && bluetoothGatt != null) {
                val pingSent = bluetoothGatt?.readRemoteRssi() == true

                if (!pingSent) {
                    failedPings++
                    if (failedPings >= 2) {
                        Log.e("BLE", "Watch missed 2 heartbeats. Declaring connection dead.")
                        disconnect()
                        break
                    }
                } else {
                    failedPings = 0
                }
                delay(1000)
            }
        }
    }

    private fun startBiometricsSync() {
        val context = currentContext ?: return
        bioSyncJob?.cancel()

        bioSyncJob = scope.launch(Dispatchers.IO) {
            val db = SecurityDatabase.get(context)
            val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
            var loopCount = 0

            delay(1000)

            while (isActive && bluetoothGatt != null) {
                try {
                    val isReady = prefs.getBoolean("ai_ready", false)
                    val isPaused = prefs.getBoolean("training_paused", true)
                    val risk = prefs.getInt("current_risk", 0)
                    val swipes = db.dao().getTotalTouchCount()

                    var accumulatedTime = prefs.getLong("accumulated_time", 0L)
                    val sessionStart = prefs.getLong("session_start_time", 0L)

                    if (!isPaused && sessionStart > 0 && !isReady) {
                        accumulatedTime += (System.currentTimeMillis() - sessionStart)
                    }

                    val requiredMs = TimeUnit.DAYS.toMillis(1)

                    val status = if (isReady) {
                        if (risk < 50) "SECURE" else if (risk < 100) "WARNING" else "INTRUDER"
                    } else {
                        if (isPaused) "PAUSED" else "TRAINING"
                    }

                    val bioScore = if (isReady) risk else swipes

                    val progress = if (isReady) {
                        ((risk / 120f) * 183f).toInt().coerceIn(0, 183)
                    } else {
                        ((accumulatedTime.toFloat() / requiredMs) * 183f).toInt().coerceIn(0, 183)
                    }

                    val timeStr = if (isReady) {
                        "Active Protection"
                    } else {
                        val remainingMs = (requiredMs - accumulatedTime).coerceAtLeast(0)
                        val d = TimeUnit.MILLISECONDS.toDays(remainingMs)
                        val h = TimeUnit.MILLISECONDS.toHours(remainingMs) % 24
                        "$d d $h h remaining"
                    }

                    sendData("<BIO:$bioScore|$status|$progress|$timeStr>")

                    if (loopCount % 3 == 0) {
                        delay(200)
                        sendData("<BIOAPP:CLEAR>")
                        delay(200)

                        val allApps = db.dao().getAllAppStats()
                        val topApps = allApps.sortedByDescending { it.interactionCount }.take(5)

                        for (app in topApps) {
                            val speed = app.avgVelocity.toInt()
                            val count = app.interactionCount
                            sendData("<BIOAPP:${app.packageName}|$speed px/s   $count>")
                            delay(200)
                        }
                        sendData("<BIOAPP:END>")
                    }
                    loopCount++
                } catch (e: Exception) {
                    Log.e("WATCH_SYNC", "Biometrics Sync Error: ${e.message}")
                }
                delay(1000)
            }
        }
    }

    private fun checkDistanceAndLock(context: Context, currentDistanceMeters: Float) {
        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        val isProximityArmed = prefs.getBoolean("is_proximity_armed", true)
        if (!isProximityArmed) return

        val thresholdMeters = prefs.getFloat("radar_threshold_meters", 10.0f)
        val isAlreadyLocked = prefs.getBoolean("is_system_locked", false)

        if (currentDistanceMeters > thresholdMeters && !isAlreadyLocked) {
            val now = System.currentTimeMillis()
            if (now - lastLockTime < LOCK_COOLDOWN_MS) return
            lastLockTime = now

            LiveLogger.log("🚨 PROXIMITY BREACH: Distance ($currentDistanceMeters m) exceeded threshold ($thresholdMeters m)!")

            prefs.edit().putBoolean("is_system_locked", true).apply()

            try {
                val lockIntent = Intent(context, LockOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(lockIntent)
                } else {
                    context.startService(lockIntent)
                }
            } catch (e: Exception) {
                LiveLogger.log("❌ Failed to launch LockOverlayService: ${e.message}")
            }
        }
    }

    fun sendData(payload: String) {
        scope.launch(Dispatchers.Main) {
            val gatt = bluetoothGatt ?: return@launch
            val rxChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(RX_CHAR_UUID) ?: return@launch

            val dataBytes = payload.toByteArray(Charsets.UTF_8)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(rxChar, dataBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                rxChar.value = dataBytes
                rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(rxChar)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLiveWeatherAndSend() {
        val context = currentContext ?: return

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)

        val lat = location?.latitude ?: 10.3157
        val lon = location?.longitude ?: 123.8854

        var cityName = "Unknown"
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            if (addresses != null && addresses.isNotEmpty()) {
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

                if (cityName == "Unknown") {
                    cityName = json.getString("name")
                }

                sendWeatherToWatch(temp, desc, humidity, windSpeed, cloudCover, cityName)

            } catch (e: Exception) {
                Log.e("BLE_WATCH", "Failed to fetch weather: ${e.message}")
            }
        }
    }

    private fun sendWeatherToWatch(temp: Int, desc: String, humidity: Int, wind: Int, rainChance: Int, city: String) {
        sendData("<W:$temp,$desc,$humidity,$wind,$rainChance,$city>")
    }

    private fun sendRadarCommandToWatch(distance: Double) {
        sendData("<RADAR:${(distance * 100).toInt()}>")
    }

    fun sendMusicToWatch(title: String, artist: String, album: String, isPlaying: Boolean) {
        val safeTitle = title.replace("<", "").replace(">", "").replace("|", "").take(20)
        val safeArtist = artist.replace("<", "").replace(">", "").replace("|", "").take(20)
        val safeAlbum = album.replace("<", "").replace(">", "").replace("|", "").take(20)
        val stateInt = if (isPlaying) 1 else 0

        sendData("<MUSIC:$safeTitle|$safeArtist|$safeAlbum|$stateInt>")
    }

    fun sendNotificationToWatch(title: String, text: String) {
        val safeTitle = title.replace("<", "").replace(">", "").replace("|", "").take(25)
        val safeText = text.replace("<", "").replace(">", "").replace("|", "").take(120)

        sendData("<NOTIF:$safeTitle|$safeText>")
    }

    fun disconnect() {
        stopScan()
        rssiPollingJob?.cancel()
        bioSyncJob?.cancel()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e("BLE", "Error closing GATT: ${e.message}")
        }
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