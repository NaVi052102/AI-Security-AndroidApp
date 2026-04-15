package com.example.aisecurity.ui.proximity

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.ble.WatchManager

class ProximityFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_proximity, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvScanStatus = view.findViewById<TextView>(R.id.tvScanStatus)
        val tvDistance = view.findViewById<TextView>(R.id.tvDistance)
        val tvHeartRate = view.findViewById<TextView>(R.id.tvHeartRate)
        val tvSecurityStatus = view.findViewById<TextView>(R.id.tvSecurityStatus)
        val tvTouchStatus = view.findViewById<TextView>(R.id.tvTouchStatus)

        // 1. LISTEN FOR CONNECTION STATUS
        WatchManager.liveStatus.observe(viewLifecycleOwner) { status ->

            // THE FIX: Check for "Disconnected" first so it doesn't accidentally match "Connected"!
            if (status.contains("Disconnected", ignoreCase = true) || status.contains("Lost", ignoreCase = true)) {
                tvScanStatus.text = "Disconnected"
                tvScanStatus.setTextColor(Color.parseColor("#EF4444")) // Threat Red

                // Reset UI to neutral when no watch is present
                tvDistance.text = "-- m"
                tvDistance.setTextColor(Color.parseColor("#94A3B8"))
                tvHeartRate.text = "-- bpm"
                tvSecurityStatus.text = "DISCONNECTED"
                tvSecurityStatus.setTextColor(Color.parseColor("#EF4444"))
            }
            else if (status.contains("Secure Link Established", ignoreCase = true) ||
                status.contains("Connected", ignoreCase = true)) {
                tvScanStatus.text = "Connected & Secured"
                tvScanStatus.setTextColor(Color.parseColor("#10B981")) // Emerald Green
            }
            else {
                // For transitional states like "Scanning..." or "Connecting..."
                tvScanStatus.text = status
                tvScanStatus.setTextColor(Color.parseColor("#94A3B8")) // Muted Slate
            }
        }

        // 2. LISTEN FOR LIVE DISTANCE & APPLY DYNAMIC THREAT COLORING
        WatchManager.liveDistance.observe(viewLifecycleOwner) { distanceStr ->
            tvDistance.text = "$distanceStr m"

            try {
                val distance = distanceStr.toFloat()
                when {
                    distance <= 3.0f -> {
                        // Safe Range: Emerald Green
                        tvDistance.setTextColor(Color.parseColor("#10B981"))
                    }
                    distance <= 8.0f -> {
                        // Warning Range: Amber/Orange
                        tvDistance.setTextColor(Color.parseColor("#F59E0B"))
                    }
                    else -> {
                        // Danger Range: Threat Red
                        tvDistance.setTextColor(Color.parseColor("#EF4444"))
                    }
                }
            } catch (e: NumberFormatException) {
                tvDistance.setTextColor(Color.parseColor("#10B981"))
            }
        }

        /* ==================================================================
        DEVELOPER NOTE:
        Uncomment the blocks below ONLY AFTER you have added the following
        variables to your WatchManager.kt file:

        val liveHeartRate = MutableLiveData<String>()
        val wristStatus = MutableLiveData<String>()
        val touchStatus = MutableLiveData<String>()
        ==================================================================
        */

        /*
        // 3. LISTEN FOR LIVE HEART RATE
        WatchManager.liveHeartRate.observe(viewLifecycleOwner) { hr ->
            tvHeartRate.text = "$hr bpm"
        }

        // 4. LISTEN FOR LIVE WRIST STATUS
        WatchManager.wristStatus.observe(viewLifecycleOwner) { wrist ->
            val wristStr = wrist.toString()
            tvSecurityStatus.text = wristStr.uppercase()
            if (wristStr.contains("ON WRIST", ignoreCase = true)) {
                tvSecurityStatus.setTextColor(Color.parseColor("#10B981")) // Green
            } else {
                tvSecurityStatus.setTextColor(Color.parseColor("#EF4444")) // Red
            }
        }

        // 5. LISTEN FOR LIVE TOUCH SENSOR STATUS
        WatchManager.touchStatus.observe(viewLifecycleOwner) { touch ->
            tvTouchStatus.text = touch.toString()
        }
        */
    }
}