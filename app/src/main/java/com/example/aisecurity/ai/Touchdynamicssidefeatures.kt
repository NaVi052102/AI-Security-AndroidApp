package com.example.aisecurity.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.aisecurity.ui.LiveLogger
import com.example.aisecurity.ui.LockOverlayService
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.*
import java.util.Locale

// ============================================================
//  TouchDynamicsSideFeatures — Non-AI Feature Extensions
//
//  Contains: Firebase remote commands, fake shutdown intercept,
//  Aegis/Blackout shield overlays, Poltergeist gesture engine,
//  Wi-Fi/BT/data/location toggles, and emergency comms.
//
//  All functions are extension functions on TouchDynamicsService
//  so they share the same instance state without coupling the
//  AI core to these features.
// ============================================================

// ── Ghost Receiver (Poltergeist command bus) ─────────────────
// Called from TouchDynamicsService to build the receiver object.

internal fun TouchDynamicsService.buildGhostReceiver() = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            if (intent?.action != "com.example.aisecurity.WAKE_MASTER_POLTERGEIST") return
            val target = intent.getStringExtra("TARGET_SETTING") ?: return

            when (target) {
                "SLAM_SHADE" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        this@buildGhostReceiver.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    }
                    this@buildGhostReceiver.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }
                "DEAD_STATE_ON"  -> deployBlackoutShield()
                "DEAD_STATE_OFF" -> removeBlackoutShield()
                "FORCE_SLEEP" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        serviceScope.launch(Dispatchers.Main) {
                            delay(300)
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                        }
                    }
                }
                "EMERGENCY_COMMS" -> executeEmergencyCommsCombo()
                in listOf("DATA", "WIFI", "BLUETOOTH", "LOCATION", "BATTERY") -> {
                    commandCooldowns[target] = System.currentTimeMillis()
                    executeDirectToggle(target)
                }
                "LOCK_AND_RECOVER_AIRPLANE" -> {
                    try {
                        startService(Intent(this@buildGhostReceiver, LockOverlayService::class.java))
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}

// ── Firebase Remote-Command Listener ────────────────────────

internal fun TouchDynamicsService.startFirebaseListener() {
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

        // Remote lock
        val cmdLockDevice = snapshot.getBoolean("cmd_lock_device") ?: false
        if (cmdLockDevice) {
            LiveLogger.log("🔒 POLTERGEIST: Ordinary Lock Command Received. Locking OS...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }
            firebaseDb.collection("Users").document(myUid)
                .set(hashMapOf("cmd_lock_device" to false), SetOptions.merge())
        }

        // Remote hidden camera
        val cmdTakePhoto = snapshot.getString("cmd_take_photo") ?: ""
        if (cmdTakePhoto.isNotEmpty()) {
            firebaseDb.collection("Users").document(myUid)
                .set(hashMapOf("cmd_take_photo" to ""), SetOptions.merge())
            serviceScope.launch(Dispatchers.Main) {
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    @Suppress("DEPRECATION")
                    val wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                                PowerManager.ON_AFTER_RELEASE,
                        "AISecurity::PreCameraWake"
                    )
                    wl.acquire(5_000L)
                    delay(300)

                    val photoIntent = Intent(
                        this@startFirebaseListener,
                        com.example.aisecurity.ui.HiddenCameraActivity::class.java
                    ).apply {
                        putExtra("CAMERA_TYPE", cmdTakePhoto)
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                    }
                    startActivity(photoIntent)
                    delay(2000)
                    if (wl.isHeld) wl.release()
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        // Mirror hardware toggle states
        val resolver = contentResolver

        fun checkMirrorState(stateField: String, targetSetting: String, physicalState: Boolean) {
            val firebaseState = snapshot.getBoolean(stateField) ?: return
            val lastState     = lastFirebaseStates[stateField]

            if (lastState == null) {
                lastFirebaseStates[stateField] = firebaseState
                if (firebaseState != physicalState) forceFirebaseSyncToReality(myUid)
                return
            }

            if (firebaseState != lastState) {
                if (firebaseState != physicalState) {
                    val lastAttemptTime = commandCooldowns[targetSetting] ?: 0L
                    if (System.currentTimeMillis() - lastAttemptTime > 10000) {
                        LiveLogger.log("⚙️ REMOTE CMD: Triggering toggle for $targetSetting...")
                        commandCooldowns[targetSetting] = System.currentTimeMillis()
                        executeDirectToggle(targetSetting)
                    }
                }
            } else if (firebaseState != physicalState) {
                LiveLogger.log("📱 LOCAL CMD: User manually changed $targetSetting. Syncing to Cloud...")
                forceFirebaseSyncToReality(myUid)
            }

            lastFirebaseStates[stateField] = firebaseState
        }

        try {
            val wifiOn  = Settings.Global.getInt(resolver, Settings.Global.WIFI_ON, 0) == 1
            val dataOn  = Settings.Global.getInt(resolver, "mobile_data", 0) == 1
            val btOn    = Settings.Global.getInt(resolver, Settings.Global.BLUETOOTH_ON, 0) == 1
            val locOn   = Settings.Secure.getInt(resolver, Settings.Secure.LOCATION_MODE, 0) != 0
            val saverOn = Settings.Global.getInt(resolver, "low_power", 0) == 1

            checkMirrorState("state_wifi",         "WIFI",      wifiOn)
            checkMirrorState("state_mobile_data",  "DATA",      dataOn)
            checkMirrorState("state_bluetooth",    "BLUETOOTH", btOn)
            checkMirrorState("state_location",     "LOCATION",  locOn)
            checkMirrorState("state_battery_saver","BATTERY",   saverOn)
        } catch (ex: Exception) { ex.printStackTrace() }
    }
}

internal fun TouchDynamicsService.forceFirebaseSyncToReality(myUid: String? = auth.currentUser?.uid) {
    if (myUid == null) return
    val resolver = contentResolver
    val wifiOn   = Settings.Global.getInt(resolver, Settings.Global.WIFI_ON, 0) == 1
    val dataOn   = Settings.Global.getInt(resolver, "mobile_data", 0) == 1
    val btOn     = Settings.Global.getInt(resolver, Settings.Global.BLUETOOTH_ON, 0) == 1
    val locOn    = Settings.Secure.getInt(resolver, Settings.Secure.LOCATION_MODE, 0) != 0
    val saverOn  = Settings.Global.getInt(resolver, "low_power", 0) == 1

    lastFirebaseStates["state_wifi"]          = wifiOn
    lastFirebaseStates["state_mobile_data"]   = dataOn
    lastFirebaseStates["state_bluetooth"]     = btOn
    lastFirebaseStates["state_location"]      = locOn
    lastFirebaseStates["state_battery_saver"] = saverOn

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

// ── Hardware Toggle Engine ───────────────────────────────────

internal fun TouchDynamicsService.executeDirectToggle(target: String) {
    serviceScope.launch(Dispatchers.IO) {
        var directApiSucceeded = false
        try {
            val resolver = contentResolver
            when (target) {
                "WIFI" -> {
                    val currentState = Settings.Global.getInt(resolver, Settings.Global.WIFI_ON, 0) == 1
                    val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    wifiMgr.isWifiEnabled = !currentState
                    directApiSucceeded = true
                }
                "BLUETOOTH" -> {
                    val currentState = Settings.Global.getInt(resolver, Settings.Global.BLUETOOTH_ON, 0) == 1
                    val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    @Suppress("DEPRECATION")
                    if (!currentState) btAdapter?.enable() else btAdapter?.disable()
                    directApiSucceeded = true
                }
                "DATA" -> {
                    val currentState = Settings.Global.getInt(resolver, "mobile_data", 0) == 1
                    // Try Settings.Global write first (works if WRITE_SETTINGS granted)
                    Settings.Global.putInt(resolver, "mobile_data", if (!currentState) 1 else 0)
                    directApiSucceeded = true
                }
                "LOCATION" -> {
                    val currentState = Settings.Secure.getInt(resolver, Settings.Secure.LOCATION_MODE, 0) != 0
                    Settings.Secure.putInt(resolver, Settings.Secure.LOCATION_MODE, if (!currentState) 3 else 0)
                    directApiSucceeded = true
                }
                "BATTERY" -> {
                    val currentState = Settings.Global.getInt(resolver, "low_power", 0) == 1
                    Settings.Global.putInt(resolver, "low_power", if (!currentState) 1 else 0)
                    directApiSucceeded = true
                }
            }
        } catch (e: Exception) {
            LiveLogger.log("⚡ Direct API blocked for $target — engaging Poltergeist fallback...")
            directApiSucceeded = false
        }

        if (directApiSucceeded) {
            delay(2000)
            forceFirebaseSyncToReality()
        } else {
            // For DATA specifically, try a reflection-based toggle before resorting to
            // Poltergeist gestures, which cause ghost-touch when the screen is active.
            val reflectionSucceeded = if (target == "DATA") tryMobileDataReflection() else false
            if (reflectionSucceeded) {
                delay(2000)
                forceFirebaseSyncToReality()
            } else {
                executePoltergeistFallback(target)
            }
        }
    }
}

// ── Mobile Data Reflection Toggle ───────────────────────────
// Tries ConnectivityManager reflection — works on many OEM ROMs
// that grant this app system/signature-level access. Returns true
// on success so the caller can skip the Poltergeist gesture layer.

private fun TouchDynamicsService.tryMobileDataReflection(): Boolean {
    return try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
            ?: return false
        val method = cm.javaClass.getDeclaredMethod("setMobileDataEnabled", Boolean::class.java)
        method.isAccessible = true
        val resolver = contentResolver
        val currentState = Settings.Global.getInt(resolver, "mobile_data", 0) == 1
        method.invoke(cm, !currentState)
        LiveLogger.log("📡 DATA: Reflection toggle succeeded (${if (!currentState) "ON" else "OFF"})")
        true
    } catch (e: Exception) {
        LiveLogger.log("📡 DATA: Reflection toggle failed — ${e.message}")
        false
    }
}

// ── Poltergeist Gesture Fallback ─────────────────────────────

private suspend fun TouchDynamicsService.executePoltergeistFallback(target: String) {
    // ── Guard: never fire gestures while user is actively using the phone ──
    if (isPoltergeistActive) return
    isPoltergeistActive = true

    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    // If the screen is ON and the device is interactive, the user is holding the phone.
    // Firing quick-settings gestures now causes the ghost-touch bug. Abort cleanly.
    if (pm.isInteractive) {
        LiveLogger.log("🚫 Poltergeist aborted for $target — screen is active, avoiding ghost touch.")
        isPoltergeistActive = false
        return
    }

    withContext(Dispatchers.Main) {
        try {
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Sentry::TileWake"
            )
            wl.acquire(3000)
            delay(300)

            val metrics = resources.displayMetrics
            val swipePath = Path().apply {
                moveTo(metrics.widthPixels * 0.85f, 1f)
                lineTo(metrics.widthPixels * 0.85f, metrics.heightPixels * 0.7f)
            }
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 300))
                    .build(),
                null, null
            )
            delay(800)

            val targets  = mutableMapOf<String, List<String>>()
            val keywords = when (target) {
                "DATA"      -> listOf("mobile data", "data network", "cellular", "datos", "data connection")
                "WIFI"      -> listOf("wi-fi", "wifi", "wlan", "internet")
                "BLUETOOTH" -> listOf("bluetooth", "bt")
                "LOCATION"  -> listOf("location", "gps", "ubicación")
                "BATTERY"   -> listOf("battery saver", "power saving", "low power")
                else        -> emptyList()
            }
            targets[target] = keywords

            spatialSurgicalComboTap(rootInActiveWindow, targets)

            if (targets.isNotEmpty()) {
                val scrollPath = Path().apply {
                    moveTo(metrics.widthPixels * 0.8f, metrics.heightPixels * 0.3f)
                    lineTo(metrics.widthPixels * 0.2f, metrics.heightPixels * 0.3f)
                }
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(scrollPath, 0, 200))
                        .build(),
                    null, null
                )
                delay(500)
                spatialSurgicalComboTap(rootInActiveWindow, targets)
            }

            if (wl.isHeld) wl.release()

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            delay(400)
            val metrics   = resources.displayMetrics
            val closePath = Path().apply {
                moveTo(metrics.widthPixels * 0.85f, metrics.heightPixels * 0.8f)
                lineTo(metrics.widthPixels * 0.85f, 1f)
            }
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(closePath, 0, 200))
                    .build(),
                null, null
            )
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            serviceScope.launch(Dispatchers.IO) {
                delay(2000)
                forceFirebaseSyncToReality()
            }
        }
    }
    isPoltergeistActive = false
}

