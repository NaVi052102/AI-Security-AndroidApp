package com.example.aisecurity.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.aisecurity.R
import com.example.aisecurity.ui.LiveLogger

class HoneypotActivity : AppCompatActivity() {

    // 1. Create the secret listener that waits for the rescue command
    private val rescueReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.aisecurity.ACTION_RESCUE") {
                LiveLogger.log("🔓 RESCUE COMMAND RECEIVED: Unpinning screen...")
                stopLockTask() // Kills the screen pinning
                finish()       // Closes the warning screen
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_honeypot)

        LiveLogger.log("🚨 WARNING SCREEN: Thief trapped in pinned display.")

        // 2. Register the listener while the screen is alive
        // (Note: RECEIVER_EXPORTED is required for Android 13/14 compatibility)
        registerReceiver(rescueReceiver, IntentFilter("com.example.aisecurity.ACTION_RESCUE"), Context.RECEIVER_EXPORTED)

        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val customMsg = prefs.getString("warning_msg", "This device has been lost or stolen.")
        val customNum = prefs.getString("contact_num", "No number provided.")

        findViewById<TextView>(R.id.tvDisplayMessage).text = customMsg
        findViewById<TextView>(R.id.tvDisplayNumber).text = customNum

        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // ENGAGE THE TRAP
        startLockTask()
    }

    override fun onBackPressed() {
        // Trap them!
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up the listener when the screen closes
        unregisterReceiver(rescueReceiver)
    }
}