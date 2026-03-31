package com.example.aisecurity.ui.biometrics

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R
import com.example.aisecurity.ai.BehavioralAuthClassifier
import com.example.aisecurity.ai.SecurityDatabase
import com.example.aisecurity.ui.AppUsageAdapter
import com.example.aisecurity.ui.LiveLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BiometricsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_biometrics, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = SecurityDatabase.get(requireContext())
        val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        // --- UI ELEMENTS ---
        val btnAction = view.findViewById<Button>(R.id.btnAction)
        val btnReset = view.findViewById<Button>(R.id.btnReset)
        val progressBar = view.findViewById<ProgressBar>(R.id.linearProgress)
        val tvScore = view.findViewById<TextView>(R.id.tvScore)
        val tvRiskScore = view.findViewById<TextView>(R.id.tvRiskScore)
        val tvStatusLabel = view.findViewById<TextView>(R.id.tvStatusLabel)

        // --- NEW: APP FLOW ELEMENTS ---
        val tvPreviousApp = view.findViewById<TextView>(R.id.tvPreviousApp)
        val tvCurrentApp = view.findViewById<TextView>(R.id.tvCurrentApp)

        // --- SETUP RECYCLER VIEW ---
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerAppStats)
        val adapter = AppUsageAdapter()
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // --- CONNECT THE NEW APP FLOW TRACKER ---
        LiveLogger.logData.observe(viewLifecycleOwner) { logText ->
            // Intercept the explicitly formatted FLOW string from the Service
            if (logText.contains("📱 FLOW:")) {
                // Splits the string: "📱 FLOW: Home Screen -> AI Security"
                val flowPart = logText.substringAfter("FLOW:").trim()
                val apps = flowPart.split("->")

                if (apps.size == 2) {
                    val previous = apps[0].trim()
                    val current = apps[1].trim()

                    // Directly assign exactly what the Service tells us
                    tvPreviousApp.text = previous
                    tvCurrentApp.text = current
                }
            }
        }

        // ==========================================
        // 1. STOP / CONTINUE TRAINING BUTTON
        // ==========================================
        btnAction.setOnClickListener {
            val isReady = prefs.getBoolean("ai_ready", false)
            if (!isReady) {
                val isCurrentlyPaused = prefs.getBoolean("training_paused", false)
                prefs.edit().putBoolean("training_paused", !isCurrentlyPaused).apply()

                if (!isCurrentlyPaused) {
                    Toast.makeText(requireContext(), "Training Paused", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Training Resumed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 2. Setup RESET Button (TOTAL FACTORY RESET)
        btnReset.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                db.dao().wipeTotalData()

                val editor = prefs.edit()
                editor.clear()
                editor.putBoolean("training_paused", false)
                editor.apply()

                LiveLogger.clear()

                val classifier = BehavioralAuthClassifier(requireContext())
                classifier.wipeMemory()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = 0
                    tvScore.text = "Training: 0/50 Swipes"
                    tvRiskScore.text = "0%"
                    tvRiskScore.setTextColor(Color.GRAY)
                    tvStatusLabel.text = "MEMORY WIPED"
                    tvStatusLabel.setTextColor(Color.GRAY)

                    // Clean the UI up perfectly
                    tvPreviousApp.text = "Home Screen"
                    tvCurrentApp.text = "Monitoring..."

                    adapter.updateData(emptyList())

                    btnAction.visibility = View.VISIBLE

                    Toast.makeText(requireContext(), "AI Fully Reset!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 3. THE LIVE DASHBOARD LOOP
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                val swipes = db.dao().getTrainingData().size
                val appStats = db.dao().getAllAppStats()
                val isReady = prefs.getBoolean("ai_ready", false)
                val currentRisk = prefs.getInt("current_risk", 0)
                val isPaused = prefs.getBoolean("training_paused", false)

                withContext(Dispatchers.Main) {
                    adapter.updateData(appStats)

                    if (isReady) {
                        progressBar.visibility = View.GONE
                        btnAction.visibility = View.GONE
                        tvScore.text = "Protection Active"
                        tvRiskScore.text = "$currentRisk%"

                        when {
                            currentRisk < 50 -> {
                                tvRiskScore.setTextColor(Color.parseColor("#4CAF50"))
                                tvStatusLabel.text = "SYSTEM SECURE"
                                tvStatusLabel.setTextColor(Color.parseColor("#4CAF50"))
                            }
                            currentRisk < 100 -> {
                                tvRiskScore.setTextColor(Color.parseColor("#FF9800"))
                                tvStatusLabel.text = "SUSPICIOUS ACTIVITY"
                                tvStatusLabel.setTextColor(Color.parseColor("#FF9800"))
                            }
                            else -> {
                                tvRiskScore.setTextColor(Color.parseColor("#F44336"))
                                tvStatusLabel.text = "INTRUDER ALERT"
                                tvStatusLabel.setTextColor(Color.parseColor("#F44336"))
                            }
                        }
                    } else {
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = swipes
                        tvScore.text = "Training: $swipes/50 Swipes"
                        tvRiskScore.text = "${(swipes * 2)}%"
                        tvRiskScore.setTextColor(Color.DKGRAY)

                        btnAction.visibility = View.VISIBLE

                        if (isPaused) {
                            btnAction.text = "CONTINUE TRAINING"
                            btnAction.setBackgroundColor(Color.parseColor("#2A2A2A"))
                            btnAction.setTextColor(Color.parseColor("#4CAF50"))
                            tvStatusLabel.text = "TRAINING PAUSED"
                            tvStatusLabel.setTextColor(Color.parseColor("#FF9800"))
                        } else {
                            btnAction.text = "STOP TRAINING"
                            btnAction.setBackgroundColor(Color.parseColor("#2A2A2A"))
                            btnAction.setTextColor(Color.parseColor("#FF9800"))
                            tvStatusLabel.text = "GATHERING DATA..."
                            tvStatusLabel.setTextColor(Color.GRAY)
                        }

                        if (swipes >= 50 && !isPaused) activateAI(db)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun activateAI(db: SecurityDatabase) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val data = db.dao().getTrainingData()
            val classifier = BehavioralAuthClassifier(requireContext())

            val errors = data.map {
                classifier.getError(floatArrayOf(it.duration, it.velocityX, 0.5f, 0.5f, 0.5f))
            }

            val newThreshold = BehavioralAuthClassifier.calculateThreshold(errors)

            val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).edit()
            prefs.putFloat("threshold", newThreshold)
            prefs.putBoolean("ai_ready", true)
            prefs.apply()
        }
    }
}