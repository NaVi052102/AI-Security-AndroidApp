package com.example.aisecurity.ui.map

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import android.content.pm.ServiceInfo
import com.google.firebase.firestore.SetOptions
import com.example.aisecurity.ai.NuclearLockdownService
import kotlinx.coroutines.*

class LocationTrackingService : Service() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var locationManager: LocationManager
    private lateinit var locationListener: LocationListener

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val channelId = "BioGuardTracker_V2"

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var hardwareSyncJob: Job? = null

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BioGuard::LocationWakeLock")
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24-hour timeout

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BioGuard::WifiLock")
        wifiLock?.acquire()

        createNotificationChannel()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val userId = auth.currentUser?.uid ?: return

                val locationData = hashMapOf(
                    "currentLat" to location.latitude,
                    "currentLng" to location.longitude,
                    "lastUpdated" to com.google.firebase.Timestamp.now()
                )

                db.collection("Users").document(userId)
                    .set(locationData, SetOptions.merge())
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 0f, locationListener)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, locationListener)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        startRemoteCommandListener()
        startHardwareStateSyncLoop()
    }

    private fun startHardwareStateSyncLoop() {
        val uid = auth.currentUser?.uid ?: return

        var lastWifi: Boolean? = null
        var lastData: Boolean? = null
        var lastLoc: Boolean? = null
        var lastBt: Boolean? = null
        var lastSaver: Boolean? = null

        hardwareSyncJob?.cancel()
        hardwareSyncJob = serviceScope.launch {
            while (isActive) {
                try {
                    val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val currentWifi = wifiMgr.isWifiEnabled

                    val currentData = Settings.Global.getInt(contentResolver, "mobile_data", 0) == 1

                    val locMgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val currentLoc = locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER) || locMgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

                    val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    val currentBt = btAdapter?.isEnabled == true

                    val powerMgr = getSystemService(Context.POWER_SERVICE) as PowerManager
                    val currentSaver = powerMgr.isPowerSaveMode

                    if (currentWifi != lastWifi || currentData != lastData || currentLoc != lastLoc || currentBt != lastBt || currentSaver != lastSaver) {

                        val isFirstRun = (lastWifi == null)

                        val stateUpdates = hashMapOf<String, Any>(
                            "state_wifi" to currentWifi,
                            "state_mobile_data" to currentData,
                            "state_location" to currentLoc,
                            "state_bluetooth" to currentBt,
                            "state_battery_saver" to currentSaver
                        )

                        if (isFirstRun) {
                            stateUpdates["cmd_wifi"] = currentWifi
                            stateUpdates["cmd_mobile_data"] = currentData
                            stateUpdates["cmd_location"] = currentLoc
                            stateUpdates["cmd_bluetooth"] = currentBt
                            stateUpdates["cmd_battery_saver"] = currentSaver
                            stateUpdates["cmd_siren"] = false
                            stateUpdates["state_siren"] = false
                        }

                        db.collection("Users").document(uid).set(stateUpdates, SetOptions.merge())

                        lastWifi = currentWifi
                        lastData = currentData
                        lastLoc = currentLoc
                        lastBt = currentBt
                        lastSaver = currentSaver

                        Log.d("HARDWARE_SYNC", "Hardware state updated in Firebase.")
                    }
                } catch (e: Exception) { e.printStackTrace() }

                delay(3000)
            }
        }
    }

    private fun startRemoteCommandListener() {
        val uid = auth.currentUser?.uid ?: return

        db.collection("Users").document(uid).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

            val cmdLoc = snapshot.getBoolean("cmd_location")
            val stateLoc = snapshot.getBoolean("state_location")

            val cmdData = snapshot.getBoolean("cmd_mobile_data")
            val stateData = snapshot.getBoolean("state_mobile_data")

            val cmdBt = snapshot.getBoolean("cmd_bluetooth")
            val stateBt = snapshot.getBoolean("state_bluetooth")

            val cmdWifi = snapshot.getBoolean("cmd_wifi")
            val stateWifi = snapshot.getBoolean("state_wifi")

            val cmdSiren = snapshot.getBoolean("cmd_siren")
            val stateSiren = snapshot.getBoolean("state_siren")

            // 1. TRIGGER LOCATION POLTERGEIST
            if (cmdLoc != null && stateLoc != null && cmdLoc != stateLoc) {
                val ghostIntent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
                ghostIntent.setPackage(packageName)
                ghostIntent.putExtra("TARGET_SETTING", "LOCATION")
                sendBroadcast(ghostIntent)
            }

            // 2. TRIGGER MOBILE DATA POLTERGEIST
            if (cmdData != null && stateData != null && cmdData != stateData) {
                val ghostIntent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
                ghostIntent.setPackage(packageName)
                ghostIntent.putExtra("TARGET_SETTING", "DATA")
                sendBroadcast(ghostIntent)
            }

            // 3. TRIGGER WI-FI POLTERGEIST
            if (cmdWifi != null && stateWifi != null && cmdWifi != stateWifi) {
                val ghostIntent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
                ghostIntent.setPackage(packageName)
                ghostIntent.putExtra("TARGET_SETTING", "WIFI")
                sendBroadcast(ghostIntent)
            }

            // 4. TRIGGER NATIVE BLUETOOTH + POLTERGEIST FALLBACK
            if (cmdBt != null && stateBt != null && cmdBt != stateBt) {
                try {
                    val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    if (cmdBt) btAdapter?.enable() else btAdapter?.disable()
                } catch (e: Exception) { e.printStackTrace() }

                val ghostIntent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
                ghostIntent.setPackage(packageName)
                ghostIntent.putExtra("TARGET_SETTING", "BLUETOOTH")
                sendBroadcast(ghostIntent)
            }

            // 5. SIREN CONTROL
            if (cmdSiren != null && stateSiren != null && cmdSiren != stateSiren) {
                if (cmdSiren) {
                    startService(Intent(this@LocationTrackingService, NuclearLockdownService::class.java))
                } else {
                    stopService(Intent(this@LocationTrackingService, NuclearLockdownService::class.java))
                }
                db.collection("Users").document(uid).set(mapOf("state_siren" to cmdSiren), SetOptions.merge())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("BioGuard Security")
            .setContentText("Hardware-level background tracking active.")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        // 🚨 THE FIX: Catch the Android 12+ Exception to prevent violent app crashes during UI transitions
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is android.app.ForegroundServiceStartNotAllowedException) {
                Log.e("LocationService", "Android 12+ blocked Foreground Service launch during UI transition. Running stealth.")
            } else {
                e.printStackTrace()
            }
        }

        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()

        try {
            locationManager.removeUpdates(locationListener)
        } catch (e: Exception) { e.printStackTrace() }

        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Location Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}