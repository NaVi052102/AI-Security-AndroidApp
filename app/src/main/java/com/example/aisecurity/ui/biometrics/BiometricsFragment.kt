package com.example.aisecurity.ui.biometrics

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R
import com.example.aisecurity.ai.AiCloudSyncManager
import com.example.aisecurity.ai.BehavioralAuthClassifier
import com.example.aisecurity.ai.SecurityDatabase
import com.example.aisecurity.ui.AppUsageAdapter
import com.example.aisecurity.ui.LiveLogger
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max

class BiometricsFragment : Fragment() {

    private val REQUIRED_TRAINING_MS = TimeUnit.DAYS.toMillis(1)
    private val MIN_REQUIRED_SWIPES  = 200

    private lateinit var tvStatusLabel: TextView
    private lateinit var tvCurrentLossValue: TextView
    private lateinit var aiLearningGraph: LineChartView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_biometrics, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db         = SecurityDatabase.get(requireContext())
        val prefs      = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        var classifier = BehavioralAuthClassifier(requireContext())

        val syncManager = AiCloudSyncManager(requireContext())
        val checkpointFile = File(requireContext().filesDir, "brain_checkpoint.ckpt")

        if (!checkpointFile.exists()) {
            syncManager.restoreBrainFromCloud { success ->
                if (success) {
                    classifier = BehavioralAuthClassifier(requireContext())
                    requireActivity().runOnUiThread {
                        Toast.makeText(context, "Cloud AI Profile Restored!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // ── View bindings ──────────────────────────────────────────────────
        val btnAction       = view.findViewById<Button>(R.id.btnAction)
        val btnUseAi        = view.findViewById<Button>(R.id.btnUseAi)
        val btnReset        = view.findViewById<Button>(R.id.btnReset)
        val progressBar     = view.findViewById<ProgressBar>(R.id.linearProgress)
        val tvScore         = view.findViewById<TextView>(R.id.tvScore)
        val tvRiskScore     = view.findViewById<TextView>(R.id.tvRiskScore)
        tvStatusLabel       = view.findViewById(R.id.tvStatusLabel)
        val tvPreviousApp   = view.findViewById<TextView>(R.id.tvPreviousApp)
        val tvCurrentApp    = view.findViewById<TextView>(R.id.tvCurrentApp)
        aiLearningGraph     = view.findViewById(R.id.aiLearningGraph)
        tvCurrentLossValue  = view.findViewById(R.id.tvCurrentLossValue)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerAppStats)
        val adapter      = AppUsageAdapter()
        recyclerView.adapter      = adapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        applyGlassButton(btnReset, "GHOST_DANGER", isNightMode)

        if (!prefs.contains("training_paused")) {
            prefs.edit().putBoolean("training_paused", true).apply()
        }

        LiveLogger.logData.observe(viewLifecycleOwner) { logText ->
            val lastFlowLine = logText.split("\n").lastOrNull { it.contains("📱 FLOW:") }
            if (lastFlowLine != null) {
                val flowPart = lastFlowLine.substringAfter("FLOW:").trim()
                val apps     = flowPart.split("->")
                if (apps.size == 2) {
                    tvPreviousApp.text = apps[0].trim()
                    tvCurrentApp.text  = apps[1].trim()
                }
            }
        }

        // 🚨 THE FIX: Directly route to Android Settings instead of the Permissions Page
        btnAction.setOnClickListener {
            val hasAccessibility = isAccessibilitySettingsOn(requireContext())
            val hasUsageStats = hasUsageStatsPermission(requireContext())

            if (!hasAccessibility) {
                Toast.makeText(requireContext(), "Please enable Accessibility to allow AI gesture tracking.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }

            if (!hasUsageStats) {
                Toast.makeText(requireContext(), "Please enable Usage Access to allow AI context awareness.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                return@setOnClickListener
            }

            val isReady  = prefs.getBoolean("ai_ready", false)
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
                        val sessionStart  = prefs.getLong("session_start_time", 0L)
                        var accumulated   = prefs.getLong("accumulated_time", 0L)
                        if (sessionStart > 0) accumulated += (System.currentTimeMillis() - sessionStart)
                        putLong("accumulated_time", accumulated)
                        putLong("session_start_time", 0L)
                        putBoolean("training_paused", true)
                    }
                }
                apply()
            }
        }

        btnUseAi.setOnClickListener { activateAI(db, classifier) }

        btnReset.setOnClickListener {
            showResetVerificationDialog(db, prefs, classifier)
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val safeContext = requireContext()

            while (isActive) {
                try {
                    val rawSwipes       = db.dao().getTrainingData()
                    val appStats        = db.dao().getAllAppStats()
                    val isReady         = prefs.getBoolean("ai_ready", false)
                    val currentRisk     = prefs.getInt("current_risk", 0)
                    var isPaused        = prefs.getBoolean("training_paused", true)

                    val hasAccessibility = isAccessibilitySettingsOn(safeContext)
                    val hasUsageStats = hasUsageStatsPermission(safeContext)

                    if (!hasAccessibility || !hasUsageStats) {
                        if (!isPaused && !isReady) {
                            val sessionStart = prefs.getLong("session_start_time", 0L)
                            var accumulated = prefs.getLong("accumulated_time", 0L)
                            if (sessionStart > 0) accumulated += (System.currentTimeMillis() - sessionStart)

                            prefs.edit()
                                .putLong("accumulated_time", accumulated)
                                .putLong("session_start_time", 0L)
                                .putBoolean("training_paused", true)
                                .apply()

                            isPaused = true
                        }
                    }

                    var accumulatedTime = prefs.getLong("accumulated_time", 0L)
                    val sessionStart    = prefs.getLong("session_start_time", 0L)
                    if (!isPaused && sessionStart > 0 && !isReady)
                        accumulatedTime += (System.currentTimeMillis() - sessionStart)

                    val timeProgress = ((accumulatedTime.toFloat() / REQUIRED_TRAINING_MS) * 100)
                        .toInt().coerceIn(0, 100)

                    val lossHistory = db.dao().getTrainingLossHistory()
                    val window      = lossHistory.takeLast(60)

                    val emaPoints  = window.map { it.emaValue }
                    val rawPoints  = window.map { it.lossValue }

                    val latestEma  = emaPoints.lastOrNull() ?: 1.0f

                    val restoredSwipes = prefs.getInt("restored_swipe_count", 0)
                    val totalSwipes = rawSwipes.size + restoredSwipes

                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext

                        adapter.updateData(appStats)

                        tvCurrentLossValue.text = "EMA: ${"%.4f".format(latestEma)}"

                        if (emaPoints.isNotEmpty()) {
                            aiLearningGraph.setData(emaPoints, rawPoints)
                        }

                        val canUseAi = accumulatedTime >= REQUIRED_TRAINING_MS && totalSwipes >= MIN_REQUIRED_SWIPES
                        btnUseAi.isEnabled = canUseAi
                        applyGlassButton(btnUseAi, if (canUseAi) "SUCCESS" else "DISABLED", isNightMode)

                        if (!hasAccessibility || !hasUsageStats) {
                            applyGlassButton(btnAction, "PRIMARY", isNightMode)
                            btnAction.text = "ENABLE PERMISSIONS"
                        }

                        if (isReady)
                            renderProtectionMode(tvScore, tvRiskScore, tvStatusLabel,
                                progressBar, btnAction, btnUseAi, currentRisk, isNightMode, hasAccessibility, hasUsageStats)
                        else
                            renderTrainingMode(tvScore, tvRiskScore, tvStatusLabel,
                                progressBar, btnAction, btnUseAi,
                                timeProgress, accumulatedTime, isPaused, totalSwipes, isNightMode, hasAccessibility, hasUsageStats)
                    }
                } catch (e: Exception) { e.printStackTrace() }
                delay(1500)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // PERMISSION CHECKERS
    // ══════════════════════════════════════════════════════════════════════════════
    private fun isAccessibilitySettingsOn(mContext: Context): Boolean {
        var accessibilityEnabled = 0
        val service = mContext.packageName + "/" + "com.example.aisecurity.ai.TouchDynamicsService"
        try {
            accessibilityEnabled = Settings.Secure.getInt(mContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Settings.SettingNotFoundException) { }

        val mStringColonSplitter = TextUtils.SimpleStringSplitter(':')
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(mContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (settingValue != null) {
                mStringColonSplitter.setString(settingValue)
                while (mStringColonSplitter.hasNext()) {
                    if (mStringColonSplitter.next().equals(service, ignoreCase = true)) return true
                }
            }
        }
        return false
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }


    // ══════════════════════════════════════════════════════════════════════════════
    // SECURE RESET DIALOG
    // ══════════════════════════════════════════════════════════════════════════════
    private fun showResetVerificationDialog(db: SecurityDatabase, prefs: SharedPreferences, classifier: BehavioralAuthClassifier) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_verify_reset, null)
        val dialogRoot = dialogView.findViewById<LinearLayout>(R.id.dialogRoot)
        val etCode = dialogView.findViewById<EditText>(R.id.etVerificationCode)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnVerify = dialogView.findViewById<Button>(R.id.btnVerify)

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val dialogBg = GradientDrawable().apply {
            cornerRadius = 60f
            if (isNightMode) {
                setColor(Color.parseColor("#FA0F172A"))
                setStroke(2, Color.parseColor("#334155"))
            } else {
                setColor(Color.parseColor("#FAFFFFFF"))
                setStroke(2, Color.parseColor("#CBD5E1"))
            }
        }
        dialogRoot.background = dialogBg

        val cancelBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            if (isNightMode) {
                setColor(Color.parseColor("#0F172A"))
                setStroke(3, Color.parseColor("#334155"))
                btnCancel.setTextColor(Color.parseColor("#94A3B8"))
            } else {
                setColor(Color.parseColor("#F8FAFC"))
                setStroke(3, Color.parseColor("#CBD5E1"))
                btnCancel.setTextColor(Color.parseColor("#64748B"))
            }
        }
        btnCancel.background = cancelBg

        val verifyBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            if (isNightMode) {
                setColor(Color.parseColor("#1E293B"))
                setStroke(4, Color.parseColor("#D4AF37"))
                btnVerify.setTextColor(Color.parseColor("#FFFFFF"))
            } else {
                setColor(Color.parseColor("#FFFFFF"))
                setStroke(4, Color.parseColor("#2563EB"))
                btnVerify.setTextColor(Color.parseColor("#1E293B"))
            }
        }
        btnVerify.background = verifyBg

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnVerify.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code == "123456") {
                dialog.dismiss()
                executeWipeProtocol(db, prefs, classifier)
            } else {
                Toast.makeText(requireContext(), "Invalid OTP Verification Code.", Toast.LENGTH_SHORT).show()
                etCode.setText("")
            }
        }

        dialog.show()
    }

