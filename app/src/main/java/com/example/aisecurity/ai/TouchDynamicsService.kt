package com.example.aisecurity.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.aisecurity.ui.HiddenCameraActivity
import com.example.aisecurity.ui.LiveLogger
import com.example.aisecurity.ui.LockOverlayService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
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

    private lateinit var auth: FirebaseAuth
    private lateinit var firebaseDb: FirebaseFirestore

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
        "com.coloros.systemui",
        "com.bbk.launcher2"
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

    private var isBooting = true
    private val commandCooldowns = mutableMapOf<String, Long>()

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

                    // 🚨 NEW: INSTANT SHADE SLAM
                    if (target == "SLAM_SHADE") {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            this@TouchDynamicsService.performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                        }
                        this@TouchDynamicsService.performGlobalAction(GLOBAL_ACTION_BACK)
                        return
                    }
                    else if (target == "FORCE_SLEEP") {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            serviceScope.launch(Dispatchers.Main) {
                                delay(300)
                                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                            }
                        }
                    } else if (target == "EMERGENCY_COMMS") {
                        executeEmergencyCommsCombo()
                    } else if (target in listOf("DATA", "WIFI", "BLUETOOTH", "LOCATION", "BATTERY")) {
                        commandCooldowns[target] = System.currentTimeMillis()
                        executeUniversalPoltergeist(target)
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

        auth = FirebaseAuth.getInstance()
        firebaseDb = FirebaseFirestore.getInstance()
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
        startFirebaseListener()
    }

    private fun startFirebaseListener() {
        val myUid = auth.currentUser?.uid ?: return

        serviceScope.launch(Dispatchers.IO) {
            try {
                forceFirebaseSyncToReality(myUid)
                delay(3000)
                isBooting = false
            } catch (e: Exception) { e.printStackTrace() }
        }

        firebaseDb.collection("Users").document(myUid).addSnapshotListener { snapshot, e ->
            if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
            if (isPoltergeistActive || isBooting) return@addSnapshotListener

            val cmdLockDevice = snapshot.getBoolean("cmd_lock_device") ?: false
            if (cmdLockDevice) {
                LiveLogger.log("🔒 POLTERGEIST: Ordinary Lock Command Received. Locking OS...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
                firebaseDb.collection("Users").document(myUid).set(
                    hashMapOf("cmd_lock_device" to false), SetOptions.merge()
                )
            }

            val cmdTakePhoto = snapshot.getString("cmd_take_photo") ?: ""
            if (cmdTakePhoto.isNotEmpty()) {
                firebaseDb.collection("Users").document(myUid).set(
                    hashMapOf("cmd_take_photo" to ""), SetOptions.merge()
                )
                serviceScope.launch(Dispatchers.Main) {
                    try {
                        val pm = getSystemService(POWER_SERVICE) as PowerManager
                        @Suppress("DEPRECATION")
                        val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "AISecurity::PreCameraWake")
                        wl.acquire(5_000L)
                        delay(300)

                        val photoIntent = Intent(this@TouchDynamicsService, HiddenCameraActivity::class.java).apply {
                            putExtra("CAMERA_TYPE", cmdTakePhoto)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        startActivity(photoIntent)

                        delay(2000)
                        if (wl.isHeld) wl.release()
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            val resolver = contentResolver

            fun checkMirrorState(stateField: String, targetSetting: String, physicalState: Boolean) {
                val firebaseState = snapshot.getBoolean(stateField)
                if (firebaseState != null && firebaseState != physicalState) {
                    val lastAttemptTime = commandCooldowns[targetSetting] ?: 0L
                    if (System.currentTimeMillis() - lastAttemptTime < 45000) return
                    LiveLogger.log("👻 POLTERGEIST: Remote Command Triggered! Fixing $targetSetting...")
                    commandCooldowns[targetSetting] = System.currentTimeMillis()
                    executeUniversalPoltergeist(targetSetting)
                }
            }

            try {
                val wifiOn = Settings.Global.getInt(resolver, Settings.Global.WIFI_ON, 0) == 1
                checkMirrorState("state_wifi", "WIFI", wifiOn)

                val dataOn = Settings.Global.getInt(resolver, "mobile_data", 0) == 1
                checkMirrorState("state_mobile_data", "DATA", dataOn)

                val btOn = Settings.Global.getInt(resolver, Settings.Global.BLUETOOTH_ON, 0) == 1
                checkMirrorState("state_bluetooth", "BLUETOOTH", btOn)

                val locOn = Settings.Secure.getInt(resolver, Settings.Secure.LOCATION_MODE, 0) != 0
                checkMirrorState("state_location", "LOCATION", locOn)

                val saverOn = Settings.Global.getInt(resolver, "low_power", 0) == 1
                checkMirrorState("state_battery_saver", "BATTERY", saverOn)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun forceFirebaseSyncToReality(myUid: String? = auth.currentUser?.uid) {
        if (myUid == null) return
        val resolver = contentResolver
        val wifiOn  = Settings.Global.getInt(resolver, Settings.Global.WIFI_ON, 0) == 1
        val dataOn  = Settings.Global.getInt(resolver, "mobile_data", 0) == 1
        val btOn    = Settings.Global.getInt(resolver, Settings.Global.BLUETOOTH_ON, 0) == 1
        val locOn   = Settings.Secure.getInt(resolver, Settings.Secure.LOCATION_MODE, 0) != 0
        val saverOn = Settings.Global.getInt(resolver, "low_power", 0) == 1

        val updates = hashMapOf<String, Any>(
            "state_wifi"          to wifiOn,
            "state_mobile_data"   to dataOn,
            "state_bluetooth"     to btOn,
            "state_location"      to locOn,
            "state_battery_saver" to saverOn,
            "lastHardwareUpdate"  to com.google.firebase.Timestamp.now()
        )
        firebaseDb.collection("Users").document(myUid).set(updates, SetOptions.merge())
    }

    private fun executeEmergencyCommsCombo() {
        if (isPoltergeistActive) return
        isPoltergeistActive = true

        serviceScope.launch(Dispatchers.IO) {
            val resolver = contentResolver
            val wifiOn = Settings.Global.getInt(resolver, Settings.Global.WIFI_ON, 0) == 1
            val dataOn = Settings.Global.getInt(resolver, "mobile_data", 0) == 1
            val locOn = Settings.Secure.getInt(resolver, Settings.Secure.LOCATION_MODE, 0) != 0

            if (wifiOn && dataOn && locOn) {
                LiveLogger.log("✅ Emergency Comms: All connections are already active. Doing nothing.")
                isPoltergeistActive = false
                return@launch
            }

            LiveLogger.log("⚠️ Emergency Comms: Missing critical connections. Force arming via Combo Hack...")

            withContext(Dispatchers.Main) {
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    @Suppress("DEPRECATION")
                    val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "Sentry::ComboWake")
                    wl.acquire(4000)
                    delay(300)

                    val metrics = resources.displayMetrics
                    val swipePath = Path().apply {
                        moveTo(metrics.widthPixels * 0.85f, 1f)
                        lineTo(metrics.widthPixels * 0.85f, metrics.heightPixels * 0.7f)
                    }
                    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(swipePath, 0, 300)).build()
                    dispatchGesture(gesture, null, null)

                    delay(800)

                    val targets = mutableMapOf<String, List<String>>()
                    if (!wifiOn) targets["WIFI"] = listOf("wi-fi", "wifi", "wlan", "internet")
                    if (!dataOn) targets["DATA"] = listOf("mobile data", "data network", "cellular", "datos", "data connection")
                    if (!locOn) targets["LOCATION"] = listOf("location", "gps", "ubicación")

                    spatialSurgicalComboTap(rootInActiveWindow, targets)

                    val scrollPath = Path().apply {
                        moveTo(metrics.widthPixels * 0.8f, metrics.heightPixels * 0.3f)
                        lineTo(metrics.widthPixels * 0.2f, metrics.heightPixels * 0.3f)
                    }
                    val scrollGesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(scrollPath, 0, 200)).build()
                    dispatchGesture(scrollGesture, null, null)

                    delay(500)
                    spatialSurgicalComboTap(rootInActiveWindow, targets)

                    if (wl.isHeld) wl.release()

                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    delay(400)

                    val metrics = resources.displayMetrics
                    val closePath = Path().apply {
                        moveTo(metrics.widthPixels * 0.85f, metrics.heightPixels * 0.8f)
                        lineTo(metrics.widthPixels * 0.85f, 1f)
                    }
                    val closeGesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(closePath, 0, 200)).build()
                    dispatchGesture(closeGesture, null, null)

                    performGlobalAction(GLOBAL_ACTION_BACK)

                    isPoltergeistActive = false

                    serviceScope.launch(Dispatchers.IO) {
                        delay(2000)
                        forceFirebaseSyncToReality()
                    }
                }
            }
        }
    }

    private fun spatialSurgicalComboTap(rootNode: AccessibilityNodeInfo?, targets: MutableMap<String, List<String>>) {
        if (rootNode == null || targets.isEmpty()) return

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)

        val successfullyTapped = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""

            for ((category, keywords) in targets) {
                if (successfullyTapped.contains(category)) continue

                if (keywords.any { k -> text.contains(k) || desc.contains(k) || text == k }) {
                    successfullyTapped.add(category)

                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else if (node.parent?.isClickable == true) {
                        node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        if (rect.centerY() > 50) {
                            val x = rect.centerX().toFloat()
                            val y = rect.centerY().toFloat()
                            fireHumanTap(x, y)
                            fireHumanTap(x, y - 80f)
                        }
                    }
                    break
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        successfullyTapped.forEach { targets.remove(it) }
    }

    private fun executeUniversalPoltergeist(target: String) {
        if (isPoltergeistActive) return
        isPoltergeistActive = true

        serviceScope.launch(Dispatchers.IO) {
            try {
                LiveLogger.log("👻 POLTERGEIST: Attempting Root silent toggle for $target...")

                val rootCommand = when (target) {
                    "DATA" -> {
                        val state = Settings.Global.getInt(contentResolver, "mobile_data", 0) == 1
                        if (state) "svc data disable" else "svc data enable"
                    }
                    "WIFI" -> {
                        val state = Settings.Global.getInt(contentResolver, Settings.Global.WIFI_ON, 0) == 1
                        if (state) "svc wifi disable" else "svc wifi enable"
                    }
                    "BLUETOOTH" -> {
                        val state = Settings.Global.getInt(contentResolver, Settings.Global.BLUETOOTH_ON, 0) == 1
                        if (state) "svc bluetooth disable" else "svc bluetooth enable"
                    }
                    "LOCATION" -> {
                        val state = Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
                        if (state) "cmd location set-location-enabled false" else "cmd location set-location-enabled true"
                    }
                    "BATTERY" -> {
                        val state = Settings.Global.getInt(contentResolver, "low_power", 0) == 1
                        if (state) "cmd battery unplug && settings put global low_power 0"
                        else "cmd battery unplug && settings put global low_power 1"
                    }
                    else -> ""
                }

                if (rootCommand.isNotEmpty()) {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", rootCommand))
                    if (process.waitFor() == 0) {
                        LiveLogger.log("👻 POLTERGEIST: Success! $target toggled via ROOT.")
                        isPoltergeistActive = false
                        forceFirebaseSyncToReality()
                        return@launch
                    }
                }
            } catch (e: Exception) {
                LiveLogger.log("⚠️ ROOT attempt failed for $target. Deploying Spatial UI Automation...")
            }

            withContext(Dispatchers.Main) {
                var toggled = false
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    @Suppress("DEPRECATION")
                    val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "Sentry::TileWake")
                    wl.acquire(3000)
                    delay(300)

                    val metrics = resources.displayMetrics
                    val swipePath = Path().apply {
                        moveTo(metrics.widthPixels * 0.85f, 1f)
                        lineTo(metrics.widthPixels * 0.85f, metrics.heightPixels * 0.7f)
                    }
                    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(swipePath, 0, 300)).build()
                    dispatchGesture(gesture, null, null)

                    delay(800)

                    val targets = mutableMapOf<String, List<String>>()
                    val keywords = when (target) {
                        "DATA" -> listOf("mobile data", "data network", "cellular", "datos", "data connection")
                        "WIFI" -> listOf("wi-fi", "wifi", "wlan", "internet")
                        "BLUETOOTH" -> listOf("bluetooth", "bt")
                        "LOCATION" -> listOf("location", "gps", "ubicación")
                        "BATTERY" -> listOf("battery saver", "power saving", "low power")
                        else -> emptyList()
                    }
                    targets[target] = keywords

                    spatialSurgicalComboTap(rootInActiveWindow, targets)
                    toggled = targets.isEmpty()

                    if (!toggled) {
                        val scrollPath = Path().apply {
                            moveTo(metrics.widthPixels * 0.8f, metrics.heightPixels * 0.3f)
                            lineTo(metrics.widthPixels * 0.2f, metrics.heightPixels * 0.3f)
                        }
                        val scrollGesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(scrollPath, 0, 200)).build()
                        dispatchGesture(scrollGesture, null, null)

                        delay(500)
                        spatialSurgicalComboTap(rootInActiveWindow, targets)
                        toggled = targets.isEmpty()
                    }

                    if (wl.isHeld) wl.release()

                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    delay(400)

                    val metrics = resources.displayMetrics
                    val closePath = Path().apply {
                        moveTo(metrics.widthPixels * 0.85f, metrics.heightPixels * 0.8f)
                        lineTo(metrics.widthPixels * 0.85f, 1f)
                    }
                    val closeGesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(closePath, 0, 200)).build()
                    dispatchGesture(closeGesture, null, null)

                    performGlobalAction(GLOBAL_ACTION_BACK)

                    isPoltergeistActive = false

                    if (!toggled) {
                        LiveLogger.log("❌ POLTERGEIST: Failed to toggle $target. Syncing Firebase to reality.")
                    }
                    serviceScope.launch(Dispatchers.IO) {
                        delay(2000)
                        forceFirebaseSyncToReality()
                    }
                }
            }
        }
    }

    private fun fireHumanTap(x: Float, y: Float) {
        try {
            val path = Path().apply {
                moveTo(x - 1f, y - 1f)
                lineTo(x + 1f, y + 1f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun triggerScrimSniper() {
        val now = System.currentTimeMillis()
        if (now - lastGuillotineTime < 300) return
        lastGuillotineTime = now
        LiveLogger.log("🛡️ SCRIM SNIPER: Aborting Quick Settings!")

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
            val midX   = metrics.widthPixels / 2f
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

    private fun executeAntiGravitySwipe() {
        try {
            val displayMetrics = resources.displayMetrics
            val middleX = displayMetrics.widthPixels / 2f
            val startY  = displayMetrics.heightPixels / 2f
            val path = Path().apply {
                moveTo(middleX, startY)
                lineTo(middleX, 0f)
            }
            val gestureBuilder = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
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
                WindowManager.LayoutParams.MATCH_PARENT,
                200,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP }
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
        val prefs     = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val isReady   = prefs.getBoolean("ai_ready", false)
        val isPaused  = prefs.getBoolean("training_paused", true)

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
                val error    = classifier.getError(features)
                val ratio    = error / globalThreshold
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
                    db.dao().insertTouch(
                        TouchProfile(duration = duration, velocityX = velocity, pressure = normPressure, appName = appName)
                    )
                } else {
                    LiveLogger.log("⚠️ [THREAT] High Risk in ARMED APP ($appName)! Risk: $swipeRisk%")
                }
            } else {
                LiveLogger.log("🛡️ [IGNORED] $appName only $finalPct% trained. Detection disabled.")
                updateRiskScore(0)
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
            val pm      = packageManager
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
        val className      = event?.className?.toString()?.lowercase(Locale.ROOT) ?: ""
        val eventType      = event?.eventType
        val textNodes      = event?.text?.toString()?.lowercase(Locale.ROOT) ?: ""

        // 🚨 NEW: AGGRESSIVE STATUS BAR BLOCKER DURING FAKE SHUTDOWN
        val isFakeDeadState = prefs.getBoolean("is_fake_dead_state", false)
        if (isFakeDeadState) {
            // ✅ Deploy the accessibility overlay — highest z-order, beats TYPE_APPLICATION_OVERLAY
            deployAegisShield()

            if (rawPackageName == "com.android.systemui" ||
                className.contains("panel", true) ||
                className.contains("notification", true) ||
                className.contains("expand", true) ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
            ) {
                LiveLogger.log("🛡️ SCRIM SNIPER: Slapping Status Bar shut during Fake Shutdown!")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                }
                performGlobalAction(GLOBAL_ACTION_BACK)
                try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (_: Exception) {}
                return
            }
        }

        val isEnvironmentHostile = km.isKeyguardLocked || isLocked

        // 🚨 STRENGTHENED POWER MENU DETECTION
        val isPowerMenu = className.contains("globalactions", true) ||
                rawPackageName == "android" ||
                (rawPackageName == "com.android.systemui" && className.contains("dialog", true)) ||
                textNodes.contains("power off") ||
                textNodes.contains("restart") ||
                textNodes.contains("shut down") ||
                textNodes.contains("reboot") ||
                textNodes.contains("emergency")

        val isFakeShutdownEnabled = prefs.getBoolean("enable_fake_shutdown", false)

        if (isEnvironmentHostile && isPowerMenu) {

            if (isFakeShutdownEnabled) {
                // 🟢 THE USER ENABLED IT: Launch the Deceptive Shutdown UI
                LiveLogger.log("🛑 POWER MENU INTERCEPTED: Triggering Fake Power-Off...")

                // Force the real power menu to close
                performGlobalAction(GLOBAL_ACTION_BACK)
                try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (_: Exception) {}

                try {
                    val phantomIntent = Intent(
                        this,
                        com.example.aisecurity.ui.FakeShutdownActivity::class.java
                    ).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                                    or Intent.FLAG_ACTIVITY_NO_ANIMATION
                                    or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                    }
                    startActivity(phantomIntent)
                } catch (e: Exception) { e.printStackTrace() }

            } else {
                // 🔴 THE USER DISABLED IT: Let the phone shut down normally, but lock the screen quickly
                LiveLogger.log("⚠️ Fake Shutdown Disabled. Allowing normal OS execution.")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            }
            return // Prevent processing other hostile checks for this specific event
        }

        if (isEnvironmentHostile && rawPackageName == "com.android.systemui") {
            if (className.contains("panel", true) || className.contains("notification", true) ||
                className.contains("expand", true) || className.contains("settings", true) ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
            ) {
                triggerScrimSniper()
                return
            }
        }

        if (isLocked) {
            deployAegisShield()
            if (rawPackageName.contains("systemui") ||
                eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            ) {
                if (!isPoltergeistActive) {
                    try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (e: Exception) { e.printStackTrace() }
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    executeAntiGravitySwipe()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            } else if (rawPackageName.contains("com.android.settings") ||
                rawPackageName.contains("coloros") ||
                rawPackageName.contains("oplus") ||
                rawPackageName.contains("miui")
            ) {
                if (!isPoltergeistActive) performGlobalAction(GLOBAL_ACTION_HOME)
            } else if (rawPackageName.isNotEmpty() &&
                !rawPackageName.contains("com.example.aisecurity")
            ) {
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
                val totalDuration    = System.currentTimeMillis() - swipeStartTime
                val estimatedPixels  = (eventCount * 100).toFloat()
                val velocity         = if (totalDuration > 0) (estimatedPixels / totalDuration) * 1000 else 0f

                if (!systemNoiseList.contains(rawPackageName) && currentVisibleScreen.isNotEmpty()) {
                    processSwipe(totalDuration.toFloat(), velocity, currentVisibleScreen)
                }
                swipeStartTime = 0L
                eventCount     = 0
            }
        }
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

        isCurrentlyInNoise    = false
        currentVisibleScreen  = appName

        if (appName == "Home Screen") {
            if (lastRealAppLeaveTime == 0L) lastRealAppLeaveTime = System.currentTimeMillis()
            return
        }

        if (appName != currentRealApp) {
            val previousApp = currentRealApp
            currentRealApp  = appName
            val now         = System.currentTimeMillis()

            val timeTaken = if (lastRealAppLeaveTime > 0)
                now - lastRealAppLeaveTime
            else
                now - lastAppSwitchTime

            currentTransitionSpeed = (timeTaken.coerceAtLeast(100L).toFloat() / 10000f).coerceIn(0f, 1f)

            if (previousApp.isNotEmpty() && previousApp != "Home Screen" && timeTaken < 60000L) {
                lastFromApp = previousApp
                lastToApp   = currentRealApp
                LiveLogger.log("📱 FLOW: $previousApp -> $currentRealApp")
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

        val history = db.dao().getTransition(from, to)
        if (history == null) {
            if (isReady) increaseRisk(15)
            db.dao().updateTransition(
                TransitionProfile(fromApp = from, toApp = to, avgTime = timeTaken, frequency = 1)
            )
        } else {
            if (isReady && kotlin.math.abs(history.avgTime - timeTaken) > 1500)
                increaseRisk(25)
            else if (isReady)
                decreaseRisk(5)

            if (!isReady || kotlin.math.abs(history.avgTime - timeTaken) <= 1500) {
                val newAvgTime = ((history.avgTime * history.frequency) + timeTaken) / (history.frequency + 1)
                db.dao().updateTransition(history.copy(avgTime = newAvgTime, frequency = history.frequency + 1))
            }
        }
        runContextualAI(
            velocity       = 0f,
            duration       = 0f,
            appName        = to,
            fromApp        = from,
            transitionTime = timeTaken
        )
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
                val count      = oldStats.interactionCount
                val newAvgVel  = ((oldStats.avgVelocity * count) + velocity) / (count + 1)
                val newAvgDur  = ((oldStats.avgDuration * count) + duration) / (count + 1)
                AppUsageProfile(appLabel, newAvgVel, newAvgDur, count + 1)
            }
            db.dao().updateAppStats(newStats)
        }

        runContextualAI(
            velocity       = velocity,
            duration       = duration,
            appName        = appLabel,
            fromApp        = lastFromApp,
            transitionTime = (lastAppSwitchTime - lastRealAppLeaveTime).coerceAtLeast(100L)
        )
        serviceScope.launch { syncBiometricsToWatch(-1) }
    }

    private fun increaseRisk(amount: Int) {
        val prefs   = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val current = prefs.getInt("current_risk", 0)
        val newRisk = (current + amount).coerceIn(0, 100)
        prefs.edit().putInt("current_risk", newRisk).apply()
        checkLock(newRisk)
    }

    private fun decreaseRisk(amount: Int) {
        val prefs   = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val current = prefs.getInt("current_risk", 0)
        prefs.edit().putInt("current_risk", (current - amount).coerceAtLeast(0)).apply()
    }

    private fun updateRiskScore(newCalculatedRisk: Int) {
        val prefs       = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val oldRisk     = prefs.getInt("current_risk", 0)
        val smoothedRisk = kotlin.math.ceil(
            (oldRisk.toFloat() + newCalculatedRisk.toFloat()) / 2f
        ).toInt().coerceIn(0, 100)
        prefs.edit().putInt("current_risk", smoothedRisk).apply()
        checkLock(smoothedRisk)
    }

    private fun checkLock(risk: Int) {
        val prefs       = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val sensitivity = prefs.getInt("ai_sensitivity", 1)

        val lockThreshold = when (sensitivity) {
            2    -> 60
            1    -> 80
            else -> 100
        }

        if (risk >= lockThreshold && !isLockdownCooldown) {
            isLockdownCooldown = true
            serviceScope.launch(Dispatchers.Main) {
                LiveLogger.log("🚨 AI LOCKDOWN: Risk ($risk%) hit threshold ($lockThreshold%)!")

                val timeFormat    = SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault())
                val currentTimeStr = timeFormat.format(Date())

                withContext(Dispatchers.IO) {
                    try {
                        val logEntry = SecurityLog(
                            timestamp = currentTimeStr,
                            title     = "AI Intruder Lockdown",
                            details   = "Unrecognized touch biometrics detected. Risk ($risk%) exceeded sensitivity threshold ($lockThreshold%). Defense protocol engaged.",
                            severity  = 2
                        )
                        db.securityLogDao().insertLog(logEntry)
                    } catch (e: Exception) {
                        Log.e("AI_LOCK", "Log failed: ${e.message}")
                    }
                }

                prefs.edit().putInt("current_risk", 50).apply()
                enforcer.lockDevice("AI Touch Dynamics Threat Detected")
            }
        }
    }

    private suspend fun syncBiometricsToWatch(loopCount: Int) {
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

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        removeAegisShield()
        isWatchSyncLoopRunning = false
        try {
            unregisterReceiver(ghostReceiver)
            unregisterReceiver(osBiometricSyncReceiver)
        } catch (_: IllegalArgumentException) {}
        serviceScope.cancel()
    }
}