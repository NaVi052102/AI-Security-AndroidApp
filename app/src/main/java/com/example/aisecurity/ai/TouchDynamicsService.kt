package com.example.aisecurity.ai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import com.example.aisecurity.ui.LiveLogger
import kotlinx.coroutines.*
import kotlin.math.abs

class TouchDynamicsService : AccessibilityService() {

    private val classifier by lazy { BehavioralAuthClassifier(this) }
    private val enforcer by lazy { SecurityEnforcer(this) }
    private val db by lazy { SecurityDatabase.get(this) }
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private var swipeJob: Job? = null
    private var swipeStartTime = 0L
    private var eventCount = 0

    // =========================================================
    // THE PURE STATE MACHINE
    // =========================================================
    private var currentVisibleScreen = ""
    private var currentRealApp = ""

    // NEW: Tracks how fast the user jumps between apps
    private var lastAppSwitchTime = System.currentTimeMillis()
    private var currentTransitionSpeed = 0.5f // Default normalized speed

    private val systemNoiseList = listOf(
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.touchtype.swiftkey"
    )

    private val homeLaunchers = listOf(
        "com.miui.home",
        "com.mi.android.globallauncher",
        "com.mi.ui.poco.home",
        "com.sec.android.app.launcher",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.oneplus.setupwizard",
        "com.coloros.systemui"
    )

    private val knownAppOverrides = mapOf(
        "com.facebook.katana" to "Facebook",
        "com.facebook.orca" to "Messenger",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.instagram.android" to "Instagram",
        "com.google.android.youtube" to "YouTube",
        "com.whatsapp" to "WhatsApp",
        "com.twitter.android" to "X (Twitter)"
    )

    private fun getReadableAppName(context: Context, packageName: String): String {
        if (homeLaunchers.contains(packageName)) return "Home Screen"
        if (knownAppOverrides.containsKey(packageName)) return knownAppOverrides[packageName]!!

        val pm = context.packageManager
        return try {
            val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName.split(".").last().replaceFirstChar { it.uppercase() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // =========================================================
        // LOGIC 1: APP TRANSITIONS (Feature #4)
        // =========================================================
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val rawPackageName = event.packageName?.toString() ?: return

            if (systemNoiseList.contains(rawPackageName)) return

            val appName = getReadableAppName(this, rawPackageName)

            if (appName != currentVisibleScreen) {
                currentVisibleScreen = appName
            }

            if (appName != "Home Screen" && appName != currentRealApp) {
                val previousApp = currentRealApp
                currentRealApp = appName

                if (previousApp.isNotEmpty()) {
                    // Calculate how fast they switched apps (Normalized 0.0 - 1.0)
                    val now = System.currentTimeMillis()
                    val timeSinceLastApp = now - lastAppSwitchTime

                    // If they switch in 0s = 0.0f, if they stay for 10s = 1.0f
                    currentTransitionSpeed = (timeSinceLastApp.toFloat() / 10000f).coerceIn(0f, 1f)
                    lastAppSwitchTime = now

                    LiveLogger.log("📱 FLOW: $previousApp -> $currentRealApp")

                    serviceScope.launch {
                        learnTransition(previousApp, currentRealApp)
                    }
                }
            }
        }

        // =========================================================
        // LOGIC 2: SWIPE VELOCITY (Feature #1)
        // =========================================================
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (swipeStartTime == 0L) {
                swipeStartTime = System.currentTimeMillis()
            }
            eventCount++
            swipeJob?.cancel()

            swipeJob = serviceScope.launch {
                delay(400)

                val totalDuration = System.currentTimeMillis() - swipeStartTime
                val estimatedPixels = (eventCount * 75).toFloat()
                val velocity = if (totalDuration > 0) (estimatedPixels / totalDuration) * 1000 else 0f

                if (currentVisibleScreen.isNotEmpty()) {
                    processSwipe(totalDuration.toFloat(), velocity, currentVisibleScreen)
                }

                swipeStartTime = 0L
                eventCount = 0
            }
        }
    }

    private suspend fun learnTransition(from: String, to: String) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", false)

        if (isPaused && !isReady) return

        val history = db.dao().getTransition(from, to)

        if (history == null) {
            LiveLogger.log("🔄 Navigated from $from to $to")
            if (isReady) increaseRisk(10)
            db.dao().updateTransition(TransitionProfile(fromApp = from, toApp = to, avgTime = 1000L, frequency = 1))
        } else {
            db.dao().updateTransition(history.copy(frequency = history.frequency + 1))
            if (isReady) decreaseRisk(5)
        }
    }

    private suspend fun processSwipe(duration: Float, velocity: Float, appLabel: String) {
        if (duration < 50) return

        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", false)

        if (isPaused && !isReady) return

        // 1. Calculate App Usage Stats
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

        // =========================================================
        // 2. BUILD THE 4-FEATURE AI PAYLOAD (Normalized 0.0 - 1.0)
        // =========================================================
        val normVelocity = (velocity / 5000f).coerceIn(0f, 1f)
        val normPressure = 0.5f // Placeholder: Accessibility Services cannot read physical screen pressure
        val normAppUsage = (newStats.interactionCount.toFloat() / 100f).coerceIn(0f, 1f)
        val normTransition = currentTransitionSpeed

        // The exact 4 features the TFLite model is waiting for!
        val features = floatArrayOf(normVelocity, normPressure, normAppUsage, normTransition)

        val threshold = prefs.getFloat("threshold", 1.0f)

        // 3. Train or Infer
        if (!isReady) {
            db.dao().insertTouch(
                TouchProfile(duration = duration, velocityX = velocity, pressure = 0.5f, appName = appLabel)
            )
            classifier.trainAI(features)
        } else {
            var riskMultiplier = 1.0f

            if (abs(velocity - newStats.avgVelocity) > 1000) {
                riskMultiplier = 1.3f
                LiveLogger.log("⚠️ Speed Anomaly in $appLabel")
            }

            val error = classifier.getError(features) * riskMultiplier
            val swipeRisk = ((error / threshold) * 100).toInt()

            updateRiskScore(swipeRisk)
        }
    }

    private fun increaseRisk(amount: Int) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val current = prefs.getInt("current_risk", 0)
        prefs.edit().putInt("current_risk", current + amount).apply()
        checkLock(current + amount)
    }

    private fun decreaseRisk(amount: Int) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val current = prefs.getInt("current_risk", 0)
        prefs.edit().putInt("current_risk", (current - amount).coerceAtLeast(0)).apply()
    }

    private fun updateRiskScore(newCalculatedRisk: Int) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val oldRisk = prefs.getInt("current_risk", 0)
        val smoothedRisk = (oldRisk + newCalculatedRisk) / 2
        prefs.edit().putInt("current_risk", smoothedRisk).apply()
        checkLock(smoothedRisk)
    }

    private fun checkLock(risk: Int) {
        if (risk > 120) {
            serviceScope.launch(Dispatchers.Main) {
                LiveLogger.log("🚨 DEVICE LOCKED: Threat Detected")
                enforcer.lockDevice("AI Touch Dynamics Threat Detected")
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}