    private fun executeWipeProtocol(db: SecurityDatabase, prefs: SharedPreferences, classifier: BehavioralAuthClassifier) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            db.dao().wipeTotalData()
            prefs.edit().clear().apply()
            classifier.wipeMemory()
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    tvStatusLabel.text = "MEMORY WIPED"
                    aiLearningGraph.setData(emptyList(), emptyList())
                    tvCurrentLossValue.text = "EMA: 1.0000"
                    Toast.makeText(requireContext(), "AI Profile Successfully Reset", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun renderProtectionMode(tvScore: TextView, tvRisk: TextView, tvStatus: TextView,
                                     pb: ProgressBar, btnAction: Button, btnUseAi: Button,
                                     risk: Int, isNightMode: Boolean, hasAcc: Boolean, hasUsage: Boolean) {
        pb.visibility      = View.GONE
        btnUseAi.visibility = View.GONE

        if (!hasAcc || !hasUsage) {
            tvStatus.text      = "SENSOR OFFLINE"
            tvStatus.setTextColor(Color.parseColor("#EF4444"))
            return
        }

        btnAction.text     = "BACK TO TRAINING"
        applyGlassButton(btnAction, "WARNING", isNightMode)
        tvScore.text       = "Continuous Protection Active"
        tvRisk.text        = "$risk%"
        val color          = if (risk < 50) "#10B981" else "#EF4444"
        tvRisk.setTextColor(Color.parseColor(color))
        tvStatus.text      = if (risk < 50) "SYSTEM SECURE" else "ANOMALY DETECTED"
        tvStatus.setTextColor(Color.parseColor(color))
    }

    private fun renderTrainingMode(tvScore: TextView, tvRisk: TextView, tvStatus: TextView,
                                   pb: ProgressBar, btnAction: Button, btnUseAi: Button,
                                   progress: Int, accumulated: Long, isPaused: Boolean,
                                   swipeCount: Int, isNightMode: Boolean, hasAcc: Boolean, hasUsage: Boolean) {
        pb.visibility       = View.VISIBLE
        btnUseAi.visibility = View.VISIBLE
        pb.progress         = progress
        val remainingMs     = (REQUIRED_TRAINING_MS - accumulated).coerceAtLeast(0)
        val days            = TimeUnit.MILLISECONDS.toDays(remainingMs)
        val hours           = TimeUnit.MILLISECONDS.toHours(remainingMs) % 24
        tvScore.text        = "Calibration: $days d $hours h remaining"
        tvRisk.text         = "$swipeCount Swipes"
        tvRisk.setTextColor(ContextCompat.getColor(requireContext(), R.color.ig_text_primary))

        if (!hasAcc || !hasUsage) {
            tvStatus.text   = "SENSORS DISCONNECTED"
            tvStatus.setTextColor(Color.parseColor("#EF4444"))
            return
        }

        if (isPaused) {
            applyGlassButton(btnAction, "PRIMARY", isNightMode)
            btnAction.text  = if (accumulated == 0L) "START TRAINING" else "CONTINUE"
            tvStatus.text   = "TRAINING PAUSED"
            tvStatus.setTextColor(Color.parseColor("#F59E0B"))
        } else {
            applyGlassButton(btnAction, "DANGER", isNightMode)
            btnAction.text  = "STOP TRAINING"
            tvStatus.text   = "MAPPING NEURAL PATHS..."
            tvStatus.setTextColor(Color.parseColor("#3B82F6"))
        }
    }

    private fun applyGlassButton(button: Button, type: String, isNightMode: Boolean) {
        val bg = GradientDrawable().apply {
            cornerRadius = 1000f; shape = GradientDrawable.RECTANGLE
        }
        when (type) {
            "PRIMARY"      -> { bg.setColors(intArrayOf(Color.parseColor("#1E3A8A"), Color.parseColor("#172554"))); bg.setStroke(3, Color.parseColor("#3B82F6")); button.setTextColor(Color.WHITE) }
            "SUCCESS"      -> { bg.setColors(intArrayOf(Color.parseColor("#064E3B"), Color.parseColor("#022C22"))); bg.setStroke(3, Color.parseColor("#10B981")); button.setTextColor(Color.WHITE) }
            "DANGER"       -> { bg.setColors(intArrayOf(Color.parseColor("#3F000F"), Color.parseColor("#1A0004"))); bg.setStroke(3, Color.parseColor("#EF4444")); button.setTextColor(Color.WHITE) }
            "WARNING"      -> { bg.setColors(intArrayOf(Color.parseColor("#78350F"), Color.parseColor("#451A03"))); bg.setStroke(3, Color.parseColor("#F59E0B")); button.setTextColor(Color.WHITE) }
            "DISABLED"     -> { bg.setColors(intArrayOf(Color.parseColor("#1E293B"), Color.parseColor("#0F172A"))); bg.setStroke(2, Color.parseColor("#334155")); button.setTextColor(Color.GRAY) }
            "GHOST_DANGER" -> { bg.setColor(Color.TRANSPARENT); bg.setStroke(2, Color.parseColor("#EF4444")); button.setTextColor(Color.parseColor("#EF4444")) }
        }
        button.background = bg
    }

    private fun activateAI(db: SecurityDatabase, classifier: BehavioralAuthClassifier) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val data       = db.dao().getTrainingData()
            val errors     = data.map {
                classifier.getError(floatArrayOf(it.velocityX / 5000f, it.duration / 2000f, 0.5f, 0.5f))
            }
            val newThreshold = BehavioralAuthClassifier.calculateThreshold(errors)
            requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).edit()
                .putFloat("threshold", newThreshold)
                .putBoolean("ai_ready", true)
                .apply()

