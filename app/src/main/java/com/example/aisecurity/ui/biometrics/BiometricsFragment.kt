package com.example.aisecurity.ui.biometrics

import android.util.AttributeSet
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Html
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
import com.example.aisecurity.ai.BehavioralAuthClassifier
import com.example.aisecurity.ai.SecurityDatabase
import com.example.aisecurity.ui.AppUsageAdapter
import com.example.aisecurity.ui.LiveLogger
import kotlinx.coroutines.*
import kotlin.math.max

class BiometricsFragment : Fragment() {

    private val DEFAULT_EMA_STABILITY = 0.08f

    private lateinit var tvStatusLabel: TextView
    private lateinit var tvCurrentLossValue: TextView
    private lateinit var aiLearningGraph: LineChartView

    private val riskHistory = mutableListOf<Float>()

    // 🚨 RAM CACHE for Transition Tracker Icons
    private val iconCache = mutableMapOf<String, Drawable?>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_biometrics, container, false)

    override fun onResume() {
        super.onResume()
        if (isHidden || !isVisible) return

        val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("has_seen_biometrics_tutorial", false)) {
            showTutorialDialog()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("has_seen_biometrics_tutorial", false)) {
                showTutorialDialog()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db         = SecurityDatabase.get(requireContext())
        val prefs      = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val classifier = BehavioralAuthClassifier(requireContext())

        val btnAction       = view.findViewById<Button>(R.id.btnAction)
        val btnUseAi        = view.findViewById<Button>(R.id.btnUseAi)
        val btnReset        = view.findViewById<Button>(R.id.btnReset)
        val btnInfo         = view.findViewById<ImageButton>(R.id.btnInfo)
        val progressBar     = view.findViewById<ProgressBar>(R.id.linearProgress)
        val tvScore         = view.findViewById<TextView>(R.id.tvScore)
        val tvRiskScore     = view.findViewById<TextView>(R.id.tvRiskScore)
        tvStatusLabel       = view.findViewById(R.id.tvStatusLabel)

        val tvPreviousApp   = view.findViewById<TextView>(R.id.tvPreviousApp)
        val ivPreviousApp   = view.findViewById<ImageView>(R.id.ivPreviousApp)
        val tvCurrentApp    = view.findViewById<TextView>(R.id.tvCurrentApp)
        val ivCurrentApp    = view.findViewById<ImageView>(R.id.ivCurrentApp)

        aiLearningGraph     = view.findViewById(R.id.aiLearningGraph)
        tvCurrentLossValue  = view.findViewById(R.id.tvCurrentLossValue)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerAppStats)
        val adapter      = AppUsageAdapter()
        recyclerView.adapter      = adapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        applyGlassButton(btnReset, "GHOST_DANGER", isNightMode)

        btnInfo.setOnClickListener {
            showTutorialDialog()
        }

        if (!prefs.contains("training_paused")) {
            prefs.edit().putBoolean("training_paused", true).apply()
        }

        // 🚨 UPGRADED OBSERVER: Now binds the visual icons dynamically
        LiveLogger.logData.observe(viewLifecycleOwner) { logText ->
            val lastFlowLine = logText.split("\n").lastOrNull { it.contains("📱 FLOW:") }
            if (lastFlowLine != null) {
                val flowPart = lastFlowLine.substringAfter("FLOW:").trim()
                val apps     = flowPart.split("->")
                if (apps.size == 2) {
                    val prevName = apps[0].trim()
                    val currName = apps[1].trim()

                    tvPreviousApp.text = prevName
                    tvCurrentApp.text  = currName

                    val prevIcon = fetchAppIcon(requireContext(), prevName)
                    if (prevIcon != null) {
                        ivPreviousApp.setImageDrawable(prevIcon)
                        ivPreviousApp.visibility = View.VISIBLE
                    } else { ivPreviousApp.visibility = View.GONE }

                    val currIcon = fetchAppIcon(requireContext(), currName)
                    if (currIcon != null) {
                        ivCurrentApp.setImageDrawable(currIcon)
                        ivCurrentApp.visibility = View.VISIBLE
                    } else { ivCurrentApp.visibility = View.GONE }
                }
            }
        }

        btnAction.setOnClickListener {
            val hasAccessibility = isAccessibilitySettingsOn(requireContext())
            val hasUsageStats = hasUsageStatsPermission(requireContext())

            if (!hasAccessibility) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            if (!hasUsageStats) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                return@setOnClickListener
            }

            val isReady  = prefs.getBoolean("ai_ready", false)
            val isPaused = prefs.getBoolean("training_paused", true)

            prefs.edit().apply {
                if (isReady) {
                    putBoolean("ai_ready", false)
                    putBoolean("training_paused", true)
                    putInt("current_risk", 0)
                    riskHistory.clear()
                } else {
                    putBoolean("training_paused", !isPaused)
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
                    val currentRisk     = prefs.getInt("current_risk", 0).toFloat()
                    var isPaused        = prefs.getBoolean("training_paused", true)

                    val hasAccessibility = isAccessibilitySettingsOn(safeContext)
                    val hasUsageStats = hasUsageStatsPermission(safeContext)

                    if (!hasAccessibility || !hasUsageStats) {
                        if (!isPaused && !isReady) {
                            prefs.edit().putBoolean("training_paused", true).apply()
                            isPaused = true
                        }
                    }

                    val lossHistory = db.dao().getTrainingLossHistory()
                    val window      = lossHistory.takeLast(60)

                    val emaPoints  = window.map { it.emaValue }
                    val rawPoints  = window.map { it.lossValue }
                    val latestEma  = emaPoints.lastOrNull() ?: 1.0f
                    val totalSwipes = rawSwipes.size

                    if (isReady) {
                        riskHistory.add(currentRisk)
                        if (riskHistory.size > 60) riskHistory.removeAt(0)
                    }

                    val globalThreshold = prefs.getFloat("threshold", DEFAULT_EMA_STABILITY)
                    val targetStability = globalThreshold * 1.2f
                    val baselineEma = 1.0f

                    val appConfidences = mutableMapOf<String, Int>()

                    var anyAppAbove75 = false
                    var coreAppsCount = 0

                    for (app in appStats) {
                        val appEma = prefs.getFloat("ema_loss_${app.packageName}", 1.0f)

                        val progressRaw = ((baselineEma - appEma) / (baselineEma - targetStability)).coerceIn(0f, 1f)
                        val finalPct = (progressRaw * 100f).toInt()

                        appConfidences[app.packageName] = finalPct

                        if (finalPct >= 75) {
                            anyAppAbove75 = true
                        }
                        coreAppsCount++
                    }

                    val isConverged = anyAppAbove75

                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext

                        if (adapter is AppUsageAdapter) {
                            try {
                                val method = adapter.javaClass.getMethod("updateData", List::class.java, Map::class.java)
                                method.invoke(adapter, appStats, appConfidences)
                            } catch (e: Exception) {
                                adapter.updateData(appStats)
                            }
                        }

                        btnUseAi.isEnabled = isConverged
                        applyGlassButton(btnUseAi, if (isConverged) "SUCCESS" else "DISABLED", isNightMode)

                        if (!hasAccessibility || !hasUsageStats) {
                            applyGlassButton(btnAction, "PRIMARY", isNightMode)
                            btnAction.text = "ENABLE PERMISSIONS"
                        }

                        if (isReady) {
                            aiLearningGraph.setMode(LineChartView.MODE_DETECTION)
                            aiLearningGraph.setData(riskHistory, emptyList())
                            tvCurrentLossValue.text = "THREAT LEVEL: ${currentRisk.toInt()}%"

                            renderProtectionMode(tvScore, tvRiskScore, tvStatusLabel,
                                progressBar, btnAction, btnUseAi, currentRisk.toInt(), isNightMode, hasAccessibility, hasUsageStats)
                        } else {
                            aiLearningGraph.setMode(LineChartView.MODE_TRAINING)
                            aiLearningGraph.setData(emaPoints, rawPoints)
                            tvCurrentLossValue.text = "EMA LOSS: ${"%.4f".format(latestEma)}"

                            val globalUiConfidence = if (coreAppsCount > 0) {
                                appConfidences.values.average().toInt()
                            } else {
                                0
                            }

                            tvScore.text = "AVERAGE AI CONVERGENCE"

                            renderTrainingMode(tvScore, tvRiskScore, tvStatusLabel,
                                progressBar, btnAction, btnUseAi,
                                globalUiConfidence, isPaused, totalSwipes, isNightMode, hasAccessibility, hasUsageStats)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
                delay(1000)
            }
        }
    }

    // 🚨 High-Speed OS Icon Fetcher
    private fun fetchAppIcon(context: Context, appName: String): Drawable? {
        if (iconCache.containsKey(appName)) return iconCache[appName]

        val pm = context.packageManager
        val knownPackages = mapOf(
            "Facebook" to "com.facebook.katana",
            "Messenger" to "com.facebook.orca",
            "TikTok" to "com.zhiliaoapp.musically",
            "Instagram" to "com.instagram.android",
            "YouTube" to "com.google.android.youtube",
            "WhatsApp" to "com.whatsapp",
            "X (Twitter)" to "com.twitter.android",
            "Mobile Legends" to "com.mobile.legends",
            "System UI" to "com.android.systemui",
            "Home Screen" to "com.miui.home"
        )

        var icon: Drawable? = null
        if (knownPackages.containsKey(appName)) {
            try { icon = pm.getApplicationIcon(knownPackages[appName]!!) } catch (e: Exception) {}
        } else {
            try {
                val packages = pm.getInstalledApplications(0)
                for (appInfo in packages) {
                    if (pm.getApplicationLabel(appInfo).toString().equals(appName, ignoreCase = true)) {
                        icon = pm.getApplicationIcon(appInfo)
                        break
                    }
                }
            } catch (e: Exception) {}
        }

        if (icon == null) {
            try { icon = ContextCompat.getDrawable(context, android.R.mipmap.sym_def_app_icon) } catch (e: Exception) {}
        }

        iconCache[appName] = icon
        return icon
    }

    private fun showTutorialDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_biometrics_info, null)
        val dialog = AlertDialog.Builder(requireContext()).setView(dialogView).setCancelable(false).create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.6f)

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val bgColor = if (isNightMode) Color.parseColor("#F20F172A") else Color.parseColor("#F2FFFFFF")
        val strokeColor = if (isNightMode) Color.parseColor("#4D3B82F6") else Color.parseColor("#3394A3B8")
        val textPrimaryColor = if (isNightMode) Color.WHITE else Color.parseColor("#0F172A")
        val textSecondaryColor = if (isNightMode) Color.parseColor("#94A3B8") else Color.parseColor("#475569")

        val solidGlassBg = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = 50f
            setStroke(3, strokeColor)
        }
        dialogView.background = solidGlassBg
        dialogView.setPadding(60, 60, 60, 60)

        val ivImage = dialogView.findViewById<ImageView>(R.id.ivTutorialImage)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTutorialTitle)
        val tvDesc = dialogView.findViewById<TextView>(R.id.tvTutorialDesc)
        val tvStep = dialogView.findViewById<TextView>(R.id.tvTutorialStep)
        val btnBack = dialogView.findViewById<Button>(R.id.btnTutorialBack)
        val btnNext = dialogView.findViewById<Button>(R.id.btnTutorialNext)

        tvTitle.setTextColor(textPrimaryColor)
        tvDesc.setTextColor(textSecondaryColor)

        data class TutorialStep(val title: String, val desc: String, val imageRes: Int)

        val steps = listOf(
            TutorialStep(
                "Welcome to the Neural Engine",
                "This security system does not rely on static passwords.<br><br>Instead, it uses <b>Edge AI</b> to learn your physical habits. It memorizes the exact velocity, pressure, and timing of your screen touches to mathematically prove you are the owner.",
                android.R.drawable.ic_dialog_info
            ),
            TutorialStep(
                "1. The Neural Mapping Curve",
                "The graph tracks the AI's learning process in real-time.<br><br>The <b>Raw Loss (Dots)</b> shows the error of your current swipe. The <b>EMA Loss (Blue Line)</b> is the smoothed average. When the blue line drops and flattens out, the AI has successfully memorized your thumb's physics.",
                android.R.drawable.ic_menu_gallery
            ),
            TutorialStep(
                "2. App Transition Tracker",
                "Intruders don't know your phone like you do.<br><br>The system silently watches how fast you switch between apps (e.g., from Home Screen to Messages). If someone takes strange routes or hesitates, the AI instantly flags it as anomalous behavior.",
                android.R.drawable.ic_menu_sort_by_size
            ),
            TutorialStep(
                "3. App-Specific Mastery",
                "Every app has its own dedicated brain.<br><br>You swipe differently in a game than you do in Chrome. The <b>Live App Insights</b> show how close the AI is to mastering each specific app. At 100%, your physical signature for that app is locked in.",
                android.R.drawable.ic_menu_manage
            ),
            TutorialStep(
                "4. Activating the Shield",
                "Once any tracked app reaches <b>75% stability</b>, the <b>USE AI</b> button unlocks.<br><br>Tap it to arm the system. The AI freezes its training and actively hunts for intruders. If an unrecognized thumb swipes the screen, the threat level spikes, and the device will violently lock.",
                android.R.drawable.ic_lock_lock
            )
        )

        var currentStep = 0

        fun updateUI() {
            val step = steps[currentStep]

            tvTitle.text = step.title
            ivImage.setImageResource(step.imageRes)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                tvDesc.text = Html.fromHtml(step.desc, Html.FROM_HTML_MODE_COMPACT)
            } else {
                @Suppress("DEPRECATION")
                tvDesc.text = Html.fromHtml(step.desc)
            }

            tvStep.text = "${currentStep + 1} / ${steps.size}"

            btnBack.visibility = if (currentStep == 0) View.INVISIBLE else View.VISIBLE
            btnNext.text = if (currentStep == steps.size - 1) "GOT IT" else "NEXT"
        }

        btnBack.setOnClickListener {
            if (currentStep > 0) {
                currentStep--
                updateUI()
            }
        }

        btnNext.setOnClickListener {
            if (currentStep < steps.size - 1) {
                currentStep++
                updateUI()
            } else {
                requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("has_seen_biometrics_tutorial", true).apply()
                dialog.dismiss()
            }
        }

        updateUI()
        dialog.show()
    }

    private fun isAccessibilitySettingsOn(mContext: Context): Boolean {
        var accessibilityEnabled = 0
        val service = mContext.packageName + "/" + "com.example.aisecurity.ai.TouchDynamicsService"
        try { accessibilityEnabled = Settings.Secure.getInt(mContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) } catch (e: Exception) { }
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(mContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (settingValue != null && settingValue.contains(service, ignoreCase = true)) return true
        }
        return false
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun showResetVerificationDialog(db: SecurityDatabase, prefs: SharedPreferences, classifier: BehavioralAuthClassifier) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_verify_reset, null)
        val dialog = AlertDialog.Builder(requireContext()).setView(dialogView).setCancelable(false).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<Button>(R.id.btnVerify).setOnClickListener {
            val code = dialogView.findViewById<EditText>(R.id.etVerificationCode).text.toString().trim()
            if (code == "123456") {
                dialog.dismiss()
                executeWipeProtocol(db, prefs, classifier)
            } else {
                showSentryToast("Invalid OTP.", isLong = false)
            }
        }
        dialog.show()
    }

    private fun executeWipeProtocol(db: SecurityDatabase, prefs: SharedPreferences, classifier: BehavioralAuthClassifier) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            db.dao().wipeTotalData()

            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith("ema_loss_") }.forEach { editor.remove(it) }
            editor.remove("threshold")
            editor.remove("ai_ready")
            editor.remove("current_risk")
            editor.apply()

            classifier.wipeMemory()
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    tvStatusLabel.text = "MEMORY WIPED"
                    aiLearningGraph.setData(emptyList(), emptyList())
                    riskHistory.clear()
                    tvCurrentLossValue.text = "EMA: 1.0000"

                    showSentryToast("AI Profile Successfully Reset", isLong = true)
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

        btnAction.text     = "DISABLE AI"
        applyGlassButton(btnAction, "DANGER", isNightMode)
        tvScore.text       = "GLOBAL PROTECTION ACTIVE"
        tvRisk.text        = "$risk%"
        val color          = if (risk < 50) "#10B981" else "#EF4444"
        tvRisk.setTextColor(Color.parseColor(color))
        tvStatus.text      = if (risk < 50) "SECURE & ADAPTING" else "ANOMALY DETECTED"
        tvStatus.setTextColor(Color.parseColor(color))
    }

    private fun renderTrainingMode(tvScore: TextView, tvRisk: TextView, tvStatus: TextView,
                                   pb: ProgressBar, btnAction: Button, btnUseAi: Button,
                                   confidence: Int, isPaused: Boolean,
                                   swipeCount: Int, isNightMode: Boolean, hasAcc: Boolean, hasUsage: Boolean) {
        pb.visibility       = View.VISIBLE
        btnUseAi.visibility = View.VISIBLE
        pb.progress         = confidence

        tvRisk.text         = "$confidence%"
        tvRisk.setTextColor(ContextCompat.getColor(requireContext(), R.color.ig_text_primary))

        if (!hasAcc || !hasUsage) {
            tvStatus.text   = "SENSORS DISCONNECTED"
            tvStatus.setTextColor(Color.parseColor("#EF4444"))
            return
        }

        if (isPaused) {
            applyGlassButton(btnAction, "PRIMARY", isNightMode)
            btnAction.text  = if (swipeCount == 0) "START TRAINING" else "CONTINUE"
            tvStatus.text   = "TRAINING PAUSED"
            tvStatus.setTextColor(Color.parseColor("#F59E0B"))
        } else {
            applyGlassButton(btnAction, "DANGER", isNightMode)
            btnAction.text  = "STOP TRAINING"
            tvStatus.text   = "TRAINING"
            tvStatus.setTextColor(Color.parseColor("#3B82F6"))
        }
    }

    private fun applyGlassButton(button: Button, type: String, isNightMode: Boolean) {
        val bg = GradientDrawable().apply { cornerRadius = 1000f; shape = GradientDrawable.RECTANGLE }
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
            val lossHistory = db.dao().getTrainingLossHistory()
            val errors = lossHistory.takeLast(200).map { it.lossValue }

            val newThreshold = BehavioralAuthClassifier.calculateThreshold(errors)

            requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).edit()
                .putFloat("threshold", newThreshold)
                .putBoolean("ai_ready", true)
                .putInt("current_risk", 0)
                .apply()

            withContext(Dispatchers.Main) {
                LiveLogger.log("✅ AI Security locked. Edge Model is Secure. Threshold: $newThreshold")
            }
        }
    }

    // ==========================================
    // 🚨 PREMIUM CUSTOM TOAST BUILDER
    // ==========================================
    private fun showSentryToast(message: String, isLong: Boolean) {
        val toast = Toast(requireContext())
        toast.duration = if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT

        val customLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.parseColor("#12151C"))
                setStroke(3, Color.parseColor("#3B82F6"))
            }
            setPadding(50, 30, 50, 30)
        }

        val icon = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_sentry_half_gold)
            layoutParams = LinearLayout.LayoutParams(60, 75).apply {
                setMargins(0, 0, 30, 0)
            }
        }

        val textView = TextView(requireContext()).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        customLayout.addView(icon)
        customLayout.addView(textView)
        toast.view = customLayout
        toast.show()
    }
}

class LineChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val MODE_TRAINING = 0
        const val MODE_DETECTION = 1
    }

    private var mode = MODE_TRAINING
    private var dataPoints: List<Float> = emptyList()
    private var rawPoints: List<Float> = emptyList()

    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#88EF4444"); strokeWidth = 3f; pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f) }
    private val rawDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#555555"); style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#11FFFFFF"); strokeWidth = 2f }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#888888"); textSize = 22f; textAlign = Paint.Align.RIGHT }

    fun setMode(newMode: Int) { mode = newMode; invalidate() }

    fun setData(main: List<Float>, raw: List<Float> = emptyList()) {
        dataPoints = main; rawPoints = raw; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val paddingBottom = 36f
        val chartH = h - paddingBottom

        canvas.drawLine(0f, chartH * 0.5f, w, chartH * 0.5f, gridPaint)
        canvas.drawLine(0f, chartH * 0.25f, w, chartH * 0.25f, gridPaint)

        if (mode == MODE_DETECTION) {
            val alertY = chartH * 0.3f
            canvas.drawLine(0f, alertY, w, alertY, thresholdPaint)
            canvas.drawText("ALERT", w - 10f, alertY - 10f, labelPaint.apply { color = Color.parseColor("#EF4444") })
        }

        val maxVal = if (mode == MODE_TRAINING) max(dataPoints.maxOrNull() ?: 1f, 0.05f) else 100f
        val n = dataPoints.size
        val xStep = w / max(n - 1, 1).toFloat()

        if (mode == MODE_TRAINING && rawPoints.size == n) {
            for (i in 0 until n) {
                canvas.drawCircle(i * xStep, chartH - (rawPoints[i] / maxVal * chartH), 3.5f, rawDotPaint)
            }
        }

        val path = Path()
        val fillPath = Path()

        mainPaint.color = if (mode == MODE_TRAINING) Color.parseColor("#3B82F6") else Color.parseColor("#10B981")

        val startY = chartH - (dataPoints[0] / maxVal * chartH)
        path.moveTo(0f, startY)
        fillPath.moveTo(0f, chartH)
        fillPath.lineTo(0f, startY)

        for (i in 1 until n) {
            val x = i * xStep
            val y = chartH - (dataPoints[i] / maxVal * chartH)

            if (mode == MODE_DETECTION && dataPoints[i] >= 70f) {
                mainPaint.color = Color.parseColor("#EF4444")
            }

            val prevX = (i - 1) * xStep
            val prevY = chartH - (dataPoints[i - 1] / maxVal * chartH)
            val cpX = prevX + (x - prevX) / 2f

            path.cubicTo(cpX, prevY, cpX, y, x, y)
            fillPath.cubicTo(cpX, prevY, cpX, y, x, y)
        }

        fillPath.lineTo(w, chartH)
        fillPath.close()

        val gradColor = if (mode == MODE_TRAINING) "#443B82F6" else "#4410B981"
        fillPaint.shader = LinearGradient(0f, 0f, 0f, chartH, Color.parseColor(gradColor), Color.TRANSPARENT, Shader.TileMode.CLAMP)

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, mainPaint)

        val legendPaintObj = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#AAAAAA"); textSize = 24f }
        val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        if (mode == MODE_TRAINING) {
            swatchPaint.color = Color.parseColor("#3B82F6")
            canvas.drawRect(8f, chartH + 8f, 26f, chartH + 24f, swatchPaint)
            canvas.drawText("EMA Loss", 32f, chartH + 24f, legendPaintObj)

            swatchPaint.color = Color.parseColor("#555555")
            canvas.drawRect(150f, chartH + 8f, 168f, chartH + 24f, swatchPaint)
            canvas.drawText("Raw Loss", 174f, chartH + 24f, legendPaintObj)
        } else {
            swatchPaint.color = Color.parseColor("#10B981")
            canvas.drawRect(8f, chartH + 8f, 26f, chartH + 24f, swatchPaint)
            canvas.drawText("Live Risk Pulse", 32f, chartH + 24f, legendPaintObj)
        }
    }
}