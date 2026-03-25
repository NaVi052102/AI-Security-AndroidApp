package com.example.aisecurity.ai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
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
        // THE VANGUARD: PARALYSIS PROTOCOL (Runs before ANY AI logic)
        // =========================================================
        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val isLocked = prefs.getBoolean("is_system_locked", false)

        if (isLocked) {
            // Instantly forces the phone back to the Home Screen to prevent app access
            performGlobalAction(GLOBAL_ACTION_HOME)

            // If they try to pull down the notification bar or open the recents menu, scramble it
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                performGlobalAction(GLOBAL_ACTION_HOME)
            }

            // CRITICAL: Return immediately so we don't train the AI on the thief's frantic tapping!
            return
        }

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

                    // BUG FIX: Lock the app names into immutable values (val) BEFORE launching
                    // the coroutine. This prevents the "Photos -> Photos" race condition!
                    val safeFromApp = currentActiveAppLabel
                    val safeToApp = newAppLabel

                    serviceScope.launch {
                        learnTransition(safeFromApp, safeToApp, timeTaken)
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

                // Pass the current context (e.g., "📱 System Home" or "YouTube") to the swipe logic
                processSwipe(totalDuration.toFloat(), velocity, currentActiveAppLabel)

                // Reset for the next swipe
                swipeStartTime = 0L
                eventCount = 0
            }
        }
    }

    private suspend fun learnTransition(from: String, to: String, timeTaken: Long) {
        // GATEKEEPER 2: The ultimate failsafe. If they are exactly the same, abort immediately.
        if (from == to) return

        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", false)

        // IGNORE APP SWITCHES IF TRAINING IS PAUSED
        if (isPaused && !isReady) {
            return
        }

        val history = db.dao().getTransition(from, to)

        if (history == null) {
            val severity = if (isReady) 1 else 0
            LiveLogger.logEvent(this@TouchDynamicsService, "New App Flow", "Navigated from $from to $to for the first time.", severity)

            if (isReady) increaseRisk(10)
            db.dao().updateTransition(TransitionProfile(fromApp = from, toApp = to, avgTime = timeTaken, frequency = 1))

        } else {
            val newAvg = ((history.avgTime * history.frequency) + timeTaken) / (history.frequency + 1)
            db.dao().updateTransition(history.copy(avgTime = newAvg, frequency = history.frequency + 1))

            if (isReady) {
                // If they switch apps 80% faster than normal, flag it
                if (timeTaken < (history.avgTime * 0.2)) {
                    LiveLogger.logEvent(this@TouchDynamicsService, "Fast App Jump", "Jumped from $from to $to in ${timeTaken}ms. Flagged as suspicious.", 1)
                    increaseRisk(15)
                } else {
                    LiveLogger.logEvent(this@TouchDynamicsService, "Verified Flow", "Normal navigation from $from to $to.", 0)
                    decreaseRisk(5)
                }
            }
        }
    }

    private suspend fun processSwipe(duration: Float, velocity: Float, appLabel: String) {
        // Ignore accidental tiny taps
        if (duration < 50) return

        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", false)

        if (isPaused && !isReady) {
            return
        }

        // Update Stats specifically for THIS app context
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

        // Setup the features array for the AI
        val features = floatArrayOf(duration, velocity, 0.5f, 0.5f, 0.5f)
        val threshold = prefs.getFloat("threshold", 1.0f)

        if (!isReady) {
            // TRAINING ON THE FLY
            db.dao().insertTouch(
                TouchProfile(duration = duration, velocityX = velocity, pressure = 0.5f, appName = appLabel)
            )

            val loss = classifier.trainAI(features)

            LiveLogger.logEvent(this@TouchDynamicsService, "AI Learning", "Context: $appLabel. Swipe matrix assimilated. Loss: ${String.format("%.4f", loss)}", 0)

        } else {
            // ARMED MODE: INFERENCE
            var riskMultiplier = 1.0f

            LiveLogger.logEvent(this@TouchDynamicsService, "Swipe Analyzed", "Context: $appLabel. Speed: ${velocity.toInt()} px/s.", 0)

            // Compare speed against THIS specific app's normal speed
            if (abs(velocity - newStats.avgVelocity) > 1000) {
                riskMultiplier = 1.3f
                LiveLogger.logEvent(this@TouchDynamicsService, "Speed Anomaly", "Context: $appLabel. Swipe speed ${velocity.toInt()}px/s (Expected ~${newStats.avgVelocity.toInt()}px/s).", 1)
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