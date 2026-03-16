package com.example.aisecurity.ui.dashboard

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.aisecurity.R
import com.example.aisecurity.ble.WatchManager // Pulls the live watch status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Grab the NEW IDs from your sleek updated XML
        val tvSystemPercentage = view.findViewById<TextView>(R.id.tvSystemPercentage)
        val tvSystemStatus = view.findViewById<TextView>(R.id.tvSystemStatus)
        val tvStatusAi = view.findViewById<TextView>(R.id.tvStatusAi)
        val tvStatusWatch = view.findViewById<TextView>(R.id.tvStatusWatch)
        val tvStatusSms = view.findViewById<TextView>(R.id.tvStatusSms)

        // 2. The Live Updating Dashboard Loop
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

                // Read the statuses
                val aiReady = prefs.getBoolean("ai_ready", false)
                val isWatchConnected = WatchManager.isConnected.value == true

                // --- NEW: Read the JSON array instead of the old string ---
                val trustedContactsJson = prefs.getString("trusted_contacts_json", "[]") ?: "[]"
                val hasContacts = trustedContactsJson != "[]" && trustedContactsJson.length > 2

                withContext(Dispatchers.Main) {
                    var score = 0

                    // --- MODULE 1: AI ---
                    if (aiReady) {
                        tvStatusAi.text = "Active & Monitoring"
                        tvStatusAi.setTextColor(Color.parseColor("#4CAF50")) // Green
                        score += 34 // Extra 1% so it totals exactly 100
                    } else {
                        tvStatusAi.text = "Training Phase"
                        tvStatusAi.setTextColor(Color.parseColor("#888888")) // Gray
                    }

                    // --- MODULE 2: WATCH ---
                    if (isWatchConnected) {
                        tvStatusWatch.text = "Secure Link Active"
                        tvStatusWatch.setTextColor(Color.parseColor("#4CAF50")) // Green
                        score += 33
                    } else {
                        tvStatusWatch.text = "Disconnected"
                        tvStatusWatch.setTextColor(Color.parseColor("#888888")) // Gray
                    }

                    // --- MODULE 3: SMS TRACKER (UPDATED) ---
                    if (hasContacts) {
                        tvStatusSms.text = "Target Locked"
                        tvStatusSms.setTextColor(Color.parseColor("#4CAF50")) // Green
                        score += 33
                    } else {
                        tvStatusSms.text = "No Target Set"
                        tvStatusSms.setTextColor(Color.parseColor("#888888")) // Gray
                    }

                    // --- UPDATE MASTER SCORE ---
                    tvSystemPercentage.text = "$score%"

                    // Update the master title based on the score
                    when (score) {
                        100 -> {
                            tvSystemStatus.text = "SYSTEM FULLY SECURED"
                            tvSystemStatus.setTextColor(Color.parseColor("#4CAF50"))
                        }
                        0 -> {
                            tvSystemStatus.text = "SYSTEM VULNERABLE"
                            tvSystemStatus.setTextColor(Color.parseColor("#F44336")) // Red
                        }
                        else -> {
                            tvSystemStatus.text = "SYSTEM SETUP INCOMPLETE"
                            tvSystemStatus.setTextColor(Color.parseColor("#FF9800")) // Orange
                        }
                    }
                }
                delay(1000) // Refresh the dashboard every 1 second
            }
        }
    }
}