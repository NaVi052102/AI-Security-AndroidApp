package com.example.aisecurity.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.aisecurity.ui.LiveLogger
import com.example.aisecurity.ui.LockOverlayService
import com.example.aisecurity.ui.TrampolineActivity
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.abs

@SuppressLint("MissingPermission")
@Suppress(
    "SpellCheckingInspection",
    "DEPRECATION",
    "UNUSED_VARIABLE",
    "UNUSED_PARAMETER",
    "ApplySharedPref",
    "CommitPrefEdits",
    "RemoveRedundantQualifierName"
)
class TouchDynamicsService : AccessibilityService() {

    private val classifier by lazy { BehavioralAuthClassifier(this) }
    private val enforcer by lazy { SecurityEnforcer(this) }
    private val db by lazy { SecurityDatabase.get(this) }
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private var swipeJob: Job? = null
    private var swipeStartTime = 0L
    private var eventCount = 0

    private var currentVisibleScreen = ""
    private var currentRealApp = ""
    private var lastAppSwitchTime = System.currentTimeMillis()
    private var currentTransitionSpeed = 0.5f
    private var lastGuillotineTime = 0L

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

    private var windowManager: WindowManager? = null
    private var aegisShieldView: View? = null
    private var isAegisDeployed = false
    private var isPoltergeistActive = false

    // =========================================================
    // NATIVE OS BIOMETRIC SYNC (The Background Listener)
    // =========================================================
    private val osBiometricSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // This broadcast ONLY fires when the true owner passes the native OS lock screen
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
                val isLocked = prefs.getBoolean("is_system_locked", false)

                if (isLocked) {
                    LiveLogger.log("🔓 OS BIOMETRIC SYNC: True Owner Verified by Android OS in the background.")

                    prefs.edit()
                        .putBoolean("is_system_locked", false)
                        .putBoolean("is_auth_in_progress", false)
                        .putInt("current_risk", 0)
                        .apply()

                    // Instantly vaporize the Red Lock Overlay
                    try {
                        val lockIntent = Intent(this@TouchDynamicsService, LockOverlayService::class.java)
                        stopService(lockIntent)
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        }
    }