internal fun TouchDynamicsService.executeEmergencyCommsCombo() {
    if (isPoltergeistActive) return
    isPoltergeistActive = true

    serviceScope.launch(Dispatchers.IO) {
        val resolver = contentResolver
        val wifiOn   = Settings.Global.getInt(resolver, Settings.Global.WIFI_ON, 0) == 1
        val dataOn   = Settings.Global.getInt(resolver, "mobile_data", 0) == 1
        val locOn    = Settings.Secure.getInt(resolver, Settings.Secure.LOCATION_MODE, 0) != 0

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
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "Sentry::ComboWake"
                )
                wl.acquire(4000)
                delay(300)

                val metrics   = resources.displayMetrics
                val swipePath = Path().apply {
                    moveTo(metrics.widthPixels * 0.85f, 1f)
                    lineTo(metrics.widthPixels * 0.85f, metrics.heightPixels * 0.7f)
                }
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 300))
                        .build(),
                    null, null
                )
                delay(800)

                val targets = mutableMapOf<String, List<String>>()
                if (!wifiOn) targets["WIFI"]     = listOf("wi-fi", "wifi", "wlan", "internet")
                if (!dataOn) targets["DATA"]     = listOf("mobile data", "data network", "cellular", "datos", "data connection")
                if (!locOn)  targets["LOCATION"] = listOf("location", "gps", "ubicación")

                spatialSurgicalComboTap(rootInActiveWindow, targets)

                val scrollPath = Path().apply {
                    moveTo(metrics.widthPixels * 0.8f, metrics.heightPixels * 0.3f)
                    lineTo(metrics.widthPixels * 0.2f, metrics.heightPixels * 0.3f)
                }
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(scrollPath, 0, 200))
                        .build(),
                    null, null
                )
                delay(500)
                spatialSurgicalComboTap(rootInActiveWindow, targets)

                if (wl.isHeld) wl.release()

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                delay(400)
                val metrics   = resources.displayMetrics
                val closePath = Path().apply {
                    moveTo(metrics.widthPixels * 0.85f, metrics.heightPixels * 0.8f)
                    lineTo(metrics.widthPixels * 0.85f, 1f)
                }
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(closePath, 0, 200))
                        .build(),
                    null, null
                )
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                isPoltergeistActive = false
                serviceScope.launch(Dispatchers.IO) {
                    delay(2000)
                    forceFirebaseSyncToReality()
                }
            }
        }
    }
}

