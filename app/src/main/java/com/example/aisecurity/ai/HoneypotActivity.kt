package com.example.aisecurity.ai

import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.aisecurity.R
import com.example.aisecurity.ui.LiveLogger

class HoneypotActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_honeypot)

        LiveLogger.log("👻 GHOST MODE: Thief trapped in Honeypot.")

        // 1. Hide the Status Bar and Navigation Bar to make it look like a real home screen
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 2. ENGAGE THE TRAP (Screen Pinning)
        // This disables the Home and Recents buttons.
        startLockTask()
    }

    // 3. Disable the Physical Back Button
    override fun onBackPressed() {
        // Do absolutely nothing. Trap them.
        LiveLogger.log("👻 Thief tried to press BACK. Ignored.")
    }

    // 4. Waste their time
    fun onFakeAppClicked(view: View) {
        // When they click a fake icon, we show a standard Android error
        // to make them think the phone is just lagging or broken.
        Toast.makeText(this, "App isn't responding", Toast.LENGTH_SHORT).show()
        LiveLogger.log("👻 Thief clicked a fake app icon.")

        // TODO for later: Trigger the front camera to snap a secret photo here!
    }
}