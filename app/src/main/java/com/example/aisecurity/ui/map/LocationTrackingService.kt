package com.example.aisecurity.ui.map

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import android.content.pm.ServiceInfo
import com.google.firebase.firestore.SetOptions
import com.example.aisecurity.ai.NuclearLockdownService // 🚨 Added Siren Import

class LocationTrackingService : Service() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var locationManager: LocationManager
    private lateinit var locationListener: LocationListener

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val channelId = "BioGuardTracker_V2"

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

        // 🚨 Start the Firebase Listener for Remote Control commands
        startRemoteCommandListener()
    }

    private fun startRemoteCommandListener() {
        val uid = auth.currentUser?.uid ?: return

        db.collection("Users").document(uid).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

            // Compare 'cmd_' (Requested) against 'state_' (Current Reality)
            val cmdLoc = snapshot.getBoolean("cmd_location")
            val stateLoc = snapshot.getBoolean("state_location")

            val cmdData = snapshot.getBoolean("cmd_mobile_data")
            val stateData = snapshot.getBoolean("state_mobile_data")

            val cmdBt = snapshot.getBoolean("cmd_bluetooth")
            val stateBt = snapshot.getBoolean("state_bluetooth")

            // Wait, your teammate's UI doesn't have Siren yet, but we are prepping the backend for it!
            val cmdSiren = snapshot.getBoolean("cmd_siren")
            val stateSiren = snapshot.getBoolean("state_siren")

            var stateUpdated = false
            val updates = mutableMapOf<String, Any>()

            // 1. TRIGGER LOCATION POLTERGEIST
            if (cmdLoc != null && cmdLoc != stateLoc) {
                val ghostIntent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
                ghostIntent.setPackage(packageName)
                ghostIntent.putExtra("TARGET_SETTING", "LOCATION")
                sendBroadcast(ghostIntent)

                updates["state_location"] = cmdLoc
                stateUpdated = true
            }

            // 2. TRIGGER MOBILE DATA POLTERGEIST
            if (cmdData != null && cmdData != stateData) {
                val ghostIntent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
                ghostIntent.setPackage(packageName)
                ghostIntent.putExtra("TARGET_SETTING", "DATA")
                sendBroadcast(ghostIntent)

                updates["state_mobile_data"] = cmdData
                stateUpdated = true
            }

            // 3. TRIGGER NATIVE BLUETOOTH + POLTERGEIST FALLBACK
            if (cmdBt != null && cmdBt != stateBt) {
                try {
                    val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
                    if (cmdBt) btAdapter?.enable() else btAdapter?.disable()
                } catch (e: Exception) { e.printStackTrace() }

                val ghostIntent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
                ghostIntent.setPackage(packageName)
                ghostIntent.putExtra("TARGET_SETTING", "BLUETOOTH")
                sendBroadcast(ghostIntent)

                updates["state_bluetooth"] = cmdBt
                stateUpdated = true
            }

            // 4. SIREN CONTROL (Pre-built for when you add it to the UI)
            if (cmdSiren != null && cmdSiren != stateSiren) {
                if (cmdSiren) {
                    startService(Intent(this@LocationTrackingService, NuclearLockdownService::class.java))
                } else {
                    stopService(Intent(this@LocationTrackingService, NuclearLockdownService::class.java))
                }
                updates["state_siren"] = cmdSiren
                stateUpdated = true
            }

            // 5. CONFIRM TO FIREBASE SO THE TEAMMATE UI CAN SEE IT SUCCEEDED
            if (stateUpdated) {
                db.collection("Users").document(uid).set(updates, SetOptions.merge())
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }

        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
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