// ── Gesture Primitives ───────────────────────────────────────

internal fun TouchDynamicsService.spatialSurgicalComboTap(
    rootNode: AccessibilityNodeInfo?,
    targets : MutableMap<String, List<String>>
) {
    if (rootNode == null || targets.isEmpty()) return

    val queue            = ArrayDeque<AccessibilityNodeInfo>()
    val successfullyTapped = mutableSetOf<String>()
    queue.add(rootNode)

    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""

        for ((category, keywords) in targets) {
            if (successfullyTapped.contains(category)) continue
            if (keywords.any { k -> text.contains(k) || desc.contains(k) || text == k }) {
                successfullyTapped.add(category)
                when {
                    node.isClickable          -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.parent?.isClickable == true -> node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    else -> {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        if (rect.centerY() > 50) {
                            fireHumanTap(rect.centerX().toFloat(), rect.centerY().toFloat())
                            fireHumanTap(rect.centerX().toFloat(), rect.centerY() - 80f)
                        }
                    }
                }
                break
            }
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
    }
    successfullyTapped.forEach { targets.remove(it) }
}

internal fun TouchDynamicsService.fireHumanTap(x: Float, y: Float) {
    try {
        val path = Path().apply {
            moveTo(x - 1f, y - 1f)
            lineTo(x + 1f, y + 1f)
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build(),
            null, null
        )
    } catch (e: Exception) { e.printStackTrace() }
}

