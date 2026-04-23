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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.MutableLiveData
import com.example.aisecurity.ai.BehavioralAuthClassifier
import com.example.aisecurity.ai.SecurityDatabase
import com.example.aisecurity.ui.LiveLogger
import com.example.aisecurity.ui.LockOverlayService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
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
    private var systemStateJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main)
    private var currentContext: Context? = null

    private val distanceFilter = KalmanFilter(processNoise = 0.008, measurementNoise = 0.5)

    private var lastLockTime = 0L
    private const val LOCK_COOLDOWN_MS = 5000L

    private fun hasPermissions(context: Context): Boolean {
        var accessibilityEnabled = 0
        val service = context.packageName + "/" + "com.example.aisecurity.ai.TouchDynamicsService"
        try { accessibilityEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) } catch (e: Exception) { }
        val hasAcc = if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            settingValue?.contains(service, ignoreCase = true) == true
        } else false

        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        val hasUsage = mode == android.app.AppOpsManager.MODE_ALLOWED

        return hasAcc && hasUsage
    }

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
                startSystemStateSync()

                scope.launch {
                    currentContext?.let { sendSystemState(it) } // Guarantee initial connection sync
                    delay(400)
                    fetchLiveWeatherAndSend()
                    delay(400)
                    WatchMediaService.syncCurrentMedia()
                    delay(400)

                    val auth = FirebaseAuth.getInstance()
                    val user = auth.currentUser
                    if (user != null) {
                        val name = user.displayName ?: "Sentry User"
                        val email = user.email ?: user.phoneNumber ?: ""
                        syncAccountProfile(name, email)
                        delay(400)
                        syncUserTracker(email)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            if (char.uuid == TX_CHAR_UUID) {
                val payload = char.getStringValue(0)
                watchPayload.postValue(payload)

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

                        // 🚨 REMOTE CONTROL TRIGGERED BY WATCH
                        "<CMD:WIFI_1>" -> handleSystemToggle(ctx, "WIFI", true)
                        "<CMD:WIFI_0>" -> handleSystemToggle(ctx, "WIFI", false)
                        "<CMD:DATA_1>" -> handleSystemToggle(ctx, "DATA", true)
                        "<CMD:DATA_0>" -> handleSystemToggle(ctx, "DATA", false)
                        "<CMD:LOC_1>" -> handleSystemToggle(ctx, "LOCATION", true)
                        "<CMD:LOC_0>" -> handleSystemToggle(ctx, "LOCATION", false)
                        "<CMD:BT_1>" -> handleSystemToggle(ctx, "BLUETOOTH", true)
                        "<CMD:BT_0>" -> handleSystemToggle(ctx, "BLUETOOTH", false)
                        "<CMD:BAT_1>" -> handleSystemToggle(ctx, "BATTERY_SAVER", true)
                        "<CMD:BAT_0>" -> handleSystemToggle(ctx, "BATTERY_SAVER", false)
                    }
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
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

    // 🚨 EXECUTES COMMANDS LOCALLY ON PHONE & WRITES TO FIREBASE
    private fun handleSystemToggle(context: Context, target: String, state: Boolean) {
        // 1. Direct hardware toggle attempt (Fastest if allowed)
        try {
            when(target) {
                "WIFI" -> {
                    val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifiMgr.isWifiEnabled = state
                }
                "BLUETOOTH" -> {
                    val btAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    if (state) btAdapter?.enable() else btAdapter?.disable()
                }
            }
        } catch (e: Exception) { Log.e("BLE", "Direct hardware toggle denied by Android: ${e.message}") }

        // 2. Broadcast for Accessibility Service (Poltergeist Intent)
        try {
            val intent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
            intent.putExtra("TARGET_SETTING", target)
            intent.putExtra("TARGET_STATE", state)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
        } catch (e: Exception) { }

        // 3. Write to Firebase (So your app code can read it if online)
        try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid
            if (userId != null) {
                val dbKey = when(target) {
                    "WIFI" -> "state_wifi"
                    "DATA" -> "state_mobile_data"
                    "LOCATION" -> "state_location"
                    "BLUETOOTH" -> "state_bluetooth"
                    "BATTERY_SAVER" -> "state_battery_saver"
                    else -> "state_unknown"
                }
                FirebaseFirestore.getInstance().collection("Users").document(userId)
                    .set(mapOf(dbKey to state), SetOptions.merge())
            }
        } catch (e: Exception) { }

        // Trigger an immediate sync back to watch to confirm state
        scope.launch(Dispatchers.IO) {
            delay(1500)
            sendSystemState(context)
        }
    }

    // 🚨 BULLETPROOF STATE READER: Won't crash if Android blocks one sensor
    private fun sendSystemState(ctx: Context) {
        var w = 0; var m = 0; var l = 0; var b = 0; var s = 0

        try { val wifiMgr = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager; w = if (wifiMgr.isWifiEnabled) 1 else 0 } catch(e:Exception){ Log.e("BLE", "Wi-Fi check failed") }
        try { m = if (Settings.Global.getInt(ctx.contentResolver, "mobile_data", 0) == 1) 1 else 0 } catch(e:Exception){ Log.e("BLE", "Data check failed") }
        try { val locMgr = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager; l = if (locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) 1 else 0 } catch(e:Exception){ Log.e("BLE", "Loc check failed") }
        try { val btAdapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter; b = if (btAdapter?.isEnabled == true) 1 else 0 } catch(e:Exception){ Log.e("BLE", "BT check failed") }
        try { val powerMgr = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager; s = if (powerMgr.isPowerSaveMode) 1 else 0 } catch(e:Exception){ Log.e("BLE", "Battery check failed") }

        sendData("<SYS:$w|$m|$l|$b|$s>")
    }

    private fun startSystemStateSync() {
        systemStateJob?.cancel()
        systemStateJob = scope.launch(Dispatchers.IO) {
            delay(3000)
            while (isActive && bluetoothGatt != null) {
                currentContext?.let { sendSystemState(it) }
                delay(3000)
            }
        }
    }

    private fun handleBioAction(context: Context) {
        if (!hasPermissions(context)) {
            sendNotificationToWatch("Permissions Needed", "Please enable permissions in the phone app first.")
            return
        }

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

        CoroutineScope(Dispatchers.IO).launch {
            val db = SecurityDatabase.get(context)
            val globalThreshold = prefs.getFloat("threshold", 0.08f)
            val targetStability = globalThreshold * 1.2f
            var canUseAi = false

            for (app in db.dao().getAllAppStats()) {
                val appEma = prefs.getFloat("ema_loss_${app.packageName}", 1.0f)
                val pct = (((1.0f - appEma) / (1.0f - targetStability)).coerceIn(0f, 1f) * 100).toInt()
                if (pct >= 75) canUseAi = true
            }

            if (!canUseAi) {
                sendNotificationToWatch("AI Not Ready", "Keep training. Apps must reach 75% stability first.")
                return@launch
            }

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

                    val hasPerms = hasPermissions(context)

                    val allApps = db.dao().getAllAppStats()
                    val globalThreshold = prefs.getFloat("threshold", 0.08f)
                    val targetStability = globalThreshold * 1.2f

                    var anyAppAbove75 = false
                    var sumPct = 0
                    var coreAppsCount = 0

                    for (app in allApps) {
                        val appEma = prefs.getFloat("ema_loss_${app.packageName}", 1.0f)
                        val progressRaw = ((1.0f - appEma) / (1.0f - targetStability)).coerceIn(0f, 1f)
                        val finalPct = (progressRaw * 100f).toInt()
                        sumPct += finalPct
                        coreAppsCount++
                        if (finalPct >= 75) anyAppAbove75 = true
                    }

                    val globalUiConfidence = if (coreAppsCount > 0) sumPct / coreAppsCount else 0
                    val bioScore = if (isReady) risk else globalUiConfidence

                    val canUseAiFlag = if (anyAppAbove75) 1 else 0
                    val isPermsOnFlag = if (hasPerms) 1 else 0

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

                    sendData("<BIO:$bioScore|$status|$progress|$timeStr|$canUseAiFlag|$isPermsOnFlag>")

                    if (loopCount % 3 == 0) {
                        delay(200)
                        sendData("<BIOAPP:CLEAR>")
                        delay(200)

                        val topApps = allApps.sortedByDescending { it.interactionCount }.take(5)

                        for (app in topApps) {
                            val appEma = prefs.getFloat("ema_loss_${app.packageName}", 1.0f)
                            val progressRaw = ((1.0f - appEma) / (1.0f - targetStability)).coerceIn(0f, 1f)
                            val finalPct = (progressRaw * 100f).toInt()

                            val safeName = app.packageName.split(".").last().take(12)
                            sendData("<BIOAPP:$safeName|$finalPct%>")
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

    fun syncAccountProfile(name: String, email: String) {
        val safeName = name.replace("<", "").replace(">", "").replace("|", "").take(25)
        val safeEmail = email.replace("<", "").replace(">", "").replace("|", "").take(35)
        sendData("<ACC:$safeName|$safeEmail>")
    }

    fun sendProximityTelemetry(rssi: Int, peak: String, sessionTime: String) {
        sendData("<PRX1:$rssi|$peak|$sessionTime>")
    }

    fun sendSensorTelemetry(accel: String, gyro: String, pocket: String) {
        val safePocket = pocket.replace("<", "").replace(">", "").replace("|", "")
        sendData("<PRX2:$accel|$gyro|$safePocket>")
    }

    fun syncUserTracker(identifier: String) {
        val safeId = identifier.replace("<", "").replace(">", "").replace("|", "").take(50)
        sendData("<TRACK:$safeId>")

        scope.launch {
            delay(300)
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                val uid = user.uid
                val email = user.email ?: user.phoneNumber ?: "LostDevice"

                val qrUrl = "https://bioguard-efb32.web.app/track?uid=$uid&name=$email"
                sendData("<QR:$qrUrl>")
            }
        }
    }

    // 🚨 RESTORED MISSING FUNCTIONS
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
        systemStateJob?.cancel()
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