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
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class BiometricsFragment : Fragment() {

    // 3 Days in Milliseconds
    private val TRAINING_DURATION_MS = TimeUnit.DAYS.toMillis(3)
    // Minimum swipes required before AI can activate (to prevent activation with no data)
    private val MIN_REQUIRED_SWIPES = 200

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

        // UI Elements
        val btnAction = view.findViewById<Button>(R.id.btnAction)
        val btnReset = view.findViewById<Button>(R.id.btnReset)
        val progressBar = view.findViewById<ProgressBar>(R.id.linearProgress)
        val tvScore = view.findViewById<TextView>(R.id.tvScore)
        val tvRiskScore = view.findViewById<TextView>(R.id.tvRiskScore)
        val tvStatusLabel = view.findViewById<TextView>(R.id.tvStatusLabel)
        val tvPreviousApp = view.findViewById<TextView>(R.id.tvPreviousApp)
        val tvCurrentApp = view.findViewById<TextView>(R.id.tvCurrentApp)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerAppStats)
        val adapter = AppUsageAdapter()
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // App Flow Observer
        LiveLogger.logData.observe(viewLifecycleOwner) { logText ->
            if (logText.contains("📱 FLOW:")) {
                val flowPart = logText.substringAfter("FLOW:").trim()
                val apps = flowPart.split("->")
                if (apps.size == 2) {
                    tvPreviousApp.text = apps[0].trim()
                    tvCurrentApp.text = apps[1].trim()
                }
            }
        }

        // Action Button: Pause/Resume Training
        btnAction.setOnClickListener {
            val isReady = prefs.getBoolean("ai_ready", false)
            if (!isReady) {
                val isCurrentlyPaused = prefs.getBoolean("training_paused", false)
                prefs.edit().putBoolean("training_paused", !isCurrentlyPaused).apply()
                val msg = if (!isCurrentlyPaused) "Training Paused" else "Training Resumed"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        // Reset Button: Full Wipe
        btnReset.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                db.dao().wipeTotalData()
                prefs.edit().clear().apply()
                LiveLogger.clear()
                val classifier = BehavioralAuthClassifier(requireContext())
                classifier.wipeMemory()

                withContext(Dispatchers.Main) {
                    tvStatusLabel.text = "MEMORY WIPED"
                    tvStatusLabel.setTextColor(Color.GRAY)
                    tvRiskScore.text = "0%"
                    adapter.updateData(emptyList())
                    Toast.makeText(requireContext(), "AI Fully Reset!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // MAIN DASHBOARD LOOP (Runs every 1 second)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val swipes = db.dao().getTrainingData().size
                val appStats = db.dao().getAllAppStats()
                val isReady = prefs.getBoolean("ai_ready", false)
                val currentRisk = prefs.getInt("current_risk", 0)
                val isPaused = prefs.getBoolean("training_paused", false)

                // Track Training Time
                var startTime = prefs.getLong("training_start_time", 0L)
                if (startTime == 0L && swipes > 0) {
                    startTime = System.currentTimeMillis()
                    prefs.edit().putLong("training_start_time", startTime).apply()
                }

                val elapsed = System.currentTimeMillis() - startTime
                val timeProgress = if (startTime == 0L) 0 else ((elapsed.toFloat() / TRAINING_DURATION_MS) * 100).toInt().coerceIn(0, 100)

                withContext(Dispatchers.Main) {
                    adapter.updateData(appStats)

                    if (isReady) {
                        renderProtectionMode(tvScore, tvRiskScore, tvStatusLabel, progressBar, btnAction, currentRisk)
                    } else {
                        renderTrainingMode(tvScore, tvRiskScore, tvStatusLabel, progressBar, btnAction,
                            timeProgress, elapsed, isPaused, swipes)

                        // AUTO-ACTIVATE CHECK
                        if (elapsed >= TRAINING_DURATION_MS && swipes >= MIN_REQUIRED_SWIPES && !isPaused) {
                            activateAI(db)
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    private fun renderProtectionMode(tvScore: TextView, tvRisk: TextView, tvStatus: TextView, pb: ProgressBar, btn: Button, risk: Int) {
        pb.visibility = View.GONE
        btn.visibility = View.GONE
        tvScore.text = "Continuous Protection Active"
        tvRisk.text = "$risk%"

        when {
            risk < 50 -> {
                tvRisk.setTextColor(Color.parseColor("#4CAF50"))
                tvStatus.text = "SYSTEM SECURE"
                tvStatus.setTextColor(Color.parseColor("#4CAF50"))
            }
            risk < 100 -> {
                tvRisk.setTextColor(Color.parseColor("#FF9800"))
                tvStatus.text = "SUSPICIOUS ACTIVITY"
                tvStatus.setTextColor(Color.parseColor("#FF9800"))
            }
            else -> {
                tvRisk.setTextColor(Color.parseColor("#F44336"))
                tvStatus.text = "INTRUDER ALERT"
                tvStatus.setTextColor(Color.parseColor("#F44336"))
            }
        }
    }

    private fun renderTrainingMode(tvScore: TextView, tvRisk: TextView, tvStatus: TextView, pb: ProgressBar, btn: Button,
                                   progress: Int, elapsed: Long, isPaused: Boolean, swipeCount: Int) {
        pb.visibility = View.VISIBLE
        pb.progress = progress

        val remainingMs = (TRAINING_DURATION_MS - elapsed).coerceAtLeast(0)
        val days = TimeUnit.MILLISECONDS.toDays(remainingMs)
        val hours = TimeUnit.MILLISECONDS.toHours(remainingMs) % 24

        tvScore.text = "Calibration: $days d $hours h remaining"
        tvRisk.text = "$swipeCount Swipes"
        tvRisk.setTextColor(Color.DKGRAY)

        if (isPaused) {
            btn.text = "CONTINUE TRAINING"
            tvStatus.text = "TRAINING PAUSED"
            tvStatus.setTextColor(Color.parseColor("#FF9800"))
        } else {
            btn.text = "STOP TRAINING"
            tvStatus.text = "LEARNING BEHAVIOR..."
            tvStatus.setTextColor(Color.GRAY)
        }
    }

    private fun activateAI(db: SecurityDatabase) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val data = db.dao().getTrainingData()
            val classifier = BehavioralAuthClassifier(requireContext())

            // NOTE: Array must match your TFLite features [Velocity, Pressure, Usage, Transition]
            val errors = data.map {
                classifier.getError(floatArrayOf(it.velocityX, 0.5f, 0.1f, 0.1f))
            }

            val newThreshold = BehavioralAuthClassifier.calculateThreshold(errors)

            requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).edit()
                .putFloat("threshold", newThreshold)
                .putBoolean("ai_ready", true)
                .apply()

            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "AI Training Complete!", Toast.LENGTH_LONG).show()
            }
        }
    }
}