internal fun TouchDynamicsService.triggerScrimSniper() {
    val now = System.currentTimeMillis()
    if (now - lastGuillotineTime < 300) return
    lastGuillotineTime = now
    serviceScope.launch(Dispatchers.Main) {
        repeat(5) {
            executeBottomScreenTap()
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (_: Exception) {}
            delay(50)
        }
    }
}

private fun TouchDynamicsService.executeBottomScreenTap() {
    try {
        val metrics = resources.displayMetrics
        val path = Path().apply {
            val midX    = metrics.widthPixels / 2f
            val bottomY = metrics.heightPixels * 0.85f
            moveTo(midX, bottomY)
            lineTo(midX + 1f, bottomY + 1f)
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 30))
                .build(),
            null, null
        )
    } catch (e: Exception) { e.printStackTrace() }
}

internal fun TouchDynamicsService.executeAntiGravitySwipe() {
    try {
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(metrics.widthPixels / 2f, metrics.heightPixels / 2f)
            lineTo(metrics.widthPixels / 2f, 0f)
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build(),
            null, null
        )
    } catch (e: Exception) { e.printStackTrace() }
}

// ── Overlay Shields ──────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility")
internal fun TouchDynamicsService.deployAegisShield() {
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
        ).apply { gravity = Gravity.TOP }
        windowManager?.addView(aegisShieldView, params)
        isAegisDeployed = true
    } catch (e: Exception) { e.printStackTrace() }
}

