package com.example.aisecurity.ui.biometrics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.aisecurity.R

class BiometricsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_biometrics, container, false)

        val tvTrainingPercentage = view.findViewById<TextView>(R.id.tvTrainingPercentage)
        val tvTrainingProgress = view.findViewById<TextView>(R.id.tvTrainingProgress)
        val btnStopTraining = view.findViewById<Button>(R.id.btnStopTraining)
        val btnResetAi = view.findViewById<Button>(R.id.btnResetAi)

        // TODO: Connect this to your actual AI Swipe Counter logic
        // For now, setting default visual states so it compiles safely
        tvTrainingPercentage.text = "0%"
        tvTrainingProgress.text = "Training: 0 / 50 Swipes"

        btnStopTraining.setOnClickListener {
            Toast.makeText(requireContext(), "AI Training Paused", Toast.LENGTH_SHORT).show()
        }

        btnResetAi.setOnClickListener {
            Toast.makeText(requireContext(), "AI Memory Purged", Toast.LENGTH_SHORT).show()
            tvTrainingPercentage.text = "0%"
            tvTrainingProgress.text = "Training: 0 / 50 Swipes"
        }

        return view
    }
}