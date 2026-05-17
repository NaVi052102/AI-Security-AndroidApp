package com.example.aisecurity.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
    private var autoCloseJob: Job? = null

    private val escapeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action

            if (action == Intent.ACTION_SCREEN_OFF && !isDeadStateActive) {
                exitFakeShutdown()
                return
            }

            if (!isDeadStateActive) return

            // Hidden escape sequence: 3 power actions (screen on/off/power connected) within 10 seconds
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

    @SuppressLint("ClickableViewAccessibility")
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

            // Keep the screen completely black
            findViewById<View>(android.R.id.content).setBackgroundColor(Color.BLACK)
            window.decorView.setBackgroundColor(Color.BLACK)
            return
        }

        setContentView(R.layout.activity_fake_shutdown)
        hideSystemUI()

        val layoutPowerMenu = findViewById<View>(R.id.layoutPowerMenu) // Generic
        val layoutVivoPowerMenu = findViewById<View>(R.id.layoutVivoPowerMenu) // Vivo
        val layoutShuttingDown = findViewById<LinearLayout>(R.id.layoutShuttingDown)

        startAutoCloseTimer()

        // Read the saved style preference
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

            val btnDraggablePower = findViewById<View>(R.id.btnDraggablePower)
            var startY = 0f
            var initialTranslationY = 0f

            val density = resources.displayMetrics.density
            val maxTravel = 90f * density // Bounds to keep it inside the pill
            val triggerThreshold = 60f * density // Point of no return

            btnDraggablePower.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        initialTranslationY = view.translationY
                        autoCloseJob?.cancel() // Pause auto-close while dragging
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - startY
                        var newTransY = initialTranslationY + dy

                        // Clamp the movement to stay inside the grey pill boundary
                        if (newTransY < -maxTravel) newTransY = -maxTravel
                        if (newTransY > maxTravel) newTransY = maxTravel

                        view.translationY = newTransY
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // Check if dragged past the kill threshold (up or down)
                        if (view.translationY <= -triggerThreshold || view.translationY >= triggerThreshold) {
                            triggerShutdownAnimation(activeMenuLayout, layoutShuttingDown)
                        } else {
                            // Let go too early? Snap back to the center with a bounce effect
                            view.animate()
                                .translationY(0f)
                                .setDuration(250)
                                .setInterpolator(OvershootInterpolator(1.2f))
                                .start()

                            startAutoCloseTimer() // Restart timer
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { }
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(escapeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(escapeReceiver, filter)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
        if (isDeadStateActive) {
            hideSystemUI()
            findViewById<View>(android.R.id.content).setBackgroundColor(Color.BLACK)
            window.decorView.setBackgroundColor(Color.BLACK)
        }
    }

    private fun triggerShutdownAnimation(layoutPowerMenu: View, layoutShuttingDown: LinearLayout) {
        // Secretly trigger the hidden front camera capture with NO animation
        try {
            val photoIntent = Intent(this, com.example.aisecurity.ui.HiddenCameraActivity::class.java).apply {
                putExtra("CAMERA_TYPE", "FRONT")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            startActivity(photoIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

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
                    delay(6000)

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

        // Capture all touches to prevent interaction
        window.decorView.setOnTouchListener { _, _ -> true }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        } else if (isDeadStateActive) {
            try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (e: Exception) {}
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