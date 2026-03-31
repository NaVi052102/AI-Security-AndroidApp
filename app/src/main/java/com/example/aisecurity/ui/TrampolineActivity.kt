package com.example.aisecurity.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class TrampolineActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The millisecond this activity is created, it finishes itself.
        // This brief flash of existence is enough to force the OS to close the notification shade!
        finish()

        // Use zero-latency animations so the thief doesn't see a screen flicker
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}