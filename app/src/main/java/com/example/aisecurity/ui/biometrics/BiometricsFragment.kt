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

    private val REQUIRED_TRAINING_MS = TimeUnit.DAYS.toMillis(1)
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

        val btnAction = view.findViewById<Button>(R.id.btnAction)
        val btnUseAi = view.findViewById<Button>(R.id.btnUseAi)
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

        if (!prefs.contains("training_paused")) {
            prefs.edit().putBoolean("training_paused", true).apply()
        }

        LiveLogger.logData.observe(viewLifecycleOwner) { logText ->
            val lastFlowLine = logText.split("\n").lastOrNull { it.contains("📱 FLOW:") }
            if (lastFlowLine != null) {
                val flowPart = lastFlowLine.substringAfter("FLOW:").trim()
                val apps = flowPart.split("->")
                if (apps.size == 2) {
                    tvPreviousApp.text = apps[0].trim()
                    tvCurrentApp.text = apps[1].trim()
                }
            }
        }

        btnAction.setOnClickListener {
            val isReady = prefs.getBoolean("ai_ready", false)
            val isPaused = prefs.getBoolean("training_paused", true)
            val editor = prefs.edit()

            if (isReady) {
                editor.putBoolean("ai_ready", false)
                editor.putBoolean("training_paused", false)
                editor.putLong("session_start_time", System.currentTimeMillis())
                Toast.makeText(requireContext(), "Reverting to Training Mode", Toast.LENGTH_SHORT).show()
            } else {
                if (isPaused) {
                    editor.putBoolean("training_paused", false)
                    editor.putLong("session_start_time", System.currentTimeMillis())
                    Toast.makeText(requireContext(), "Training Started", Toast.LENGTH_SHORT).show()
                } else {
                    val sessionStart = prefs.getLong("session_start_time", 0L)
                    var accumulated = prefs.getLong("accumulated_time", 0L)
                    if (sessionStart > 0) {
                        accumulated += (System.currentTimeMillis() - sessionStart)
                    }
                    editor.putLong("accumulated_time", accumulated)
                    editor.putLong("session_start_time", 0L)
                    editor.putBoolean("training_paused", true)
                    Toast.makeText(requireContext(), "Training Paused", Toast.LENGTH_SHORT).show()
                }
            }
            editor.apply()
        }

        btnUseAi.setOnClickListener {
            val sessionStart = prefs.getLong("session_start_time", 0L)
            var accumulated = prefs.getLong("accumulated_time", 0L)

            if (sessionStart > 0) accumulated += (System.currentTimeMillis() - sessionStart)

            prefs.edit()
                .putLong("accumulated_time", accumulated)
                .putLong("session_start_time", 0L)
                .putBoolean("training_paused", true)
                .apply()

            activateAI(db)
        }

        btnReset.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                db.dao().wipeTotalData()

                prefs.edit()
                    .putBoolean("ai_ready", false)
                    .putBoolean("training_paused", true)
                    .putInt("current_risk", 0)
                    .putLong("accumulated_time", 0L)
                    .putLong("session_start_time", 0L)
                    .remove("threshold")
                    .apply()

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

        // ==========================================
        // 🚨 FIX: IMMORTAL UI UPDATER LOOP
        // ==========================================
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val swipes = db.dao().getTrainingData().size
                    val appStats = db.dao().getAllAppStats()
                    val isReady = prefs.getBoolean("ai_ready", false)
                    val currentRisk = prefs.getInt("current_risk", 0)
                    val isPaused = prefs.getBoolean("training_paused", true)

                    var accumulatedTime = prefs.getLong("accumulated_time", 0L)
                    val sessionStart = prefs.getLong("session_start_time", 0L)

                    if (!isPaused && sessionStart > 0 && !isReady) {
                        accumulatedTime += (System.currentTimeMillis() - sessionStart)
                    }

                    val timeProgress = ((accumulatedTime.toFloat() / REQUIRED_TRAINING_MS) * 100).toInt().coerceIn(0, 100)
                    val canUseAi = accumulatedTime >= REQUIRED_TRAINING_MS && swipes >= MIN_REQUIRED_SWIPES

                    withContext(Dispatchers.Main) {
                        adapter.updateData(appStats)

                        btnUseAi.isEnabled = canUseAi
                        if (canUseAi) {
                            btnUseAi.setBackgroundColor(Color.parseColor("#4CAF50"))
                        } else {
                            btnUseAi.setBackgroundColor(Color.parseColor("#444444"))
                        }

                        if (isReady) {
                            renderProtectionMode(tvScore, tvRiskScore, tvStatusLabel, progressBar, btnAction, btnUseAi, currentRisk)
                        } else {
                            renderTrainingMode(tvScore, tvRiskScore, tvStatusLabel, progressBar, btnAction, btnUseAi,
                                timeProgress, accumulatedTime, isPaused, swipes)
                        }
                    }
                } catch (e: Exception) {
                    // 🚨 SAFETY NET: If the DB wipe collides with this loop, ignore the error and keep going!
                    e.printStackTrace()
                }
                delay(1000)
            }
        }
    }

    private fun renderProtectionMode(tvScore: TextView, tvRisk: TextView, tvStatus: TextView, pb: ProgressBar, btnAction: Button, btnUseAi: Button, risk: Int) {
        pb.visibility = View.GONE
        btnUseAi.visibility = View.GONE

        btnAction.text = "BACK TO TRAINING"
        btnAction.setBackgroundColor(Color.parseColor("#FF9800"))

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

    private fun renderTrainingMode(tvScore: TextView, tvRisk: TextView, tvStatus: TextView, pb: ProgressBar, btnAction: Button, btnUseAi: Button,
                                   progress: Int, accumulated: Long, isPaused: Boolean, swipeCount: Int) {
        pb.visibility = View.VISIBLE
        btnUseAi.visibility = View.VISIBLE
        pb.progress = progress

        val remainingMs = (REQUIRED_TRAINING_MS - accumulated).coerceAtLeast(0)
        val days = TimeUnit.MILLISECONDS.toDays(remainingMs)
        val hours = TimeUnit.MILLISECONDS.toHours(remainingMs) % 24

        tvScore.text = "Calibration: $days d $hours h remaining"
        tvRisk.text = "$swipeCount Swipes"
        tvRisk.setTextColor(Color.DKGRAY)

        if (isPaused) {
            btnAction.setBackgroundColor(Color.parseColor("#2196F3"))
            btnAction.text = if (accumulated == 0L) "START TRAINING" else "CONTINUE"
            tvStatus.text = "TRAINING PAUSED"
            tvStatus.setTextColor(Color.parseColor("#FF9800"))
        } else {
            btnAction.setBackgroundColor(Color.parseColor("#E74C3C"))
            btnAction.text = "STOP TRAINING"
            tvStatus.text = "LEARNING BEHAVIOR..."
            tvStatus.setTextColor(Color.GRAY)
        }
    }

    private fun activateAI(db: SecurityDatabase) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val data = db.dao().getTrainingData()
            val classifier = BehavioralAuthClassifier(requireContext())

            val errors = data.map {
                classifier.getError(floatArrayOf(it.velocityX, 0.5f, 0.1f, 0.1f))
            }

            val newThreshold = BehavioralAuthClassifier.calculateThreshold(errors)

            requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).edit()
                .putFloat("threshold", newThreshold)
                .putBoolean("ai_ready", true)
                .apply()

            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "AI Protection Activated!", Toast.LENGTH_LONG).show()
            }
        }
    }
}