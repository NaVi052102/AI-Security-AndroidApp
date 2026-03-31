package com.example.aisecurity.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit
import com.example.aisecurity.ui.LiveLogger
import com.example.aisecurity.ui.TrampolineActivity
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.abs

@Suppress("SpellCheckingInspection")
class TouchDynamicsService : AccessibilityService() {

    private val classifier by lazy { BehavioralAuthClassifier(this) }
    private val enforcer by lazy { SecurityEnforcer(this) }
    private val db by lazy { SecurityDatabase.get(this) }
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private var swipeJob: Job? = null
    private var swipeStartTime = 0L
    private var eventCount = 0

    private var currentActiveAppLabel = "📱 System Home"
    private var lastAppChangeTime = 0L

    private var windowManager: WindowManager? = null
    private var aegisShieldView: View? = null
    private var isAegisDeployed = false

    private var isPoltergeistActive = false
    private var activeMission = ""
    private var hasClickedSwitch = false
    private var lastAirplaneTriggerTime = 0L

    private val ghostReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // THE FIX: Total Try/Catch Armor
            try {
                if (intent?.action == "com.example.aisecurity.WAKE_MASTER_POLTERGEIST") {
                    val target = intent.getStringExtra("TARGET_SETTING") ?: return
                    launchTeleportingPoltergeist(target)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ==========================================
    // THE AIRPLANE MODE GUARDIAN
    // ==========================================
    private val airplaneGuardianReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (isInitialStickyBroadcast) return
                if (intent?.action == Intent.ACTION_AIRPLANE_MODE_CHANGED) {

                    if (context == null) return

                    // THE FIX: Persistent Hard-Drive Cooldown (Survives crashes!)
                    val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                    val currentTime = System.currentTimeMillis()
                    val lastTrigger = prefs.getLong("last_airplane_trigger", 0L)

                    if (currentTime - lastTrigger < 5000) return // 5-second global cooldown!

                    val isAirplaneModeOn = Settings.Global.getInt(
                        context.contentResolver,
                        Settings.Global.AIRPLANE_MODE_ON, 0
                    ) != 0

                    if (isAirplaneModeOn) {
                        // Save the new timestamp to the hard drive
                        prefs.edit().putLong("last_airplane_trigger", currentTime).apply()

                        val currentRisk = prefs.getInt("current_risk", 0)

                        if (currentRisk > 80) {
                            LiveLogger.log("⚠️ AI BEHAVIORAL BLOCK: High Risk ($currentRisk). Action Denied!")

                            serviceScope.launch(Dispatchers.Main) {
                                try {
                                    val lockIntent = Intent(context, com.example.aisecurity.ui.LockOverlayService::class.java)
                                    context.startService(lockIntent)
                                } catch (e: Exception) { e.printStackTrace() }

                                delay(1500)
                                launchTeleportingPoltergeist("AIRPLANE")
                            }
                        } else {
                            LiveLogger.log("🛡️ TSA CHECKPOINT: Verifying Owner Identity...")

                            try {
                                val authIntent = Intent(context, com.example.aisecurity.ui.GuardianAuthActivity::class.java)
                                authIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                context.startActivity(authIntent)
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ghostReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(ghostReceiver, filter)
        }

        val airplaneFilter = IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        registerReceiver(airplaneGuardianReceiver, airplaneFilter)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isLocked = prefs.getBoolean("is_system_locked", false)

        if (isLocked) {
            deployAegisShield()

            val pkg = event?.packageName?.toString()?.lowercase(Locale.ROOT) ?: ""
            val eventType = event?.eventType

            // 1. THE TRAMPOLINE BOMB
            if (pkg.contains("systemui") || eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                if (!isPoltergeistActive) {
                    val trampolineIntent = Intent(this, TrampolineActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                    startActivity(trampolineIntent)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    executeAntiGravitySwipe()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
            // 2. THE TELEPORTATION PROTOCOL (Settings Menu)
            else if (pkg.contains("com.android.settings") || pkg.contains("coloros") || pkg.contains("oplus") || pkg.contains("miui")) {
                if (!isPoltergeistActive) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
            // 3. THE APP BLOCKER
            else if (pkg.isNotEmpty() && !pkg.contains("com.example.aisecurity")) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        } else {
            removeAegisShield()
        }

        val currentTime = System.currentTimeMillis()
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val rawPackageName = event.packageName?.toString() ?: return
            val newAppLabel = AppMonitor.getFriendlyCategory(this, rawPackageName)
            if (newAppLabel != currentActiveAppLabel) {
                val timeTaken = currentTime - lastAppChangeTime
                if (lastAppChangeTime != 0L && timeTaken > 1000) {
                    val safeFromApp = currentActiveAppLabel
                    serviceScope.launch { learnTransition(safeFromApp, newAppLabel, timeTaken) }
                }
                currentActiveAppLabel = newAppLabel
                lastAppChangeTime = currentTime
            }
        }
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (swipeStartTime == 0L) { swipeStartTime = System.currentTimeMillis() }
            eventCount++
            swipeJob?.cancel()
            swipeJob = serviceScope.launch {
                delay(400)
                val totalDuration = System.currentTimeMillis() - swipeStartTime
                val estimatedPixels = (eventCount * 75).toFloat()
                val velocity = if (totalDuration > 0) (estimatedPixels / totalDuration) * 1000 else 0f
                processSwipe(totalDuration.toFloat(), velocity, currentActiveAppLabel)
                swipeStartTime = 0L
                eventCount = 0
            }
        }
    }

    private fun launchTeleportingPoltergeist(target: String) {
        if (isPoltergeistActive) return

        isPoltergeistActive = true

        val missions = if (target == "ALL") {
            listOf("AIRPLANE", "LOCATION", "BLUETOOTH", "DATA")
        } else {
            listOf(target)
        }

        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)

        serviceScope.launch(Dispatchers.Main) {

            for (mission in missions) {
                activeMission = mission
                hasClickedSwitch = false

                val intentAction = when (mission) {
                    "BLUETOOTH" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "LOCATION" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    "AIRPLANE" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                    "DATA" -> {
                        if (manufacturer.contains("realme") || manufacturer.contains("oppo") || manufacturer.contains("oneplus")) {
                            Settings.ACTION_WIRELESS_SETTINGS
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            Settings.ACTION_DATA_USAGE_SETTINGS
                        } else {
                            Settings.ACTION_WIRELESS_SETTINGS
                        }
                    }
                    else -> Settings.ACTION_BLUETOOTH_SETTINGS
                }

                try {
                    val settingsIntent = Intent(intentAction)
                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    startActivity(settingsIntent)

                    var attempts = 0
                    while (isPoltergeistActive && !hasClickedSwitch && attempts < 8) {
                        delay(500)
                        val rootNode = rootInActiveWindow
                        if (rootNode != null) {
                            val turnOn = mission != "AIRPLANE"
                            executeSurgicalClick(rootNode, turnOn)
                        }
                        attempts++
                    }

                    if (hasClickedSwitch) {
                        delay(600)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            performGlobalAction(GLOBAL_ACTION_HOME)
            isPoltergeistActive = false
            activeMission = ""
        }
    }

    @Suppress("DEPRECATION")
    private fun executeSurgicalClick(node: AccessibilityNodeInfo?, turnOn: Boolean): Boolean {
        if (node == null || hasClickedSwitch) return false

        val className = node.className?.toString() ?: ""

        if (className.contains("Switch") || className.contains("ToggleButton") || node.isCheckable) {

            val needsClick = if (turnOn) !node.isChecked else node.isChecked

            if (needsClick) {
                hasClickedSwitch = true

                val directClick = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                if (!directClick || !node.isClickable) {
                    var clickTarget: AccessibilityNodeInfo? = node.parent
                    while (clickTarget != null && !clickTarget.isClickable) {
                        clickTarget = clickTarget.parent
                    }
                    clickTarget?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                return true
            } else {
                hasClickedSwitch = true
                return true
            }
        }

        for (i in 0 until node.childCount) {
            if (executeSurgicalClick(node.getChild(i), turnOn)) return true
        }
        return false
    }

    private fun executeAntiGravitySwipe() {
        try {
            val displayMetrics = resources.displayMetrics
            val middleX = displayMetrics.widthPixels / 2f
            val startY = displayMetrics.heightPixels / 2f
            val endY = 0f

            val path = Path()
            path.moveTo(middleX, startY)
            path.lineTo(middleX, endY)

            val strokeDescription = GestureDescription.StrokeDescription(path, 0, 50)
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(strokeDescription)

            dispatchGesture(gestureBuilder.build(), null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun deployAegisShield() {
        if (isAegisDeployed || windowManager == null) return

        try {
            aegisShieldView = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnTouchListener { _, _ -> true }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                200,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP

            windowManager?.addView(aegisShieldView, params)
            isAegisDeployed = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeAegisShield() {
        if (!isAegisDeployed || windowManager == null || aegisShieldView == null) return
        try {
            windowManager?.removeView(aegisShieldView)
            isAegisDeployed = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun learnTransition(from: String, to: String, timeTaken: Long) {
        if (from == to) return

        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", false)

        if (isPaused && !isReady) return

        val history = db.dao().getTransition(from, to)

        if (history == null) {
            LiveLogger.log("New App Flow: Navigated from $from to $to.")

            if (isReady) increaseRisk(10)
            db.dao().updateTransition(TransitionProfile(fromApp = from, toApp = to, avgTime = timeTaken, frequency = 1))

        } else {
            val newAvg = ((history.avgTime * history.frequency) + timeTaken) / (history.frequency + 1)
            db.dao().updateTransition(history.copy(avgTime = newAvg, frequency = history.frequency + 1))

            if (isReady) {
                if (timeTaken < (history.avgTime * 0.2)) {
                    LiveLogger.log("Fast App Jump: Jumped from $from to $to in ${timeTaken}ms.")
                    increaseRisk(15)
                } else {
                    decreaseRisk(5)
                }
            }
        }
    }

    private suspend fun processSwipe(duration: Float, velocity: Float, appLabel: String) {
        if (duration < 50) return

        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", false)

        if (isPaused && !isReady) return

        val oldStats = db.dao().getAppStats(appLabel)
        val newStats = if (oldStats == null) {
            AppUsageProfile(appLabel, velocity, duration, 1)
        } else {
            val count = oldStats.interactionCount
            val newAvgVel = ((oldStats.avgVelocity * count) + velocity) / (count + 1)
            val newAvgDur = ((oldStats.avgDuration * count) + duration) / (count + 1)
            AppUsageProfile(appLabel, newAvgVel, newAvgDur, count + 1)
        }
        db.dao().updateAppStats(newStats)

        val features = floatArrayOf(duration, velocity, 0.5f, 0.5f, 0.5f)
        val threshold = prefs.getFloat("threshold", 1.0f)

        if (!isReady) {
            db.dao().insertTouch(TouchProfile(duration = duration, velocityX = velocity, pressure = 0.5f, appName = appLabel))
            val loss = classifier.trainAI(features)
            LiveLogger.log("AI Learning: Context: $appLabel. Loss: ${String.format(Locale.US, "%.4f", loss)}")
        } else {
            var riskMultiplier = 1.0f
            if (abs(velocity - newStats.avgVelocity) > 1000) {
                riskMultiplier = 1.3f
                LiveLogger.log("Speed Anomaly: Swipe speed ${velocity.toInt()}px/s.")
            }
            val error = classifier.getError(features) * riskMultiplier
            val swipeRisk = ((error / threshold) * 100).toInt()
            updateRiskScore(swipeRisk)
        }
    }

    private fun increaseRisk(amount: Int) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val current = prefs.getInt("current_risk", 0)
        prefs.edit { putInt("current_risk", current + amount) }
        checkLock(current + amount)
    }

    @Suppress("SameParameterValue") // Silences the 'always 5' warning!
    private fun decreaseRisk(amount: Int) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val current = prefs.getInt("current_risk", 0)
        prefs.edit { putInt("current_risk", (current - amount).coerceAtLeast(0)) }
    }

    private fun updateRiskScore(newCalculatedRisk: Int) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val oldRisk = prefs.getInt("current_risk", 0)
        val smoothedRisk = (oldRisk + newCalculatedRisk) / 2
        prefs.edit { putInt("current_risk", smoothedRisk) }
        checkLock(smoothedRisk)
    }

    private fun checkLock(risk: Int) {
        if (risk > 120) {
            serviceScope.launch(Dispatchers.Main) {
                enforcer.lockDevice("AI Touch Dynamics Threat Detected")
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        removeAegisShield()
        try {
            unregisterReceiver(ghostReceiver)
            unregisterReceiver(airplaneGuardianReceiver)
        } catch (_: IllegalArgumentException) {
            // Safe ignore
        }
        serviceScope.cancel()
    }
}