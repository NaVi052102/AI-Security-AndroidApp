package com.example.aisecurity.ui.proximity

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.aisecurity.R

class ProximityFragment : Fragment() {

    private lateinit var tvLiveDistance: TextView
    private lateinit var tvWatchStatus: TextView
    private lateinit var tvConnectionState: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_proximity, container, false)

        tvLiveDistance = view.findViewById(R.id.tvLiveDistance)
        tvWatchStatus = view.findViewById(R.id.tvWatchStatus)
        tvConnectionState = view.findViewById(R.id.tvConnectionState)

        val btnPulseRadar = view.findViewById<Button>(R.id.btnPulseRadar)

        // Simulate a radar scan when the user taps the button
        btnPulseRadar.setOnClickListener {
            simulateRadarPulse(btnPulseRadar)
        }

        return view
    }

    private fun simulateRadarPulse(button: Button) {
        button.isEnabled = false
        button.text = "SCANNING FREQUENCIES..."
        tvConnectionState.text = "Pinging paired devices..."
        tvConnectionState.setTextColor(android.graphics.Color.parseColor("#007AFF")) // Tech Blue

        // Simulate a 2-second network/bluetooth delay
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAdded) { // Ensure fragment is still alive
                button.isEnabled = true
                button.text = "PULSE RADAR"

                // For now, default back to waiting state (until we build the real BLE logic)
                tvConnectionState.text = "Radar Pulse Complete. No changes."
                tvConnectionState.setTextColor(resources.getColor(R.color.text_muted, null))
            }
        }, 2000)
    }
}