internal fun TouchDynamicsService.removeAegisShield() {
    if (!isAegisDeployed || windowManager == null || aegisShieldView == null) return
    try {
        windowManager?.removeView(aegisShieldView)
        isAegisDeployed = false
    } catch (e: Exception) { e.printStackTrace() }
}

@SuppressLint("ClickableViewAccessibility")
internal fun TouchDynamicsService.deployBlackoutShield() {
    if (blackoutShieldView != null || windowManager == null) return
    try {
        blackoutShieldView = View(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, _ -> true }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.OPAQUE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager?.addView(blackoutShieldView, params)
    } catch (e: Exception) { e.printStackTrace() }
}

internal fun TouchDynamicsService.removeBlackoutShield() {
    if (blackoutShieldView == null || windowManager == null) return
    try {
        windowManager?.removeView(blackoutShieldView)
        blackoutShieldView = null
    } catch (e: Exception) { e.printStackTrace() }
}

// ── Accessibility Event: Side-Feature Interception ───────────
// Called first in onAccessibilityEvent. Returns true if the event
// was fully handled here so the AI core can skip processing it.

internal fun TouchDynamicsService.handleSideFeatureEvents(
    event          : AccessibilityEvent?,
    rawPackageName : String,
    className      : String,
    eventType      : Int?,
    combinedText   : String,
    isLocked       : Boolean,
    prefs          : android.content.SharedPreferences
): Boolean {
    val km          = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    val defenseType = prefs.getString("protocol_defense_type", "OVERLAY") ?: "OVERLAY"

    // ── 1. Fake Dead State: block all UI interaction ─────────
    val isFakeDeadState = prefs.getBoolean("is_fake_dead_state", false)
    if (isFakeDeadState) {
        if (rawPackageName == "com.android.systemui" ||
            className.contains("panel", true) ||
            className.contains("notification", true) ||
            className.contains("expand", true) ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (_: Exception) {}
            return true
        }
    }

    // ── 2. Fake Shutdown Intercept ───────────────────────────
    val isEnvironmentHostile = km.isKeyguardLocked || isLocked

    val isFalsePositive = className.contains("volume", true) ||
            combinedText.contains("volume") ||
            className.contains("notification", true) ||
            combinedText.contains("emergency")

    val isMiuiPowerAction = rawPackageName.contains("miui.powercenter") ||
            rawPackageName.contains("powerkeeper") ||
            className.contains("shutdowncontainer", true) ||
            className.contains("globalactions", true)

    val hasPowerText = combinedText.contains("power off") ||
            combinedText.contains("restart") ||
            combinedText.contains("reboot") ||
            combinedText.contains("shut down")

    val isGenericSystemPower =
        (rawPackageName == "android" || rawPackageName.contains("systemui")) && hasPowerText

    val isPowerMenu = !isFalsePositive && (isMiuiPowerAction || isGenericSystemPower)
    val isFakeShutdownEnabled = prefs.getBoolean("enable_fake_shutdown", false)

    if (isPowerMenu) {
        if (isFakeShutdownEnabled) {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (_: Exception) {}
            try {
                startActivity(
                    Intent(this, com.example.aisecurity.ui.FakeShutdownActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                    }
                )
            } catch (e: Exception) { e.printStackTrace() }
            return true
        } else if (isEnvironmentHostile) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }
            return true
        }
    }

    // ── 3. Lockdown Defense (Aegis Shield) ───────────────────
    if (isLocked) {
        if (defenseType == "OVERLAY") {
            deployAegisShield()
            when {
                rawPackageName.contains("systemui") ||
                        eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    if (!isPoltergeistActive) {
                        try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (e: Exception) { e.printStackTrace() }
                        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        executeAntiGravitySwipe()
                        performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    }
                }
                rawPackageName.contains("com.android.settings") ||
                        rawPackageName.contains("coloros") ||
                        rawPackageName.contains("oplus") ||
                        rawPackageName.contains("miui") -> {
                    if (!isPoltergeistActive) performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }
                rawPackageName.isNotEmpty() &&
                        !rawPackageName.contains("com.example.aisecurity") -> {
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }
            }
        }
        return true
    } else {
        removeAegisShield()
    }

    // ── 4. Scrim Sniper (hostile env + notification shade) ───
    if (isEnvironmentHostile && rawPackageName == "com.android.systemui" && defenseType == "OVERLAY") {
        if (className.contains("panel", true) ||
            className.contains("notification", true) ||
            className.contains("expand", true) ||
            className.contains("settings", true) ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            triggerScrimSniper()
            return true
        }
    }

    return false // Not handled — let the AI core process it
}