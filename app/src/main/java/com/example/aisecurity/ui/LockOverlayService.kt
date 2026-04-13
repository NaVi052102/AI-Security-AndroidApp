@file:Suppress("RemoveRedundantQualifierName", "DEPRECATION", "SpellCheckingInspection")
package com.example.aisecurity.ui

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.aisecurity.R

class LockOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var isOverlayVisible = false

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "lockdown_channel"
        val channel = NotificationChannel(channelId, "Security Override", NotificationManager.IMPORTANCE_HIGH)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Security Protocol Active")
            .setContentText("Device lockdown is engaged.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // Changed to standard alert icon
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) { // Android 14+ requires explicit type
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }

        // Only draw the overlay if it isn't already on the screen
        if (!isOverlayVisible) {
            drawLockScreen()
        }

        return START_STICKY
    }

    @SuppressLint("InflateParams", "ApplySharedPref")
    @Suppress("CommitPrefEdits")
    private fun drawLockScreen() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        // SET UP THE MAIN RED OVERLAY
        overlayView = inflater.inflate(R.layout.activity_persistent_lock, null)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.CENTER

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val customMsg = prefs.getString("warning_msg", "This device has been lost or stolen.")
        val customNum = prefs.getString("contact_num", "No number provided.")

        prefs.edit().putBoolean("is_system_locked", true).apply()

        overlayView?.findViewById<TextView>(R.id.tvDisplayMessage)?.text = customMsg
        overlayView?.findViewById<TextView>(R.id.tvDisplayNumber)?.text = customNum

        // ==========================================
        // THE TEST OVERRIDE & INSTRUCTIONS
        // ==========================================
        val btnUnlockScreen = overlayView?.findViewById<Button>(R.id.btnUnlockScreen)
        val etPinOverride = overlayView?.findViewById<EditText>(R.id.etPinOverride)
        val tvInstructions = overlayView?.findViewById<TextView>(R.id.tvInstructions)

        btnUnlockScreen?.setOnClickListener {
            val enteredPin = etPinOverride?.text.toString()

            if (enteredPin == "0000") {
                // THE 0000 TEST OVERRIDE: Unlock instantly
                prefs.edit()
                    .putBoolean("is_system_locked", false)
                    .putBoolean("is_auth_in_progress", false)
                    .putInt("current_risk", 0)
                    .apply()

                stopSelf() // Destroys the Red Screen Service
            } else {
                tvInstructions?.text = "To unlock natively:\nTurn off screen & use Fingerprint/Face."
                etPinOverride?.text?.clear()
            }
        }

        overlayView?.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )

        try {
            windowManager.addView(overlayView, layoutParams)
            isOverlayVisible = true

            // SELF-HEALING NETWORK CHECK
            val isAirplaneModeOn = android.provider.Settings.Global.getInt(
                contentResolver,
                android.provider.Settings.Global.AIRPLANE_MODE_ON, 0
            ) != 0

            if (isAirplaneModeOn) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val ghostIntent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
                    ghostIntent.setPackage(packageName)
                    ghostIntent.putExtra("TARGET_SETTING", "ALL")
                    sendBroadcast(ghostIntent)
                }, 1500)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (isOverlayVisible && overlayView != null) {
                windowManager.removeView(overlayView)
                isOverlayVisible = false
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}