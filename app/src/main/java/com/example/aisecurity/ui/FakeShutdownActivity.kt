package com.example.aisecurity.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
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
import android.view.animation.AccelerateDecelerateInterpolator
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
    private var autoCloseJob: Job? = null

    private val escapeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action

            if (action == "com.example.aisecurity.TRIGGER_FAKE_BOOT" && isDeadStateActive) {
                forceBringToFrontAndBoot(context)
                return
            }

            if (action == Intent.ACTION_SCREEN_OFF && !isDeadStateActive) {
                exitFakeShutdown()
                return
            }

            if (!isDeadStateActive) return

            if (action == Intent.ACTION_SCREEN_ON) {
                forceBringToFrontAndBoot(context)
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
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            window.setDecorFitsSystemWindows(false)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("is_fake_dead_state", false)) {
            isDeadStateActive = true
            setContentView(R.layout.activity_fake_shutdown)
            hideSystemUI()
            triggerFakeBootSequence()
            return
        }

        setContentView(R.layout.activity_fake_shutdown)
        hideSystemUI()

        val layoutPowerMenu = findViewById<View>(R.id.layoutPowerMenu) // Generic
        val layoutVivoPowerMenu = findViewById<View>(R.id.layoutVivoPowerMenu) // Vivo
        val layoutShuttingDown = findViewById<LinearLayout>(R.id.layoutShuttingDown)

        startAutoCloseTimer()

        // 🚨 NEW: Read the saved style preference
        val savedStyle = prefs.getString("fake_shutdown_style", "Xiaomi / Generic")

        val activeMenuLayout: View

        if (savedStyle == "Vivo V40 Lite") {
            layoutPowerMenu.visibility = View.GONE
            layoutVivoPowerMenu.visibility = View.VISIBLE
            activeMenuLayout = layoutVivoPowerMenu

            val btnVivoPowerOff = findViewById<View>(R.id.btnVivoPowerOff)
            val btnVivoRestart = findViewById<View>(R.id.btnVivoRestart)

            val clickListener = View.OnClickListener {
                autoCloseJob?.cancel()
                triggerShutdownAnimation(activeMenuLayout, layoutShuttingDown)
            }
            btnVivoPowerOff.setOnClickListener(clickListener)
            btnVivoRestart.setOnClickListener(clickListener)

        } else {
            // Default Xiaomi / Generic
            layoutPowerMenu.visibility = View.VISIBLE
            layoutVivoPowerMenu.visibility = View.GONE
            activeMenuLayout = layoutPowerMenu

            val btnPowerOff = findViewById<View>(R.id.btnPowerOff)
            val btnRestart = findViewById<View>(R.id.btnRestart)

            val clickListener = View.OnClickListener {
                autoCloseJob?.cancel()
                triggerShutdownAnimation(activeMenuLayout, layoutShuttingDown)
            }
            btnPowerOff.setOnClickListener(clickListener)
            btnRestart.setOnClickListener(clickListener)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { }
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction("com.example.aisecurity.TRIGGER_FAKE_BOOT")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(escapeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(escapeReceiver, filter)
        }
    }

    // 🚨 UPDATE THE ANIMATION FUNCTION TO ACCEPT 'View' INSTEAD OF 'LinearLayout'
    private fun triggerShutdownAnimation(layoutPowerMenu: View, layoutShuttingDown: LinearLayout) {
        layoutPowerMenu.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                layoutPowerMenu.visibility = View.GONE

                findViewById<View>(android.R.id.content).setBackgroundColor(Color.BLACK)
                window.decorView.setBackgroundColor(Color.BLACK)

                layoutShuttingDown.alpha = 0f
                layoutShuttingDown.visibility = View.VISIBLE
                layoutShuttingDown.animate().alpha(1f).setDuration(400).start()

                lifecycleScope.launch {
                    delay(3000)

                    layoutShuttingDown.animate().alpha(0f).setDuration(300).withEndAction {
                        layoutShuttingDown.visibility = View.GONE
                        activateDeadStateTraps()
                    }.start()
                }
            }
            .start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isDeadStateActive) {
            triggerFakeBootSequence()
        }
    }

    private fun forceBringToFrontAndBoot(context: Context?) {
        sendBroadcast(Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST").apply {
            putExtra("TARGET_SETTING", "DEAD_STATE_OFF")
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }

        val bringToFrontIntent = Intent(context, FakeShutdownActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("START_BOOT_SEQUENCE", true)
        }
        context?.startActivity(bringToFrontIntent)
    }

    private fun startAutoCloseTimer() {
        autoCloseJob?.cancel()
        autoCloseJob = lifecycleScope.launch {
            delay(15000)
            if (!isDeadStateActive) {
                exitFakeShutdown()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isDeadStateActive && !isBootlooping) {
            hideSystemUI()
            triggerFakeBootSequence()
        }
    }

    private fun triggerShutdownAnimation(layoutPowerMenu: LinearLayout, layoutShuttingDown: LinearLayout) {
        layoutPowerMenu.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                layoutPowerMenu.visibility = View.GONE

                findViewById<View>(android.R.id.content).setBackgroundColor(Color.BLACK)
                window.decorView.setBackgroundColor(Color.BLACK)

                layoutShuttingDown.alpha = 0f
                layoutShuttingDown.visibility = View.VISIBLE
                layoutShuttingDown.animate().alpha(1f).setDuration(400).start()

                lifecycleScope.launch {
                    delay(3000)

                    layoutShuttingDown.animate().alpha(0f).setDuration(300).withEndAction {
                        layoutShuttingDown.visibility = View.GONE
                        activateDeadStateTraps()
                    }.start()
                }
            }
            .start()
    }

    private fun activateDeadStateTraps() {
        isDeadStateActive = true
        getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_fake_dead_state", true).apply()

        sendBroadcast(Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST").apply {
            putExtra("TARGET_SETTING", "DEAD_STATE_ON")
        })

        window.decorView.setOnTouchListener { _, _ -> true }
    }

    private fun triggerFakeBootSequence() {
        if (isBootlooping) return
        isBootlooping = true

        sendBroadcast(Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST").apply {
            putExtra("TARGET_SETTING", "DEAD_STATE_OFF")
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Sentry::FakeBootWake"
            )
            wl.acquire(6000)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val layoutFakeBoot = findViewById<View>(R.id.layoutFakeBoot)
        val ivBootLogo = findViewById<View>(R.id.ivBootLogo)

        layoutFakeBoot.alpha = 0f
        layoutFakeBoot.visibility = View.VISIBLE
        layoutFakeBoot.animate().alpha(1f).setDuration(500).start()

        val pulseAnimation = ObjectAnimator.ofPropertyValuesHolder(
            ivBootLogo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.15f, 1.0f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.15f, 1.0f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 1.0f, 0.7f)
        ).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        pulseAnimation.start()

        lifecycleScope.launch {
            delay(5000)

            layoutFakeBoot.animate().alpha(0f).setDuration(400).withEndAction {
                pulseAnimation.cancel()
                layoutFakeBoot.visibility = View.GONE
                isBootlooping = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                exitFakeShutdown()
            }.start()
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