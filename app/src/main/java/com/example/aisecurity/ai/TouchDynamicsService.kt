// ... Keep your imports exactly as they are ...
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
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.aisecurity.ui.LiveLogger
import com.example.aisecurity.ui.LockOverlayService
import kotlinx.coroutines.*
import java.util.Locale

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

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var swipeJob: Job? = null
    private var swipeStartTime = 0L
    private var eventCount = 0

    private var currentVisibleScreen = "Home Screen"
    private var currentRealApp = ""
    private var lastAppSwitchTime = System.currentTimeMillis()
    private var currentTransitionSpeed = 0.5f

    private var lastGuillotineTime = 0L
    private var lastRealAppLeaveTime = 0L
    private var isCurrentlyInNoise = false

    private var lastFromApp = "System UI"
    private var lastToApp = "Monitoring..."

    private var isLockdownCooldown = false
    private var lastUnlockTime = 0L

    private val systemNoiseList = listOf(
        "com.android.systemui",
        "com.android.systemui.plugin",
        "com.google.android.googlequicksearchbox",
        "com.google.android.inputmethod.latin",
        "com.touchtype.swiftkey",
        "com.google.android.gms",
        "android"
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
        "com.ss.android.ugc.trill" to "TikTok",
        "com.instagram.android" to "Instagram",
        "com.google.android.youtube" to "YouTube",
        "com.whatsapp" to "WhatsApp",
        "com.twitter.android" to "X (Twitter)",
        "com.mobile.legends" to "Mobile Legends"
    )

    private var windowManager: WindowManager? = null
    private var aegisShieldView: View? = null
    private var isAegisDeployed = false
    private var isPoltergeistActive = false
    private var isWatchSyncLoopRunning = false

    private val osBiometricSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
                lastUnlockTime = System.currentTimeMillis()
                isLockdownCooldown = false

                LiveLogger.log("🔓 OS UNLOCK: True Owner Verified. 3-Second Grace Period Started.")
                prefs.edit()
                    .putBoolean("is_system_locked", false)
                    .putBoolean("is_auth_in_progress", false)
                    .putInt("current_risk", 0)
                    .apply()

                try {
                    val lockIntent = Intent(this@TouchDynamicsService, LockOverlayService::class.java)
                    stopService(lockIntent)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private val ghostReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action == "com.example.aisecurity.WAKE_MASTER_POLTERGEIST") {
                    val target = intent.getStringExtra("TARGET_SETTING") ?: return
                    if (target == "FORCE_SLEEP") {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            serviceScope.launch(Dispatchers.Main) {
                                delay(300)
                                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                            }
                        }
                    } else if (target != "AIRPLANE" && target != "LOCK_AND_RECOVER_AIRPLANE") {
                        launchTeleportingPoltergeist(target)
                    } else if (target == "LOCK_AND_RECOVER_AIRPLANE") {
                        try {
                            val lockIntent = Intent(this@TouchDynamicsService, LockOverlayService::class.java)
                            this@TouchDynamicsService.startService(lockIntent)
                        } catch (_: Exception) { }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ghostReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(ghostReceiver, filter)
        }

        val userPresentFilter = IntentFilter(Intent.ACTION_USER_PRESENT)
        registerReceiver(osBiometricSyncReceiver, userPresentFilter)
        startWatchSyncLoop()
    }

    private fun getContextHash(text: String): Float {
        return (kotlin.math.abs(text.hashCode()) % 1000) / 1000f
    }

    private suspend fun runContextualAI(
        velocity: Float,
        duration: Float,
        appName: String,
        fromApp: String,
        transitionTime: Long
    ) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", true)

        val globalThreshold = prefs.getFloat("threshold", 0.08f)
        val targetStability = globalThreshold * 1.2f
        val baselineEma = 1.0f

        val stats = db.dao().getAppStats(appName)
        val interactionCount = stats?.interactionCount ?: 0
        var appSpecificEma = prefs.getFloat("ema_loss_$appName", 1.0f)

        // 🚨 Calculate the exact % this app is currently at
        val progressRaw = ((baselineEma - appSpecificEma) / (baselineEma - targetStability)).coerceIn(0f, 1f)
        val finalPct = (progressRaw * 100f).toInt()

        val normVelocity    = (velocity / 5000f).coerceIn(0f, 1f)
        val normPressure    = if (duration == 0f) 0.0f else 0.5f
        val normAppUsage    = (interactionCount.toFloat() / 100f).coerceIn(0f, 1f)
        val normTransition  = (transitionTime.toFloat() / 10000f).coerceIn(0f, 1f)
        val appID           = getContextHash(appName)
        val transitionID    = getContextHash("$fromApp->$appName")

        val features = floatArrayOf(
            normVelocity, normPressure, normAppUsage, normTransition, appID, transitionID
        )

        if (isReady) {
            // 🛡️ AI PROTECTION IS ACTIVE
            if (finalPct >= 75) {
                // This app is trained! Evaluate intruder risk.
                val error = classifier.getError(features)
                val ratio = error / globalThreshold
                val swipeRisk = if (ratio <= 1.0f) {
                    (ratio * 25f).toInt()
                } else {
                    (25f + ((ratio - 1.0f) * 75f)).toInt()
                }.coerceIn(0, 100)

                updateRiskScore(swipeRisk)

                if (swipeRisk < 35) {
                    val rawLoss = classifier.trainAI(features)
                    appSpecificEma = BehavioralAuthClassifier.emaStep(appSpecificEma, rawLoss)
                    prefs.edit().putFloat("ema_loss_$appName", appSpecificEma).apply()
                    db.dao().insertTouch(TouchProfile(duration = duration, velocityX = velocity, pressure = normPressure, appName = appName))
                } else {
                    LiveLogger.log("⚠️ [THREAT] High Risk in ARMED APP ($appName)! Risk: $swipeRisk%")
                }
            } else {
                // 🛑 STRICT SUSPENSION: App is < 75%. The AI completely ignores it.
                // No silent training. No risk evaluation.
                LiveLogger.log("🛡️ [IGNORED] $appName is only $finalPct% trained. Intruder detection disabled.")

                // Allow the global risk meter to slightly cool down since they are in a safe/untracked app
                updateRiskScore(0)
            }
        } else if (!isPaused) {
            // 🧠 AI IS NOT ARMED (LEARNING MODE IS ACTIVE)
            val rawLoss = classifier.trainAI(features)
            appSpecificEma = BehavioralAuthClassifier.emaStep(appSpecificEma, rawLoss)
            prefs.edit().putFloat("ema_loss_$appName", appSpecificEma).apply()

            db.dao().insertTouch(TouchProfile(duration = duration, velocityX = velocity, pressure = normPressure, appName = appName))
            db.dao().insertLossPoint(LossPoint(timestamp = System.currentTimeMillis(), lossValue = rawLoss, emaValue = appSpecificEma))

            LiveLogger.log("📉 [TRAIN] $appName | EMA: ${"%.4f".format(appSpecificEma)}")
            updateRiskScore(0)
        }
    }

    private fun startWatchSyncLoop() {
        if (!isWatchSyncLoopRunning) {
            isWatchSyncLoopRunning = true
            serviceScope.launch {
                var loopCount = 0
                while (isActive) {
                    try { syncBiometricsToWatch(loopCount) } catch (_: Exception) {}
                    loopCount++
                    delay(1000)
                }
            }
        }
    }

    private fun getReadableAppName(packageName: String): String {
        if (homeLaunchers.contains(packageName) || packageName.contains("launcher")) return "Home Screen"
        if (knownAppOverrides.containsKey(packageName)) return knownAppOverrides[packageName]!!
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.split(".").last().replaceFirstChar { it.uppercase() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        startWatchSyncLoop()

        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("is_auth_in_progress", false)) return

        val isLocked = prefs.getBoolean("is_system_locked", false)
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

        val rawPackageName = event?.packageName?.toString()?.lowercase(Locale.ROOT) ?: ""
        val className = event?.className?.toString()?.lowercase(Locale.ROOT) ?: ""
        val eventType = event?.eventType

        val isEnvironmentHostile = km.isKeyguardLocked || isLocked

        // =========================================================
        // 🚨 NEW: THE PHANTOM POWER-OFF INTERCEPTOR
        // =========================================================
        val textNodes = event?.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val isPowerMenu = className.contains("globalactions", true) ||
                textNodes.contains("power off") ||
                textNodes.contains("restart") ||
                textNodes.contains("shut down") ||
                textNodes.contains("reboot")

        if (isEnvironmentHostile && isPowerMenu) {
            LiveLogger.log("🛑 POWER MENU INTERCEPTED: Triggering Phantom Power-Off...")

            // 1. Instantly collapse the power menu
            performGlobalAction(GLOBAL_ACTION_BACK)
            try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (_: Exception) {}

            // 2. Deploy the Fake Shutdown Screen to trick the intruder
            try {
                val phantomIntent = Intent(this, com.example.aisecurity.ui.FakeShutdownActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }
                startActivity(phantomIntent)
            } catch (e: Exception) { e.printStackTrace() }
            return
        }
        // =========================================================

        if (isEnvironmentHostile && rawPackageName == "com.android.systemui") {
            if (className.contains("panel", true) || className.contains("notification", true) ||
                className.contains("expand", true) || className.contains("settings", true) ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                triggerScrimSniper()
                return
            }
        }

        if (isLocked) {
            deployAegisShield()
            if (rawPackageName.contains("systemui") || eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                if (!isPoltergeistActive) {

                    // 🚨 THE CRASH FIX: No dummy activities! We use native Android broadcasts to force the status bar closed.
                    try {
                        sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                    } catch (e: Exception) { e.printStackTrace() }

                    performGlobalAction(GLOBAL_ACTION_BACK)
                    executeAntiGravitySwipe()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            } else if (rawPackageName.contains("com.android.settings") || rawPackageName.contains("coloros") || rawPackageName.contains("oplus") || rawPackageName.contains("miui")) {
                if (!isPoltergeistActive) performGlobalAction(GLOBAL_ACTION_HOME)
            } else if (rawPackageName.isNotEmpty() && !rawPackageName.contains("com.example.aisecurity")) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        } else {
            removeAegisShield()
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (systemNoiseList.contains(rawPackageName)) return
            updateContext(rawPackageName)
        }

        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (!systemNoiseList.contains(rawPackageName) && !homeLaunchers.contains(rawPackageName) && !rawPackageName.contains("launcher")) {
                val actualApp = getReadableAppName(rawPackageName)
                if (actualApp != currentVisibleScreen) updateContext(rawPackageName)
            }

            if (swipeStartTime == 0L) swipeStartTime = System.currentTimeMillis()
            eventCount++
            swipeJob?.cancel()

            swipeJob = serviceScope.launch {
                delay(200)
                val totalDuration = System.currentTimeMillis() - swipeStartTime
                val estimatedPixels = (eventCount * 100).toFloat()
                val velocity = if (totalDuration > 0) (estimatedPixels / totalDuration) * 1000 else 0f

                if (!systemNoiseList.contains(rawPackageName) && currentVisibleScreen.isNotEmpty()) {
                    processSwipe(totalDuration.toFloat(), velocity, currentVisibleScreen)
                }
                swipeStartTime = 0L
                eventCount = 0
            }
        }
    }

    private fun updateContext(packageName: String) {
        val appName = getReadableAppName(packageName)
        val isNoise = appName.contains("System", ignoreCase = true) || appName.contains("quicksearchbox", ignoreCase = true) || systemNoiseList.contains(packageName)

        if (isNoise) {
            isCurrentlyInNoise = true
            if (lastRealAppLeaveTime == 0L) lastRealAppLeaveTime = System.currentTimeMillis()
            return
        }

        isCurrentlyInNoise = false
        currentVisibleScreen = appName

        if (appName == "Home Screen") {
            if (lastRealAppLeaveTime == 0L) lastRealAppLeaveTime = System.currentTimeMillis()
            return
        }

        if (appName != currentRealApp) {
            val previousApp = currentRealApp
            currentRealApp = appName
            val now = System.currentTimeMillis()

            val timeTaken = if (lastRealAppLeaveTime > 0) now - lastRealAppLeaveTime else now - lastAppSwitchTime
            currentTransitionSpeed = (timeTaken.coerceAtLeast(100L).toFloat() / 10000f).coerceIn(0f, 1f)

            if (previousApp.isNotEmpty() && previousApp != "Home Screen" && timeTaken < 60000L) {
                lastFromApp = previousApp
                lastToApp = currentRealApp
                LiveLogger.log("📱 FLOW: $previousApp -> $currentRealApp")
                serviceScope.launch { learnTransition(previousApp, currentRealApp, timeTaken) }
            }
            lastAppSwitchTime = now
            lastRealAppLeaveTime = 0L
        } else {
            lastRealAppLeaveTime = 0L
        }
    }

    private fun triggerScrimSniper() {
        val now = System.currentTimeMillis()
        if (now - lastGuillotineTime < 300) return
        lastGuillotineTime = now
        LiveLogger.log("🛡️ SCRIM SNIPER: Tapping bottom screen to abort Quick Settings!")

        serviceScope.launch(Dispatchers.Main) {
            repeat(5) {
                executeBottomScreenTap()
                performGlobalAction(GLOBAL_ACTION_BACK)
                try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (_: Exception) {}
                delay(50)
            }
        }
    }

    private fun executeBottomScreenTap() {
        try {
            val metrics = resources.displayMetrics
            val midX = metrics.widthPixels / 2f
            val bottomY = metrics.heightPixels * 0.85f
            val path = Path().apply { moveTo(midX, bottomY); lineTo(midX + 1f, bottomY + 1f) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 30)).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) { e.printStackTrace() }
    }

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
                    val settingsIntent = Intent(intentAction).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION) }
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

                            if (firedThisLoop) { tapFiredForData = true; delay(2000) } else { delay(300) }
                        } catch (e: Exception) { e.printStackTrace() }
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
            val path = Path().apply { moveTo(x - 1f, y - 1f); lineTo(x + 1f, y + 1f) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 150)).build()
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
                    while (p != null && levels < 5) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p = p.parent; levels++ }

                    if (screenWidth > 0) { delay(100); fireHumanTap(screenWidth * 0.88f, y) } else { fireHumanTap(rect.centerX().toFloat(), y) }
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
            val path = Path().apply { moveTo(middleX, startY); lineTo(middleX, 0f) }
            val gestureBuilder = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            dispatchGesture(gestureBuilder.build(), null, null)
        } catch (e: Exception) { e.printStackTrace() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun deployAegisShield() {
        if (isAegisDeployed || windowManager == null) return
        try {
            aegisShieldView = View(this).apply { setBackgroundColor(Color.TRANSPARENT); setOnTouchListener { _, _ -> true } }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, 200,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP }
            windowManager?.addView(aegisShieldView, params)
            isAegisDeployed = true
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun removeAegisShield() {
        if (!isAegisDeployed || windowManager == null || aegisShieldView == null) return
        try { windowManager?.removeView(aegisShieldView); isAegisDeployed = false } catch (e: Exception) { e.printStackTrace() }
    }

    private suspend fun learnTransition(from: String, to: String, timeTaken: Long) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", true)

        if (isPaused && !isReady) return
        if (System.currentTimeMillis() - lastUnlockTime < 3000) return

        val history = db.dao().getTransition(from, to)
        if (history == null) {
            if (isReady) increaseRisk(15)
            db.dao().updateTransition(TransitionProfile(fromApp = from, toApp = to, avgTime = timeTaken, frequency = 1))
        } else {
            if (isReady && kotlin.math.abs(history.avgTime - timeTaken) > 1500) increaseRisk(25) else if (isReady) decreaseRisk(5)
            if (!isReady || kotlin.math.abs(history.avgTime - timeTaken) <= 1500) {
                val newAvgTime = ((history.avgTime * history.frequency) + timeTaken) / (history.frequency + 1)
                db.dao().updateTransition(history.copy(avgTime = newAvgTime, frequency = history.frequency + 1))
            }
        }
        runContextualAI(velocity = 0f, duration = 0f, appName = to, fromApp = from, transitionTime = timeTaken)
    }

    private suspend fun processSwipe(duration: Float, velocity: Float, appLabel: String) {
        if (duration < 20) return
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", true)

        if (isPaused && !isReady) return
        if (System.currentTimeMillis() - lastUnlockTime < 3000) return

        // 🚨 UPGRADED: Only increment the UI swipe counts if we are actively in TRAINING MODE.
        // If 'isReady' (USE AI) is true, we freeze the stats so they don't inflate endlessly.
        if (!isReady) {
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
        }

        runContextualAI(velocity = velocity, duration = duration, appName = appLabel, fromApp = lastFromApp, transitionTime = (lastAppSwitchTime - lastRealAppLeaveTime).coerceAtLeast(100L))
        serviceScope.launch { syncBiometricsToWatch(-1) }
    }

    private fun increaseRisk(amount: Int) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val current = prefs.getInt("current_risk", 0)
        val newRisk = (current + amount).coerceIn(0, 100)
        prefs.edit().putInt("current_risk", newRisk).apply()
        checkLock(newRisk)
    }

    private fun decreaseRisk(amount: Int) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val current = prefs.getInt("current_risk", 0)
        prefs.edit().putInt("current_risk", (current - amount).coerceAtLeast(0)).apply()
    }

    private fun updateRiskScore(newCalculatedRisk: Int) {
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val oldRisk = prefs.getInt("current_risk", 0)
        val smoothedRisk = kotlin.math.ceil((oldRisk.toFloat() + newCalculatedRisk.toFloat()) / 2f).toInt().coerceIn(0, 100)
        prefs.edit().putInt("current_risk", smoothedRisk).apply()
        checkLock(smoothedRisk)
    }

    private fun checkLock(risk: Int) {
        if (risk >= 100 && !isLockdownCooldown) {
            isLockdownCooldown = true
            serviceScope.launch(Dispatchers.Main) {
                LiveLogger.log("🚨 DEVICE LOCKED: 100% Threat Reached")
                getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).edit().putInt("current_risk", 50).apply()
                enforcer.lockDevice("AI Touch Dynamics Threat Detected")
            }
        }
    }

    private suspend fun syncBiometricsToWatch(loopCount: Int) {
        if (com.example.aisecurity.ble.WatchManager.isConnected.value != true) return
        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", true)
        val risk = prefs.getInt("current_risk", 0)
        val totalSwipes = db.dao().getTotalTouchCount()

        val status = if (isReady) { if (risk < 50) "SECURE" else if (risk < 100) "WARNING" else "INTRUDER" } else { if (isPaused) "PAUSED" else "TRAINING" }
        val bioScore = if (isReady) risk else totalSwipes
        val progress = if (isReady) ((risk / 100f) * 183f).toInt().coerceIn(0, 183) else ((totalSwipes.toFloat() / 200f) * 183f).toInt().coerceIn(0, 183)
        val timeStr = if (isReady) "Active Protection" else "Hunting Plateau"

        com.example.aisecurity.ble.WatchManager.sendData("<BIO:$bioScore|$status|$progress|$timeStr>"); delay(40)
        com.example.aisecurity.ble.WatchManager.sendData("<BIOTRANS:${lastFromApp.take(15)}|${lastToApp.take(15)}>"); delay(40)

        if (loopCount % 3 == 0 || loopCount == -1) {
            com.example.aisecurity.ble.WatchManager.sendData("<BIOAPP:CLEAR>"); delay(40)
            db.dao().getAllAppStats().sortedByDescending { it.interactionCount }.take(5).forEach { app ->
                com.example.aisecurity.ble.WatchManager.sendData("<BIOAPP:${app.packageName}|${app.avgVelocity.toInt()} px/s   ${app.interactionCount}>")
                delay(40)
            }
            com.example.aisecurity.ble.WatchManager.sendData("<BIOAPP:END>")
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        removeAegisShield()
        isWatchSyncLoopRunning = false
        try { unregisterReceiver(ghostReceiver); unregisterReceiver(osBiometricSyncReceiver) } catch (_: IllegalArgumentException) {}
        serviceScope.cancel()
    }
}