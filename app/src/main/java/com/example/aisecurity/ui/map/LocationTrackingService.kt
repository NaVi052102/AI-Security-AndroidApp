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

class LocationTrackingService : Service() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    // THE RAW HARDWARE MANAGER
    private lateinit var locationManager: LocationManager
    private lateinit var locationListener: LocationListener

    // THE DOUBLE LOCKS (CPU + INTERNET)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val CHANNEL_ID = "BioGuardTracker_V2"

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // =========================================================
        // 1. ACQUIRE CPU WAKELOCK (Keeps the processor running)
        // =========================================================
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BioGuard::LocationWakeLock")
        wakeLock?.acquire()

        // =========================================================
        // 2. ACQUIRE WIFI WAKELOCK (Keeps Firebase connected to the internet)
        // =========================================================
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BioGuard::WifiLock")
        wifiLock?.acquire()

        createNotificationChannel()

        // =========================================================
        // 3. HARDWARE GPS OVERRIDE
        // =========================================================
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val userId = auth.currentUser?.uid ?: return

                Log.d("BioGuard_Tracker", "Hardware GPS Fired: ${location.latitude}, ${location.longitude}")

                val locationData = hashMapOf(
                    "currentLat" to location.latitude,
                    "currentLng" to location.longitude,
                    "lastUpdated" to com.google.firebase.Timestamp.now()
                )

                // Upload to Firebase silently
                db.collection("Users").document(userId)
                    .set(locationData, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d("BioGuard_Tracker", "Uploaded to Firebase from Background!")
                    }
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            // Request updates directly from the physical GPS chip and Network cell towers
            // 5000ms (5 seconds) interval, 0 meters distance change
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L,
                0f,
                locationListener
            )

            // Also ping cell towers as a backup if GPS loses signal indoors
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                5000L,
                0f,
                locationListener
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BioGuard Security")
            .setContentText("Hardware-level background tracking active.")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // <-- CHANGE TO HIGH
            .setOngoing(true) // <-- ADD THIS LINE to make it completely un-swipeable
            .build()

        // THE ANDROID 14 FIX: Explicitly declare it as a Location service in the code
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
                CHANNEL_ID,
                "Location Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}