package com.example.aisecurity.ui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.aisecurity.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FakeShutdownActivity : AppCompatActivity() {

    private val escapeTimestamps = mutableListOf<Long>()
    private var isDeadStateActive = false
    private var isBootlooping = false
    private var autoCloseJob: Job? = null // 🚨 Timer to close the menu if idling

    private val escapeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action

            if (action == Intent.ACTION_SCREEN_OFF && !isDeadStateActive) {
                exitFakeShutdown()
                return
            }

            if (!isDeadStateActive) return

            if (action == Intent.ACTION_SCREEN_ON) {
                triggerFakeBootSequence()
            }

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
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            window.setDecorFitsSystemWindows(false)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_fake_shutdown)
        hideSystemUI()

        val btnPowerOff = findViewById<LinearLayout>(R.id.btnPowerOff)
        val layoutShuttingDown = findViewById<LinearLayout>(R.id.layoutShuttingDown)

        // 🚨 START THE 8-SECOND AUTO-DESTRUCT TIMER
        // If they don't click anything, the menu vanishes (Fixes the idle popup bug!)
        startAutoCloseTimer()

        btnPowerOff.setOnClickListener {
            autoCloseJob?.cancel() // Stop the timer since they interacted with it
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

    private fun startAutoCloseTimer() {
        autoCloseJob?.cancel()
        autoCloseJob = lifecycleScope.launch {
            delay(8000) // Wait 8 seconds
            if (!isDeadStateActive) {
                exitFakeShutdown() // Silently kill the UI
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isDeadStateActive) {
            hideSystemUI()
            triggerFakeBootSequence()
        }
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
        getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_fake_dead_state", true).apply()

        sendBroadcast(Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST").apply {
            putExtra("TARGET_SETTING", "DEAD_STATE_ON")
        })

        window.decorView.setOnTouchListener { _, _ -> true }
    }

    // ==========================================
    // 🎬 HARDWARE-FORCED FAKE BOOT SEQUENCE
    // ==========================================
    private fun triggerFakeBootSequence() {
        if (isBootlooping) return
        isBootlooping = true

        // 🚨 HARDWARE WAKELOCK (Redmi Fix)
        // This violently forces the screen to turn on at the hardware level, bypassing MIUI's blocks.
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Sentry::FakeBootWake"
            )
            wl.acquire(5000) // Hold the screen on for 5 seconds
        } catch (e: Exception) {
            e.printStackTrace()
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val layoutFakeBoot = findViewById<LinearLayout>(R.id.layoutFakeBoot)
        layoutFakeBoot.visibility = View.VISIBLE

        lifecycleScope.launch {
            delay(4500)

            layoutFakeBoot.visibility = View.GONE
            isBootlooping = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        } else if (isDeadStateActive) {
            try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (e: Exception) {}
            sendBroadcast(Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST").apply {
                putExtra("TARGET_SETTING", "SLAM_SHADE")
            })
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
        }
    }

    private fun exitFakeShutdown() {
        getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_fake_dead_state", false).apply()
        sendBroadcast(Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST").apply {
            putExtra("TARGET_SETTING", "DEAD_STATE_OFF")
        })
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        autoCloseJob?.cancel()
        getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_fake_dead_state", false).apply()
        sendBroadcast(Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST").apply {
            putExtra("TARGET_SETTING", "DEAD_STATE_OFF")
        })
        try {
            unregisterReceiver(escapeReceiver)
        } catch (e: Exception) { e.printStackTrace() }
    }
}