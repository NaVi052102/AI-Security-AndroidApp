package com.example.aisecurity.ai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent // --- FIXED: Missing Intent import ---
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.edit // --- FIXED: Modern SharedPreferences KTX ---
import kotlinx.coroutines.*
import java.util.Locale // --- FIXED: Missing Locale import ---
import kotlin.math.abs

class TouchDynamicsService : AccessibilityService() {

    private val classifier by lazy { BehavioralAuthClassifier(this) }
    private val enforcer by lazy { SecurityEnforcer(this) }
    private val db by lazy { SecurityDatabase.get(this) }
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    // --- VARIABLES ---
    private var swipeJob: Job? = null
    private var swipeStartTime = 0L
    private var eventCount = 0

    // Memory for App Transitions & Context
    private var currentActiveAppLabel = "📱 System Home"
    private var lastAppChangeTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        // =========================================================
        // THE VANGUARD: PARALYSIS PROTOCOL
        // =========================================================
        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val isLocked = prefs.getBoolean("is_system_locked", false)

        if (isLocked) {
            val pkg = event?.packageName?.toString()

            // 1. The Guillotine: If they touch the status bar/quick settings, slam it shut!
            if (pkg == "com.android.systemui") {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            // 2. The App Blocker: If they try to open ANY app (like GCash) beneath our overlay,
            // snap the phone back to the Home Screen instantly.
            else if (pkg != null && pkg != "com.example.aisecurity") {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }

            return // CRITICAL: Stop the AI from learning the thief's panicked taps
        }

        // ... (Keep the rest of your Behavioral Biometrics code here) ...

        // =========================================================
        // NORMAL OPERATION: BEHAVIORAL BIOMETRICS ENGINE
        // =========================================================
        val currentTime = System.currentTimeMillis()

        // LOGIC 1: APP FLOW (The Gatekeeper)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val rawPackageName = event.packageName?.toString() ?: return
            val newAppLabel = AppMonitor.getFriendlyCategory(this, rawPackageName)

            // GATEKEEPER 1: Only trigger if we actually moved to a DIFFERENT app
            if (newAppLabel != currentActiveAppLabel) {

                val timeTaken = currentTime - lastAppChangeTime

                // Add a 1000ms THROTTLE to prevent spamming the database
                if (lastAppChangeTime != 0L && timeTaken > 1000) {

                    // FIXED: Lock the changing variable into a static val,
                    // and inline the newAppLabel as suggested by the IDE.
                    val safeFromApp = currentActiveAppLabel

                    serviceScope.launch {
                        learnTransition(safeFromApp, newAppLabel, timeTaken)
                    }
                }

                // Update memory for the next switch
                currentActiveAppLabel = newAppLabel
                lastAppChangeTime = currentTime
            }
        }

        // LOGIC 2: SWIPE DETECTION (Context-Aware)
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (swipeStartTime == 0L) {
                swipeStartTime = System.currentTimeMillis()
            }
            eventCount++
            swipeJob?.cancel()

            swipeJob = serviceScope.launch {
                delay(400) // Wait to see if the swipe is completely finished

                val totalDuration = System.currentTimeMillis() - swipeStartTime
                val estimatedPixels = (eventCount * 75).toFloat()
                val velocity = if (totalDuration > 0) (estimatedPixels / totalDuration) * 1000 else 0f

                // Pass the current context to the swipe logic
                processSwipe(totalDuration.toFloat(), velocity, currentActiveAppLabel)

                // Reset for the next swipe
                swipeStartTime = 0L
                eventCount = 0
            }
        }
    }

    private suspend fun learnTransition(from: String, to: String, timeTaken: Long) {
        if (from == to) return

        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", false)

        if (isPaused && !isReady) {
            return
        }

        val history = db.dao().getTransition(from, to)

        if (history == null) {
            val severity = if (isReady) 1 else 0
            LiveLogger.logEvent(this, "New App Flow", "Navigated from $from to $to for the first time.", severity)

            if (isReady) increaseRisk(10)
            db.dao().updateTransition(TransitionProfile(fromApp = from, toApp = to, avgTime = timeTaken, frequency = 1))

        } else {
            val newAvg = ((history.avgTime * history.frequency) + timeTaken) / (history.frequency + 1)
            db.dao().updateTransition(history.copy(avgTime = newAvg, frequency = history.frequency + 1))

            if (isReady) {
                if (timeTaken < (history.avgTime * 0.2)) {
                    LiveLogger.logEvent(this, "Fast App Jump", "Jumped from $from to $to in ${timeTaken}ms. Flagged as suspicious.", 1)
                    increaseRisk(15)
                } else {
                    LiveLogger.logEvent(this, "Verified Flow", "Normal navigation from $from to $to.", 0)
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

        if (isPaused && !isReady) {
            return
        }

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

        // FIXED GRAMMAR: Set up the features array for the AI
        val features = floatArrayOf(duration, velocity, 0.5f, 0.5f, 0.5f)
        val threshold = prefs.getFloat("threshold", 1.0f)

        if (!isReady) {
            db.dao().insertTouch(
                TouchProfile(duration = duration, velocityX = velocity, pressure = 0.5f, appName = appLabel)
            )

            val loss = classifier.trainAI(features)

            // FIXED LOCALE: Added Locale.US to String.format
            LiveLogger.logEvent(this, "AI Learning", "Context: $appLabel. Swipe matrix assimilated. Loss: ${String.format(Locale.US, "%.4f", loss)}", 0)

        } else {
            var riskMultiplier = 1.0f

            LiveLogger.logEvent(this, "Swipe Analyzed", "Context: $appLabel. Speed: ${velocity.toInt()} px/s.", 0)

            if (abs(velocity - newStats.avgVelocity) > 1000) {
                riskMultiplier = 1.3f
                LiveLogger.logEvent(this, "Speed Anomaly", "Context: $appLabel. Swipe speed ${velocity.toInt()}px/s (Expected ~${newStats.avgVelocity.toInt()}px/s).", 1)
            }

            val error = classifier.getError(features) * riskMultiplier
            val swipeRisk = ((error / threshold) * 100).toInt()

            updateRiskScore(swipeRisk)
        }
    }

    private fun increaseRisk(amount: Int) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val current = prefs.getInt("current_risk", 0)
        // FIXED: Used modern KTX edit block
        prefs.edit { putInt("current_risk", current + amount) }
        checkLock(current + amount)
    }

    private fun decreaseRisk(amount: Int) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val current = prefs.getInt("current_risk", 0)
        // FIXED: Used modern KTX edit block
        prefs.edit { putInt("current_risk", (current - amount).coerceAtLeast(0)) }
    }

    private fun updateRiskScore(newCalculatedRisk: Int) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val oldRisk = prefs.getInt("current_risk", 0)

        val smoothedRisk = (oldRisk + newCalculatedRisk) / 2
        // FIXED: Used modern KTX edit block
        prefs.edit { putInt("current_risk", smoothedRisk) }
        checkLock(smoothedRisk)
    }

    private fun checkLock(risk: Int) {
        if (risk > 120) {
            serviceScope.launch(Dispatchers.Main) {
                LiveLogger.logEvent(this@TouchDynamicsService, "DEVICE LOCKED", "Intruder detected via AI Touch Dynamics. Risk score exceeded limits.", 2)
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