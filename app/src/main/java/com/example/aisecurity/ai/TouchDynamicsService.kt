package com.example.aisecurity.ai

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.example.aisecurity.ui.LiveLogger
import com.example.aisecurity.ui.LockOverlayService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.*
import java.util.Locale

// ============================================================
//  TouchDynamicsService — AI Swipe Recognition Core
//  UPGRADED: Equalized but RUTHLESS penalties (15/30/50).
//  Strict mode now guarantees a lockdown in 1 to 2 bad swipes.
// ============================================================

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

    internal val classifier by lazy { BehavioralAuthClassifier(this) }
    internal val enforcer   by lazy { SecurityEnforcer(this) }
    internal val db         by lazy { SecurityDatabase.get(this) }

    internal lateinit var auth: FirebaseAuth
    internal lateinit var firebaseDb: FirebaseFirestore

    internal val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    internal var swipeJob       : Job? = null
    internal var swipeStartTime = 0L
    internal var eventCount     = 0

    internal var currentVisibleScreen  = "Home Screen"
    internal var currentRealApp        = ""
    internal var lastAppSwitchTime     = System.currentTimeMillis()
    internal var currentTransitionSpeed = 0.5f
    internal var lastRealAppLeaveTime  = 0L
    internal var isCurrentlyInNoise    = false
    internal var lastFromApp           = "System UI"
    internal var lastToApp             = "Monitoring..."

    internal var isLockdownCooldown = false
    internal var lastUnlockTime     = 0L

    internal val systemNoiseList = listOf(
        "com.android.systemui",
        "com.android.systemui.plugin",
        "com.google.android.googlequicksearchbox",
        "com.google.android.inputmethod.latin",
        "com.touchtype.swiftkey",
        "com.google.android.gms",
        "android"
    )

    internal val homeLaunchers = listOf(
        "com.miui.home",
        "com.mi.android.globallauncher",
        "com.mi.ui.poco.home",
        "com.sec.android.app.launcher",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.oneplus.setupwizard",
        "com.coloros.systemui",
        "com.bbk.launcher2"
    )

    internal val knownAppOverrides = mapOf(
        "com.facebook.katana"        to "Facebook",
        "com.facebook.orca"          to "Messenger",
        "com.zhiliaoapp.musically"   to "TikTok",
        "com.ss.android.ugc.trill"   to "TikTok",
        "com.instagram.android"      to "Instagram",
        "com.google.android.youtube" to "YouTube",
        "com.whatsapp"               to "WhatsApp",
        "com.twitter.android"        to "X (Twitter)",
        "com.mobile.legends"         to "Mobile Legends"
    )

    internal var windowManager      : WindowManager? = null
    internal var aegisShieldView    : View?          = null
    internal var isAegisDeployed    = false
    internal var blackoutShieldView : View?          = null
    internal var isPoltergeistActive      = false
    internal var isWatchSyncLoopRunning   = false
    internal var isBooting                = true
    internal val commandCooldowns         = mutableMapOf<String, Long>()
    internal val lastFirebaseStates       = mutableMapOf<String, Boolean>()
    internal var lastGuillotineTime       = 0L

    private val osBiometricSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
                lastUnlockTime     = System.currentTimeMillis()
                isLockdownCooldown = false

                LiveLogger.log("🔓 OS UNLOCK: True Owner Verified. 3-Second Grace Period Started.")
                prefs.edit()
                    .putBoolean("is_system_locked", false)
                    .putBoolean("is_auth_in_progress", false)
                    .putInt("current_risk", 0)
                    .apply()

                try {
                    stopService(Intent(this@TouchDynamicsService, LockOverlayService::class.java))
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    internal val ghostReceiver = buildGhostReceiver()

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        auth        = FirebaseAuth.getInstance()
        firebaseDb  = FirebaseFirestore.getInstance()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ghostReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(ghostReceiver, filter)
        }
        registerReceiver(osBiometricSyncReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))

        startWatchSyncLoop()
        startFirebaseListener()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        startWatchSyncLoop()

        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("is_auth_in_progress", false)) return

        val isLocked       = prefs.getBoolean("is_system_locked", false)
        val rawPackageName = event?.packageName?.toString()?.lowercase(Locale.ROOT) ?: ""
        val className      = event?.className?.toString()?.lowercase(Locale.ROOT) ?: ""
        val eventType      = event?.eventType
        val textNodes      = event?.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val contentDesc    = event?.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val combinedText   = "$textNodes $contentDesc"

        if (handleSideFeatureEvents(event, rawPackageName, className, eventType, combinedText, isLocked, prefs)) return

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (systemNoiseList.contains(rawPackageName)) return
            updateContext(rawPackageName)
        }

        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (!systemNoiseList.contains(rawPackageName) &&
                !homeLaunchers.contains(rawPackageName) &&
                !rawPackageName.contains("launcher")
            ) {
                val actualApp = getReadableAppName(rawPackageName)
                if (actualApp != currentVisibleScreen) updateContext(rawPackageName)
            }

            if (swipeStartTime == 0L) swipeStartTime = System.currentTimeMillis()
            eventCount++
            swipeJob?.cancel()

            swipeJob = serviceScope.launch {
                delay(200)
                val totalDuration   = System.currentTimeMillis() - swipeStartTime
                val estimatedPixels = (eventCount * 100).toFloat()
                val velocity        = if (totalDuration > 0) (estimatedPixels / totalDuration) * 1000 else 0f

                if (!systemNoiseList.contains(rawPackageName) && currentVisibleScreen.isNotEmpty()) {
                    processSwipe(totalDuration.toFloat(), velocity, currentVisibleScreen)
                }
                swipeStartTime = 0L
                eventCount     = 0
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        removeAegisShield()
        removeBlackoutShield()
        isWatchSyncLoopRunning = false
        try {
            unregisterReceiver(ghostReceiver)
            unregisterReceiver(osBiometricSyncReceiver)
        } catch (_: IllegalArgumentException) {}
        serviceScope.cancel()
    }

    private fun updateContext(packageName: String) {
        val appName = getReadableAppName(packageName)
        getSharedPreferences("app_icons", MODE_PRIVATE).edit()
            .putString(appName, packageName).apply()

        val isNoise = appName.contains("System", ignoreCase = true) ||
                appName.contains("quicksearchbox", ignoreCase = true) ||
                systemNoiseList.contains(packageName)

        if (isNoise) {
            isCurrentlyInNoise = true
            if (lastRealAppLeaveTime == 0L) lastRealAppLeaveTime = System.currentTimeMillis()
            return
        }

        isCurrentlyInNoise   = false
        currentVisibleScreen = appName

        if (appName == "Home Screen") {
            if (lastRealAppLeaveTime == 0L) lastRealAppLeaveTime = System.currentTimeMillis()
            return
        }

        if (appName != currentRealApp) {
            val previousApp = currentRealApp
            currentRealApp  = appName

            val myUid = auth.currentUser?.uid
            if (myUid != null) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        firebaseDb.collection("Users").document(myUid)
                            .set(hashMapOf("current_active_app" to currentRealApp), SetOptions.merge())
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            val now      = System.currentTimeMillis()
            val timeTaken = if (lastRealAppLeaveTime > 0)
                now - lastRealAppLeaveTime
            else
                now - lastAppSwitchTime

            currentTransitionSpeed =
                (timeTaken.coerceAtLeast(100L).toFloat() / 10000f).coerceIn(0f, 1f)

            if (previousApp.isNotEmpty() && previousApp != "Home Screen" && timeTaken < 60000L) {
                lastFromApp = previousApp
                lastToApp   = currentRealApp
                LiveLogger.log("📱 FLOW: $lastFromApp -> $lastToApp")
                serviceScope.launch { learnTransition(previousApp, currentRealApp, timeTaken) }
            }
            lastAppSwitchTime    = now
            lastRealAppLeaveTime = 0L
        } else {
            lastRealAppLeaveTime = 0L
        }
    }

    private suspend fun learnTransition(from: String, to: String, timeTaken: Long) {
        val prefs    = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady  = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", true)

        if (isPaused && !isReady) return
        if (System.currentTimeMillis() - lastUnlockTime < 3000) return

        // 🚨 MASSIVELY INCREASED TRANSITION PENALTIES
        val penaltyLevel = prefs.getInt("ai_sensitivity", 1)
        val penaltyValue = when (penaltyLevel) {
            0    -> 15  // Normal: +15%
            2    -> 50  // Strict: +50%
            else -> 30  // Moderate: +30%
        }

        val history = db.dao().getTransition(from, to)
        if (history == null) {
            if (isReady) {
                increaseRisk(penaltyValue)
                LiveLogger.log("🚨 ANOMALY: Unrecognized App Route! Penalty: +$penaltyValue%")
            }
            db.dao().updateTransition(
                TransitionProfile(fromApp = from, toApp = to, avgTime = timeTaken, frequency = 1)
            )
        } else {
            if (isReady && kotlin.math.abs(history.avgTime - timeTaken) > 1500) {
                increaseRisk(penaltyValue)
                LiveLogger.log("🚨 ANOMALY: Erratically slow/fast App Switch! Penalty: +$penaltyValue%")
            }
            else if (isReady) {
                decreaseRisk(1) // Ruthless forgiveness limit
            }

            if (!isReady || kotlin.math.abs(history.avgTime - timeTaken) <= 1500) {
                val newAvgTime =
                    ((history.avgTime * history.frequency) + timeTaken) / (history.frequency + 1)
                db.dao().updateTransition(
                    history.copy(avgTime = newAvgTime, frequency = history.frequency + 1)
                )
            }
        }

        runContextualAI(velocity = 0f, duration = 0f, appName = to, fromApp = from, transitionTime = timeTaken)
    }

    private suspend fun processSwipe(duration: Float, velocity: Float, appLabel: String) {
        if (duration < 20) return
        val prefs    = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady  = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", true)

        if (isPaused && !isReady) return
        if (System.currentTimeMillis() - lastUnlockTime < 3000) return

        if (!isReady) {
            val oldStats = db.dao().getAppStats(appLabel)
            val newStats = if (oldStats == null) {
                AppUsageProfile(appLabel, velocity, duration, 1)
            } else {
                val count     = oldStats.interactionCount
                val newAvgVel = ((oldStats.avgVelocity * count) + velocity) / (count + 1)
                val newAvgDur = ((oldStats.avgDuration * count) + duration) / (count + 1)
                AppUsageProfile(appLabel, newAvgVel, newAvgDur, count + 1)
            }
            db.dao().updateAppStats(newStats)
        }

        runContextualAI(velocity = velocity, duration = duration, appName = appLabel, fromApp = lastFromApp, transitionTime = (lastAppSwitchTime - lastRealAppLeaveTime).coerceAtLeast(100L))
        serviceScope.launch { syncBiometricsToWatch(-1) }
    }

    internal fun increaseRisk(amount: Int) {
        val prefs   = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val current = prefs.getInt("current_risk", 0)
        val newRisk = (current + amount).coerceIn(0, 100)
        prefs.edit().putInt("current_risk", newRisk).apply()
        checkLock(newRisk)
    }

    internal fun decreaseRisk(amount: Int) {
        val prefs   = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val current = prefs.getInt("current_risk", 0)
        prefs.edit().putInt("current_risk", (current - amount).coerceAtLeast(0)).apply()
    }

    private fun checkLock(risk: Int) {
        val lockThreshold = 100
        if (risk >= lockThreshold && !isLockdownCooldown) {
            isLockdownCooldown = true
            serviceScope.launch(Dispatchers.Main) {
                val title   = "AI Intruder Lockdown"
                val details = "Unrecognized touch biometrics detected. Risk ($risk%) reached maximum limit ($lockThreshold%). Defense protocol engaged."
                com.example.aisecurity.ble.WatchManager.logAndNotify(this@TouchDynamicsService, title, details, 2)

                val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
                prefs.edit().putInt("current_risk", 50).apply()
                enforcer.lockDevice("AI Touch Dynamics Threat Detected")
            }
        }
    }

    private fun getContextHash(text: String): Float =
        (kotlin.math.abs(text.hashCode()) % 1000) / 1000f

    private suspend fun runContextualAI(
        velocity      : Float,
        duration      : Float,
        appName       : String,
        fromApp       : String,
        transitionTime: Long
    ) {
        val prefs    = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady  = prefs.getBoolean("ai_ready", false)
        val isPaused = prefs.getBoolean("training_paused", true)

        val globalThreshold = prefs.getFloat("threshold", 0.08f)
        val targetStability = globalThreshold * 1.2f
        val baselineEma     = 1.0f

        val stats            = db.dao().getAppStats(appName)
        val interactionCount = stats?.interactionCount ?: 0
        var appSpecificEma   = prefs.getFloat("ema_loss_$appName", 1.0f)

        val progressRaw = ((baselineEma - appSpecificEma) / (baselineEma - targetStability)).coerceIn(0f, 1f)
        val finalPct    = (progressRaw * 100f).toInt()

        val normVelocity   = (velocity / 5000f).coerceIn(0f, 1f)
        val normPressure   = if (duration == 0f) 0.0f else 0.5f
        val normAppUsage   = (interactionCount.toFloat() / 100f).coerceIn(0f, 1f)
        val normTransition = (transitionTime.toFloat() / 10000f).coerceIn(0f, 1f)
        val appID          = getContextHash(appName)
        val transitionID   = getContextHash("$fromApp->$appName")

        val features = floatArrayOf(
            normVelocity, normPressure, normAppUsage, normTransition, appID, transitionID
        )

        if (isReady) {
            if (finalPct >= 75) {
                val error = classifier.getError(features)
                val ratio = error / globalThreshold

                if (ratio > 1.0f) {
                    // 🚨 RUTHLESS INTRUDER PENALTIES FOR SWIPES (Equalized to Transitions)
                    val penaltyLevel = prefs.getInt("ai_sensitivity", 1)
                    val basePenalty = when (penaltyLevel) {
                        0    -> 15  // Normal: +15%
                        2    -> 50  // Strict: +50% (Max 2 bad swipes to lockdown)
                        else -> 30  // Moderate: +30%
                    }

                    // Multiplier scales from 1.0x to 2.0x based on how unfamiliar the swipe is.
                    // On Strict, an extremely bad swipe will hit 100% and INSTANTLY lock.
                    val severityMultiplier = ratio.coerceIn(1.0f, 2.0f)
                    val finalPenalty = (basePenalty * severityMultiplier).toInt()

                    increaseRisk(finalPenalty)
                    LiveLogger.log("🚨 INTRUDER SWIPE DETECTED! Ratio: ${"%.2f".format(ratio)}x, Penalty: +$finalPenalty%")
                } else {
                    decreaseRisk(1) // Ruthless forgiveness limit

                    val rawLoss = classifier.trainAI(features)
                    appSpecificEma = BehavioralAuthClassifier.emaStep(appSpecificEma, rawLoss)
                    prefs.edit().putFloat("ema_loss_$appName", appSpecificEma).apply()
                    db.dao().insertTouch(
                        TouchProfile(
                            duration  = duration,
                            velocityX = velocity,
                            pressure  = normPressure,
                            appName   = appName
                        )
                    )
                }
            }
        } else if (!isPaused) {
            val rawLoss = classifier.trainAI(features)
            appSpecificEma = BehavioralAuthClassifier.emaStep(appSpecificEma, rawLoss)
            prefs.edit().putFloat("ema_loss_$appName", appSpecificEma).apply()

            db.dao().insertTouch(
                TouchProfile(duration = duration, velocityX = velocity, pressure = normPressure, appName = appName)
            )
            db.dao().insertLossPoint(
                LossPoint(timestamp = System.currentTimeMillis(), lossValue = rawLoss, emaValue = appSpecificEma)
            )
        }
    }

    internal fun startWatchSyncLoop() {
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

    internal suspend fun syncBiometricsToWatch(loopCount: Int) {
        if (com.example.aisecurity.ble.WatchManager.isConnected.value != true) return
        val prefs      = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady    = prefs.getBoolean("ai_ready", false)
        val isPaused   = prefs.getBoolean("training_paused", true)
        val risk       = prefs.getInt("current_risk", 0)
        val totalSwipes = db.dao().getTotalTouchCount()

        val status = if (isReady) {
            if (risk < 50) "SECURE" else if (risk < 100) "WARNING" else "INTRUDER"
        } else {
            if (isPaused) "PAUSED" else "TRAINING"
        }
        val bioScore = if (isReady) risk else totalSwipes
        val progress = if (isReady)
            ((risk / 100f) * 183f).toInt().coerceIn(0, 183)
        else
            ((totalSwipes.toFloat() / 200f) * 183f).toInt().coerceIn(0, 183)
        val timeStr = if (isReady) "Active Protection" else "Hunting Plateau"

        com.example.aisecurity.ble.WatchManager.sendData("<BIO:$bioScore|$status|$progress|$timeStr>"); delay(40)
        com.example.aisecurity.ble.WatchManager.sendData("<BIOTRANS:${lastFromApp.take(15)}|${lastToApp.take(15)}>"); delay(40)

        if (loopCount % 3 == 0 || loopCount == -1) {
            com.example.aisecurity.ble.WatchManager.sendData("<BIOAPP:CLEAR>"); delay(40)
            db.dao().getAllAppStats()
                .sortedByDescending { it.interactionCount }
                .take(5)
                .forEach { app ->
                    com.example.aisecurity.ble.WatchManager.sendData(
                        "<BIOAPP:${app.packageName}|${app.avgVelocity.toInt()} px/s   ${app.interactionCount}>"
                    )
                    delay(40)
                }
            com.example.aisecurity.ble.WatchManager.sendData("<BIOAPP:END>")
        }
    }

    internal fun getReadableAppName(packageName: String): String {
        if (homeLaunchers.contains(packageName) || packageName.contains("launcher"))
            return "Home Screen"
        if (knownAppOverrides.containsKey(packageName))
            return knownAppOverrides[packageName]!!
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.split(".").last().replaceFirstChar { it.uppercase() }
        }
    }
}