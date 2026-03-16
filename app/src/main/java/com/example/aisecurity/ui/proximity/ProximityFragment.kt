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
        val tvDistance = view.findViewById<TextView>(R.id.tvDistance) // Grab the big text!

        // 1. LISTEN FOR CONNECTION STATUS
        WatchManager.liveStatus.observe(viewLifecycleOwner) { status ->
            if (status.contains("Secure Link Established", ignoreCase = true) ||
                status.contains("Connected", ignoreCase = true)) {
                tvScanStatus.text = "Connected"
                tvScanStatus.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                tvScanStatus.text = status
                tvScanStatus.setTextColor(Color.parseColor("#888888"))

                // If disconnected, reset the distance to zero
                tvDistance.text = "-- m"
            }
        }

        // 2. LISTEN FOR LIVE DISTANCE UPDATES
        WatchManager.liveDistance.observe(viewLifecycleOwner) { distance ->
            // Update the big green numbers!
            tvDistance.text = "$distance m"
            tvDistance.setTextColor(Color.parseColor("#8BC34A")) // Light green color
        }
    }
}