    private val ghostReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action == "com.example.aisecurity.WAKE_MASTER_POLTERGEIST") {
                    val target = intent.getStringExtra("TARGET_SETTING") ?: return

                    if (target != "AIRPLANE" && target != "LOCK_AND_RECOVER_AIRPLANE") {
                        launchTeleportingPoltergeist(target)
                    } else if (target == "LOCK_AND_RECOVER_AIRPLANE") {
                        try {
                            val lockIntent = Intent(this@TouchDynamicsService, LockOverlayService::class.java)
                            this@TouchDynamicsService.startService(lockIntent)
                        } catch (_: Exception) { }
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

        // Register Poltergeist
        val filter = IntentFilter("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ghostReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(ghostReceiver, filter)
        }

        // Register Native OS Biometric Sync Listener
        val userPresentFilter = IntentFilter(Intent.ACTION_USER_PRESENT)
        registerReceiver(osBiometricSyncReceiver, userPresentFilter)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)

        // CEASEFIRE PROTOCOL: If the OS Biometric prompt is active, ignore everything!
        if (prefs.getBoolean("is_auth_in_progress", false)) return

        val isLocked = prefs.getBoolean("is_system_locked", false)
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

        val pkg = event?.packageName?.toString()?.lowercase(Locale.ROOT) ?: ""
        val className = event?.className?.toString()?.lowercase(Locale.ROOT) ?: ""
        val eventType = event?.eventType

        // =========================================================
        // THE SCRIM-SNIPER GUILLOTINE (Active ONLY when Locked)
        // =========================================================
        val isEnvironmentHostile = km.isKeyguardLocked || isLocked

        if (isEnvironmentHostile && pkg == "com.android.systemui") {
            if (className.contains("panel", true) ||
                className.contains("notification", true) ||
                className.contains("expand", true) ||
                className.contains("settings", true) ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {

                triggerScrimSniper()
                return
            }
        }

        // =========================================================
        // EXISTING LOCKDOWN LOGIC (Trampoline fallback)
        // =========================================================
        if (isLocked) {
            deployAegisShield()

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
            } else if (pkg.contains("com.android.settings") || pkg.contains("coloros") || pkg.contains("oplus") || pkg.contains("miui")) {
                if (!isPoltergeistActive) performGlobalAction(GLOBAL_ACTION_HOME)
            } else if (pkg.isNotEmpty() && !pkg.contains("com.example.aisecurity")) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        } else {
            removeAegisShield()
        }

        // =========================================================
        // BEHAVIORAL BIOMETRICS TRACKING (Runs transparently)
        // =========================================================
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (systemNoiseList.contains(pkg)) return
            val appName = getReadableAppName(this, pkg)

            if (appName != currentVisibleScreen) {
                currentVisibleScreen = appName
            }

            if (appName != "Home Screen" && appName != currentRealApp) {
                val previousApp = currentRealApp
                currentRealApp = appName

                if (previousApp.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val timeSinceLastApp = now - lastAppSwitchTime

                    currentTransitionSpeed = (timeSinceLastApp.toFloat() / 10000f).coerceIn(0f, 1f)
                    lastAppSwitchTime = now
                    serviceScope.launch { learnTransition(previousApp, currentRealApp) }
                }
            }
        }

        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (swipeStartTime == 0L) swipeStartTime = System.currentTimeMillis()
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

    // =========================================================
    // THE SCRIM-SNIPER EXECUTION
    // =========================================================
    private fun triggerScrimSniper() {
        val now = System.currentTimeMillis()
        if (now - lastGuillotineTime < 300) return
        lastGuillotineTime = now

        LiveLogger.log("🛡️ SCRIM SNIPER: Tapping bottom screen to abort Quick Settings!")

        serviceScope.launch(Dispatchers.Main) {
            repeat(5) {
                executeBottomScreenTap()
                performGlobalAction(GLOBAL_ACTION_BACK)

                try {
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                } catch (_: Exception) {}

                delay(50)
            }
        }
    }

    private fun executeBottomScreenTap() {
        try {
            val metrics = resources.displayMetrics
            val midX = metrics.widthPixels / 2f
            val bottomY = metrics.heightPixels * 0.85f

            val path = Path().apply {
                moveTo(midX, bottomY)
                lineTo(midX + 1f, bottomY + 1f)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 30))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) { e.printStackTrace() }
    }

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

    // =========================================================
    // POLTERGEIST TERMINATOR FUNCTIONS
    // =========================================================
    private fun launchTeleportingPoltergeist(target: String) {
        if (isPoltergeistActive) return

        isPoltergeistActive = true
        val missions = if (target == "ALL") listOf("LOCATION", "BLUETOOTH", "DATA") else listOf(target)
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)

        serviceScope.launch(Dispatchers.Main) {
            for (mission in missions) {

                val initiallyNeedsToggle = try {
                    when (mission) {
                        "BLUETOOTH" -> (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == false
                        "LOCATION" -> !(getSystemService(LOCATION_SERVICE) as LocationManager).isProviderEnabled(LocationManager.GPS_PROVIDER)
                        else -> true
                    }
                } catch (_: Exception) { true }

                if (!initiallyNeedsToggle) continue

                val intentAction = when (mission) {
                    "BLUETOOTH" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "LOCATION" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
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

                    delay(800)

                    var attempts = 0
                    var tapFiredForData = false

                    while (isPoltergeistActive && attempts < 15) {

                        val stillNeedsToggle = try {
                            when (mission) {
                                "BLUETOOTH" -> (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == false
                                "LOCATION" -> !(getSystemService(LOCATION_SERVICE) as LocationManager).isProviderEnabled(LocationManager.GPS_PROVIDER)
                                "DATA" -> !tapFiredForData
                                else -> false
                            }
                        } catch (_: Exception) { !tapFiredForData }

                        if (!stillNeedsToggle) break

                        try {
                            var firedThisLoop = false
                            val activeRoot = rootInActiveWindow

                            if (activeRoot != null) firedThisLoop = nukeSwitch(activeRoot, mission)

                            if (!firedThisLoop) {
                                for (window in windows) {
                                    if (nukeSwitch(window.root, mission)) {
                                        firedThisLoop = true
                                        break
                                    }
                                }
                            }

                            if (firedThisLoop) {
                                tapFiredForData = true
                                delay(2000)
                            } else {
                                delay(300)
                            }

                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        attempts++
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            performGlobalAction(GLOBAL_ACTION_HOME)
            isPoltergeistActive = false
        }
    }

    private fun fireHumanTap(x: Float, y: Float) {
        try {
            val path = Path().apply {
                moveTo(x - 1f, y - 1f)
                lineTo(x + 1f, y + 1f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 150))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private suspend fun nukeSwitch(rootNode: AccessibilityNodeInfo?, mission: String): Boolean {
        if (rootNode == null) return false

        val keywords = when (mission) {
            "BLUETOOTH" -> listOf("bluetooth")
            "LOCATION" -> listOf("location", "gps")
            "DATA" -> listOf("mobile data", "cellular", "data usage", "sim")
            else -> emptyList()
        }

        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            allNodes.add(node)
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(rootNode)

        val windowRect = Rect()
        rootNode.getBoundsInScreen(windowRect)
        var screenWidth = resources.displayMetrics.widthPixels.toFloat()
        if (screenWidth <= 0) screenWidth = windowRect.width().toFloat()

        var struck = false

        for (node in allNodes) {
            val t = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            val c = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""

            if (keywords.any { k -> t.contains(k) || c.contains(k) }) {
                val rect = Rect()
                node.getBoundsInScreen(rect)

                if (rect.height() < 400 && rect.centerY() > 100) {
                    val y = rect.centerY().toFloat()

                    var p: AccessibilityNodeInfo? = node
                    var levels = 0
                    while (p != null && levels < 5) {
                        p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        p = p.parent
                        levels++
                    }

                    if (screenWidth > 0) {
                        delay(100)
                        fireHumanTap(screenWidth * 0.88f, y)
                    } else {
                        fireHumanTap(rect.centerX().toFloat(), y)
                    }

                    struck = true
                    break
                }
            }
        }
        return struck
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
        } catch (e: Exception) { e.printStackTrace() }
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
                WindowManager.LayoutParams.MATCH_PARENT, 200,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP
            windowManager?.addView(aegisShieldView, params)
            isAegisDeployed = true
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun removeAegisShield() {
        if (!isAegisDeployed || windowManager == null || aegisShieldView == null) return
        try {
            windowManager?.removeView(aegisShieldView)
            isAegisDeployed = false
        } catch (e: Exception) { e.printStackTrace() }
    }

    // =========================================================
    //  AI TRAINING & CLASSIFICATION
    // =========================================================
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

        val normVelocity = (velocity / 5000f).coerceIn(0f, 1f)
        val normPressure = 0.5f
        val normAppUsage = (newStats.interactionCount.toFloat() / 100f).coerceIn(0f, 1f)
        val normTransition = currentTransitionSpeed

        val features = floatArrayOf(normVelocity, normPressure, normAppUsage, normTransition)
        val threshold = prefs.getFloat("threshold", 1.0f)

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
        removeAegisShield()
        try {
            unregisterReceiver(ghostReceiver)
            unregisterReceiver(osBiometricSyncReceiver)
        } catch (_: IllegalArgumentException) {}
        serviceScope.cancel()
    }
}