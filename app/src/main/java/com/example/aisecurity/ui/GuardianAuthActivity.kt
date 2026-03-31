package com.example.aisecurity.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class GuardianAuthActivity : AppCompatActivity() {

    private val AUTH_REQUEST_CODE = 101
    private var isAuthPending = false
    private var hasTriggeredLockdown = false

    // The Timebomb Scope
    private val activityScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            isAuthPending = savedInstanceState.getBoolean("isAuthPending", false)
        }

        if (!isAuthPending) {
            isAuthPending = true
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

            try {
                if (km.isKeyguardSecure) {
                    val authIntent = km.createConfirmDeviceCredentialIntent(
                        "Guardian Protocol",
                        "Verify identity to disable networks."
                    )
                    if (authIntent != null) {
                        startActivityForResult(authIntent, AUTH_REQUEST_CODE)
                        startTimebomb() // Start the countdown!
                    } else {
                        triggerHostileLockdown()
                    }
                } else {
                    Toast.makeText(this, "No secure lock screen found.", Toast.LENGTH_LONG).show()
                    triggerHostileLockdown()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                triggerHostileLockdown()
            }
        }
    }

    private fun startTimebomb() {
        // The user has exactly 12 seconds to enter the PIN.
        // If they press Home, this timer keeps ticking!
        activityScope.launch {
            delay(12000)
            if (!isFinishing && !hasTriggeredLockdown) {
                Toast.makeText(this@GuardianAuthActivity, "TIME EXPIRED. SECURING DEVICE.", Toast.LENGTH_LONG).show()
                triggerHostileLockdown()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isAuthPending", isAuthPending)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == AUTH_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                activityScope.cancel() // Defuse the timebomb!
                Toast.makeText(this, "Identity Verified. Airplane Mode Allowed.", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                triggerHostileLockdown()
            }
        }
    }

    private fun triggerHostileLockdown() {
        if (hasTriggeredLockdown) return
        hasTriggeredLockdown = true
        activityScope.cancel()

        Toast.makeText(this, "SECURITY BREACH DETECTED", Toast.LENGTH_LONG).show()

        try {
            val lockIntent = Intent(this, LockOverlayService::class.java)
            startService(lockIntent)

            val ghostIntent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
            ghostIntent.setPackage(packageName)
            ghostIntent.putExtra("TARGET_SETTING", "AIRPLANE")
            sendBroadcast(ghostIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel() // Clean up memory
    }
}