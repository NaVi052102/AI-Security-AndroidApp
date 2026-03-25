package com.example.aisecurity.ai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.edit
import kotlinx.coroutines.*
import java.util.Locale
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

    private var currentActiveAppLabel = "📱 System Home"
    private var lastAppChangeTime = 0L

    // ==========================================
    // THE AEGIS SHIELD (Ultimate Forcefield)
    // ==========================================
    private var windowManager: WindowManager? = null
    private var aegisShieldView: View? = null
    private var isAegisDeployed = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val isLocked = prefs.getBoolean("is_system_locked", false)

        // =========================================================
        // THE VANGUARD: AEGIS PROTOCOL
        // =========================================================
        if (isLocked) {
            deployAegisShield() // Drop the impenetrable ceiling

            val pkg = event?.packageName?.toString()?.lowercase(Locale.ROOT) ?: ""

            // THE OEM-PROOF GUILLOTINE
            // Catches com.android.systemui, com.coloros.systemui, com.oplus.systemui, etc.
            if (pkg.contains("systemui") || pkg.contains("settings")) {
                serviceScope.launch(Dispatchers.Main) {
                    for (i in 1..5) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        delay(100)
                    }
                }
            }
            // The App Blocker
            else if (pkg.isNotEmpty() && !pkg.contains("com.example.aisecurity")) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return // Stop AI training
        } else {
            removeAegisShield() // Retract the ceiling when unlocked
        }

        // =========================================================
        // NORMAL OPERATION: BEHAVIORAL BIOMETRICS ENGINE
        // =========================================================
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

    // ==========================================
    // AEGIS SHIELD LOGIC
    // ==========================================
    private fun deployAegisShield() {
        if (isAegisDeployed || windowManager == null) return

        try {
            aegisShieldView = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                // Consume absolutely every touch event that hits the top 200 pixels
                setOnTouchListener { _, _ -> true }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                200, // Height of the forcefield
                // THE GOD-TIER LAYER: Sits physically above the hardware edge sensors
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

    // ... (Keep your learnTransition, processSwipe, increaseRisk, decreaseRisk, updateRiskScore, checkLock methods exactly as they were below here!) ...

    private suspend fun learnTransition(from: String, to: String, timeTaken: Long) { /* ... */ }
    private suspend fun processSwipe(duration: Float, velocity: Float, appLabel: String) { /* ... */ }
    private fun increaseRisk(amount: Int) { /* ... */ }
    private fun decreaseRisk(amount: Int) { /* ... */ }
    private fun updateRiskScore(newCalculatedRisk: Int) { /* ... */ }
    private fun checkLock(risk: Int) { /* ... */ }

    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        removeAegisShield()
        serviceScope.cancel()
    }
}