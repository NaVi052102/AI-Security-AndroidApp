package com.example.aisecurity.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.aisecurity.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FakeShutdownActivity : AppCompatActivity() {

    private val escapeTimestamps = mutableListOf<Long>()
    private var isDeadStateActive = false

    private var topBlocker: View? = null
    private var bottomBlocker: View? = null
    private var wm: WindowManager? = null

    private val escapeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isDeadStateActive) return

            val action = intent?.action
            if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_POWER_CONNECTED) {
                val now = System.currentTimeMillis()
                escapeTimestamps.add(now)

                escapeTimestamps.removeAll { now - it > 10000 }

                if (escapeTimestamps.size >= 3) {
                    Toast.makeText(context, "System Restored. Fake Shutdown Deactivated.", Toast.LENGTH_LONG).show()
                    exitFakeShutdown()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_fake_shutdown)
        hideSystemUI()

        val btnPowerOff = findViewById<LinearLayout>(R.id.btnPowerOff)
        val layoutShuttingDown = findViewById<LinearLayout>(R.id.layoutShuttingDown)

        btnPowerOff.setOnClickListener {
            triggerShutdownAnimation(btnPowerOff, layoutShuttingDown)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { }
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(escapeReceiver, filter)
    }

    private fun triggerShutdownAnimation(btnPowerOff: LinearLayout, layoutShuttingDown: LinearLayout) {
        btnPowerOff.visibility = View.GONE
        findViewById<View>(android.R.id.content).setBackgroundColor(Color.BLACK)
        window.decorView.setBackgroundColor(Color.BLACK)
        layoutShuttingDown.visibility = View.VISIBLE

        lifecycleScope.launch {
            delay(2500)
            layoutShuttingDown.visibility = View.GONE
            activateDeadStateTraps()
        }
    }

    private fun activateDeadStateTraps() {
        isDeadStateActive = true

        // 🚨 TELL THE AI WE ARE IN DEAD STATE SO IT BLOCKS THE STATUS BAR
        getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_fake_dead_state", true).apply()

        deployEdgeBlockers()

        // 🚨 BLIND TOUCH CONSUMPTION
        // We return true for every touch so the user can't tap anything behind the black screen.
        // The 5-tap escape hatch has been completely removed.
        window.decorView.setOnTouchListener { _, _ -> true }
    }

    // 🚨 FAILSAFE: If the status bar is pulled, the Activity loses focus. Instantly slam it shut!
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        } else if (isDeadStateActive) {
            try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (e: Exception) {}
            // Send command to Accessibility Service to force-close the notification shade
            sendBroadcast(Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST").apply {
                putExtra("TARGET_SETTING", "SLAM_SHADE")
            })
        }
    }

    private fun deployEdgeBlockers() {
        if (!Settings.canDrawOverlays(this)) return

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Tall enough to cover any status bar pull gesture on any device
        val topBlockerHeight = 350

        topBlocker = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, _ -> true }
        }
        val topParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            topBlockerHeight,
            layoutFlag,
            // ✅ Removed FLAG_NOT_TOUCH_MODAL — it was letting swipe-from-edge slip through
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSPARENT
        ).apply { gravity = Gravity.TOP }

        bottomBlocker = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, _ -> true }
        }
        val bottomParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            topBlockerHeight,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSPARENT
        ).apply { gravity = Gravity.BOTTOM }

        try {
            wm?.addView(topBlocker, topParams)
            wm?.addView(bottomBlocker, bottomParams)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun removeEdgeBlockers() {
        try {
            topBlocker?.let { wm?.removeView(it) }
            bottomBlocker?.let { wm?.removeView(it) }
            topBlocker = null
            bottomBlocker = null
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
    }

    private fun exitFakeShutdown() {
        // TURN OFF THE AI TRAP FLAG
        getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_fake_dead_state", false).apply()
        removeEdgeBlockers()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_fake_dead_state", false).apply()
        removeEdgeBlockers()
        try {
            unregisterReceiver(escapeReceiver)
        } catch (e: Exception) { e.printStackTrace() }
    }
}