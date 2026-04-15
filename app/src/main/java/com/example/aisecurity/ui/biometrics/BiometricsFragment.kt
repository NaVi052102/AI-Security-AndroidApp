package com.example.aisecurity.ui.biometrics

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
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
import kotlin.math.max

class BiometricsFragment : Fragment() {

    private val REQUIRED_TRAINING_MS = TimeUnit.DAYS.toMillis(1)
    private val MIN_REQUIRED_SWIPES = 200

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_biometrics, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = SecurityDatabase.get(requireContext())
        val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val classifier = BehavioralAuthClassifier(requireContext())

        // Binding
        val btnAction = view.findViewById<Button>(R.id.btnAction)
        val btnUseAi = view.findViewById<Button>(R.id.btnUseAi)
        val btnReset = view.findViewById<Button>(R.id.btnReset)
        val progressBar = view.findViewById<ProgressBar>(R.id.linearProgress)
        val tvScore = view.findViewById<TextView>(R.id.tvScore)
        val tvRiskScore = view.findViewById<TextView>(R.id.tvRiskScore)
        val tvStatusLabel = view.findViewById<TextView>(R.id.tvStatusLabel)
        val tvPreviousApp = view.findViewById<TextView>(R.id.tvPreviousApp)
        val tvCurrentApp = view.findViewById<TextView>(R.id.tvCurrentApp)

        // Premium Graph Bindings
        val aiLearningGraph = view.findViewById<LineChartView>(R.id.aiLearningGraph)
        val tvCurrentLossValue = view.findViewById<TextView>(R.id.tvCurrentLossValue)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerAppStats)
        val adapter = AppUsageAdapter()
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val isNightMode = true
        applyGlassButton(btnReset, "GHOST_DANGER", isNightMode)

        if (!prefs.contains("training_paused")) {
            prefs.edit().putBoolean("training_paused", true).apply()
        }

        // --- OBSERVE LIVE APP FLOW ---
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
            prefs.edit().apply {
                if (isReady) {
                    putBoolean("ai_ready", false)
                    putBoolean("training_paused", false)
                    putLong("session_start_time", System.currentTimeMillis())
                } else {
                    if (isPaused) {
                        putBoolean("training_paused", false)
                        putLong("session_start_time", System.currentTimeMillis())
                    } else {
                        val sessionStart = prefs.getLong("session_start_time", 0L)
                        var accumulated = prefs.getLong("accumulated_time", 0L)
                        if (sessionStart > 0) accumulated += (System.currentTimeMillis() - sessionStart)
                        putLong("accumulated_time", accumulated)
                        putLong("session_start_time", 0L)
                        putBoolean("training_paused", true)
                    }
                }
                apply()
            }
        }

        btnUseAi.setOnClickListener { activateAI(db) }

        btnReset.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                db.dao().wipeTotalData()
                prefs.edit().clear().apply()
                classifier.wipeMemory()
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        tvStatusLabel.text = "MEMORY WIPED"
                        aiLearningGraph.setData(emptyList())
                        tvCurrentLossValue.text = "LOSS: 0.0000"
                        Toast.makeText(requireContext(), "AI Reset", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // --- DASHBOARD UPDATER LOOP ---
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val rawSwipes = db.dao().getTrainingData()
                    val appStats = db.dao().getAllAppStats()

                    val isReady = prefs.getBoolean("ai_ready", false)
                    val currentRisk = prefs.getInt("current_risk", 0)
                    val isPaused = prefs.getBoolean("training_paused", true)

                    var accumulatedTime = prefs.getLong("accumulated_time", 0L)
                    val sessionStart = prefs.getLong("session_start_time", 0L)
                    if (!isPaused && sessionStart > 0 && !isReady) accumulatedTime += (System.currentTimeMillis() - sessionStart)

                    val timeProgress = ((accumulatedTime.toFloat() / REQUIRED_TRAINING_MS) * 100).toInt().coerceIn(0, 100)

                    // Calculate Graph Data Trend
                    val graphPoints = rawSwipes.takeLast(40).map {
                        classifier.getError(floatArrayOf(it.velocityX / 5000f, it.duration / 2000f, 0.5f, 0.5f))
                    }
                    val latestLoss = graphPoints.lastOrNull() ?: 0.0f

                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            adapter.updateData(appStats)
                            tvCurrentLossValue.text = String.format("LOSS: %.4f", latestLoss)
                            if (graphPoints.isNotEmpty()) aiLearningGraph.setData(graphPoints)

                            val canUseAi = accumulatedTime >= REQUIRED_TRAINING_MS && rawSwipes.size >= MIN_REQUIRED_SWIPES
                            btnUseAi.isEnabled = canUseAi
                            applyGlassButton(btnUseAi, if(canUseAi) "SUCCESS" else "DISABLED", isNightMode)

                            if (isReady) renderProtectionMode(tvScore, tvRiskScore, tvStatusLabel, progressBar, btnAction, btnUseAi, currentRisk, isNightMode)
                            else renderTrainingMode(tvScore, tvRiskScore, tvStatusLabel, progressBar, btnAction, btnUseAi, timeProgress, accumulatedTime, isPaused, rawSwipes.size, isNightMode)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
                delay(1500)
            }
        }
    }

    private fun renderProtectionMode(tvScore: TextView, tvRisk: TextView, tvStatus: TextView, pb: ProgressBar, btnAction: Button, btnUseAi: Button, risk: Int, isNightMode: Boolean) {
        pb.visibility = View.GONE
        btnUseAi.visibility = View.GONE
        btnAction.text = "BACK TO TRAINING"
        applyGlassButton(btnAction, "WARNING", isNightMode)
        tvScore.text = "Continuous Protection Active"
        tvRisk.text = "$risk%"
        val color = if (risk < 50) "#10B981" else "#EF4444"
        tvRisk.setTextColor(Color.parseColor(color))
        tvStatus.text = if (risk < 50) "SYSTEM SECURE" else "ANOMALY DETECTED"
        tvStatus.setTextColor(Color.parseColor(color))
    }

    private fun renderTrainingMode(tvScore: TextView, tvRisk: TextView, tvStatus: TextView, pb: ProgressBar, btnAction: Button, btnUseAi: Button,
                                   progress: Int, accumulated: Long, isPaused: Boolean, swipeCount: Int, isNightMode: Boolean) {
        pb.visibility = View.VISIBLE
        btnUseAi.visibility = View.VISIBLE
        pb.progress = progress
        val remainingMs = (REQUIRED_TRAINING_MS - accumulated).coerceAtLeast(0)
        val days = TimeUnit.MILLISECONDS.toDays(remainingMs)
        val hours = TimeUnit.MILLISECONDS.toHours(remainingMs) % 24
        tvScore.text = "Calibration: $days d $hours h remaining"
        tvRisk.text = "$swipeCount Swipes"
        tvRisk.setTextColor(ContextCompat.getColor(requireContext(), R.color.ig_text_primary))

        if (isPaused) {
            applyGlassButton(btnAction, "PRIMARY", isNightMode)
            btnAction.text = if (accumulated == 0L) "START TRAINING" else "CONTINUE"
            tvStatus.text = "TRAINING PAUSED"
            tvStatus.setTextColor(Color.parseColor("#F59E0B"))
        } else {
            applyGlassButton(btnAction, "DANGER", isNightMode)
            btnAction.text = "STOP TRAINING"
            tvStatus.text = "MAPPING NEURAL PATHS..."
            tvStatus.setTextColor(Color.parseColor("#3B82F6"))
        }
    }

    private fun applyGlassButton(button: Button, type: String, isNightMode: Boolean) {
        val bg = GradientDrawable().apply { cornerRadius = 1000f; shape = GradientDrawable.RECTANGLE }
        when (type) {
            "PRIMARY" -> { bg.setColors(intArrayOf(Color.parseColor("#1E3A8A"), Color.parseColor("#172554"))); bg.setStroke(3, Color.parseColor("#3B82F6")); button.setTextColor(Color.WHITE) }
            "SUCCESS" -> { bg.setColors(intArrayOf(Color.parseColor("#064E3B"), Color.parseColor("#022C22"))); bg.setStroke(3, Color.parseColor("#10B981")); button.setTextColor(Color.WHITE) }
            "DANGER" -> { bg.setColors(intArrayOf(Color.parseColor("#3F000F"), Color.parseColor("#1A0004"))); bg.setStroke(3, Color.parseColor("#EF4444")); button.setTextColor(Color.WHITE) }
            "WARNING" -> { bg.setColors(intArrayOf(Color.parseColor("#78350F"), Color.parseColor("#451A03"))); bg.setStroke(3, Color.parseColor("#F59E0B")); button.setTextColor(Color.WHITE) }
            "DISABLED" -> { bg.setColors(intArrayOf(Color.parseColor("#1E293B"), Color.parseColor("#0F172A"))); bg.setStroke(2, Color.parseColor("#334155")); button.setTextColor(Color.GRAY) }
            "GHOST_DANGER" -> { bg.setColor(Color.TRANSPARENT); bg.setStroke(2, Color.parseColor("#EF4444")); button.setTextColor(Color.parseColor("#EF4444")) }
        }
        button.background = bg
    }

    private fun activateAI(db: SecurityDatabase) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val data = db.dao().getTrainingData()
            val classifier = BehavioralAuthClassifier(requireContext())
            val errors = data.map { classifier.getError(floatArrayOf(it.velocityX/5000f, it.duration/2000f, 0.5f, 0.5f)) }
            val newThreshold = BehavioralAuthClassifier.calculateThreshold(errors)
            requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).edit()
                .putFloat("threshold", newThreshold).putBoolean("ai_ready", true).apply()
        }
    }
}

