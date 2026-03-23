package com.example.aisecurity.ui

import android.content.Context
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.aisecurity.R
import com.example.aisecurity.ui.LiveLogger

class HoneypotActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_honeypot)

        LiveLogger.log("🚨 WARNING SCREEN: Thief trapped in pinned display.")

        // 1. Load the custom recovery text the user saved in Settings
        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val customMsg = prefs.getString("warning_msg", "This device has been lost or stolen.")
        val customNum = prefs.getString("contact_num", "No number provided.")

        findViewById<TextView>(R.id.tvDisplayMessage).text = customMsg
        findViewById<TextView>(R.id.tvDisplayNumber).text = customNum

        // 2. Hide the Status/Navigation Bars to prevent escape
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 3. ENGAGE THE TRAP (Pin the screen)
        startLockTask()
    }

    override fun onBackPressed() {
        // Do nothing. Trap them.
    }
}