            val syncManager = AiCloudSyncManager(requireContext())
            syncManager.backupBrainToCloud { success ->
                if (success) {
                    LiveLogger.log("✅ AI Security locked and backed up to cloud.")
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// LineChartView
// ══════════════════════════════════════════════════════════════════════════════
class LineChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var emaPoints: List<Float> = emptyList()
    private var rawPoints: List<Float> = emptyList()

    private val emaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#3B82F6")
        strokeWidth = 6f
        style       = Paint.Style.STROKE
        strokeCap   = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rawDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#22FFFFFF")
        strokeWidth = 1.5f
        pathEffect  = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.parseColor("#AAAAAA")
        textSize = 24f
    }

    fun setData(ema: List<Float>, raw: List<Float> = emptyList()) {
        emaPoints = ema
        rawPoints = raw
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (emaPoints.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        val legendH = 36f
        val plotH   = h - legendH

        val gridY = listOf(0.25f, 0.5f, 0.75f)
        gridY.forEach { frac ->
            val y = plotH * frac
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        val allValues = emaPoints + rawPoints
        val maxVal    = max(allValues.maxOrNull() ?: 1f, 0.05f)

        val n      = emaPoints.size
        val xStep  = w / (n - 1)

        if (rawPoints.size == n) {
            for (i in 0 until n) {
                val x = i * xStep
                val y = plotH - (rawPoints[i] / maxVal * plotH)
                canvas.drawCircle(x, y, 3.5f, rawDotPaint)
            }
        }

        val path     = Path()
        val fillPath = Path()

        var prevX = 0f
        var prevY = plotH - (emaPoints[0] / maxVal * plotH)

        path.moveTo(prevX, prevY)
        fillPath.moveTo(0f, plotH)
        fillPath.lineTo(prevX, prevY)

        for (i in 1 until n) {
            val x   = i * xStep
            val y   = plotH - (emaPoints[i] / maxVal * plotH)
            val cpX = prevX + (x - prevX) / 2f
            path.cubicTo(cpX, prevY, cpX, y, x, y)
            fillPath.cubicTo(cpX, prevY, cpX, y, x, y)
            prevX = x; prevY = y
        }

        fillPath.lineTo(w, plotH)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, plotH,
            Color.parseColor("#663B82F6"),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, emaPaint)

        val swatchPaint = Paint().apply { isAntiAlias = true }
        swatchPaint.color = Color.parseColor("#3B82F6")
        canvas.drawRect(8f, plotH + 8f, 26f, plotH + 24f, swatchPaint)
        legendPaint.color = Color.parseColor("#AAAAAA")
        canvas.drawText("EMA loss", 32f, plotH + 24f, legendPaint)

        swatchPaint.color = Color.parseColor("#555555")
        canvas.drawRect(160f, plotH + 8f, 178f, plotH + 24f, swatchPaint)
        canvas.drawText("Raw loss", 184f, plotH + 24f, legendPaint)
    }
}