// 📈 SENIOR DESIGN: HARDWARE ACCELERATED BÉZIER GRAPH
class LineChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var dataPoints: List<Float> = emptyList()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3B82F6"); strokeWidth = 8f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22FFFFFF"); strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f) }

    fun setData(newData: List<Float>) { dataPoints = newData; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.size < 2) return
        val w = width.toFloat(); val h = height.toFloat()

        canvas.drawLine(0f, h*0.3f, w, h*0.3f, gridPaint)
        canvas.drawLine(0f, h*0.6f, w, h*0.6f, gridPaint)

        val xStep = w / (dataPoints.size - 1)
        val maxVal = max(dataPoints.maxOrNull() ?: 1f, 0.6f)

        val path = Path(); val fillPath = Path()
        var prevX = 0f; var prevY = h - (dataPoints[0] / maxVal * h)
        path.moveTo(prevX, prevY); fillPath.moveTo(0f, h); fillPath.lineTo(prevX, prevY)

        for (i in 1 until dataPoints.size) {
            val x = i * xStep; val y = h - (dataPoints[i] / maxVal * h)
            path.cubicTo(prevX + (x - prevX)/2, prevY, prevX + (x - prevX)/2, y, x, y)
            fillPath.cubicTo(prevX + (x - prevX)/2, prevY, prevX + (x - prevX)/2, y, x, y)
            prevX = x; prevY = y
        }
        fillPath.lineTo(w, h); fillPath.close()
        fillPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#663B82F6"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fillPaint); canvas.drawPath(path, linePaint)
    }
}