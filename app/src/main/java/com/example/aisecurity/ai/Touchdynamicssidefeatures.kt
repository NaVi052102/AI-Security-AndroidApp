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
import android.location.LocationManager
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

// ============================================================
//  TouchDynamicsSideFeatures — Non-AI Feature Extensions
//
//  Contains: Firebase remote commands, fake shutdown intercept,
//  Aegis/Blackout shield overlays, Wi-Fi/BT/data/location toggles.
// ============================================================

// ── Ghost Receiver (Command Bus) ─────────────────
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
                "DEAD_STATE_ON"  -> {
                    deployBlackoutShield()
                }
                "DEAD_STATE_OFF" -> {
                    removeBlackoutShield()
                }
                "FORCE_SLEEP" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        serviceScope.launch(Dispatchers.Main) {
                            delay(300)
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                        }
                    }
                }
                "EMERGENCY_COMMS" -> {
                    // Strict 30-second cooldown to prevent Radar Bouncing from spamming the UI
                    val lastTime = commandCooldowns[target] ?: 0L
                    if (System.currentTimeMillis() - lastTime > 30000) {
                        commandCooldowns[target] = System.currentTimeMillis()
                        executeEmergencyCommsCombo()
                    } else {
                        LiveLogger.log("⚠️ Emergency Comms blocked (Cooldown active).")
                    }
                }
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
        if (isBooting) return@addSnapshotListener

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

// ── Pure Background API Hardware Toggle Engine ────────────────
// Uses ONLY Silent APIs for remote commands.

internal fun TouchDynamicsService.executeDirectToggle(target: String) {
    serviceScope.launch(Dispatchers.IO) {
        try {
            val resolver = contentResolver
            when (target) {
                "WIFI" -> {
                    val currentState = Settings.Global.getInt(resolver, Settings.Global.WIFI_ON, 0) == 1
                    val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    wifiMgr.isWifiEnabled = !currentState
                }
                "BLUETOOTH" -> {
                    val currentState = Settings.Global.getInt(resolver, Settings.Global.BLUETOOTH_ON, 0) == 1
                    val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    @Suppress("DEPRECATION")
                    if (!currentState) btAdapter?.enable() else btAdapter?.disable()
                }
                "DATA" -> {
                    val currentState = Settings.Global.getInt(resolver, "mobile_data", 0) == 1
                    val targetState = !currentState
                    try { Settings.Global.putInt(resolver, "mobile_data", if (targetState) 1 else 0) } catch (_: Exception) {}
                    setMobileDataReflection(targetState)
                }
                "LOCATION" -> {
                    @Suppress("DEPRECATION")
                    val currentState = Settings.Secure.getInt(resolver, Settings.Secure.LOCATION_MODE, 0) != 0

                    @Suppress("DEPRECATION")
                    Settings.Secure.putInt(resolver, Settings.Secure.LOCATION_MODE, if (!currentState) 3 else 0)
                }
                "BATTERY" -> {
                    val currentState = Settings.Global.getInt(resolver, "low_power", 0) == 1
                    Settings.Global.putInt(resolver, "low_power", if (!currentState) 1 else 0)
                }
            }
        } catch (e: Exception) {
            LiveLogger.log("⚡ Direct API blocked for $target. System restricted.")
        } finally {
            delay(2000)
            forceFirebaseSyncToReality()
        }
    }
}

// ── Mobile Data Reflection Toggle ───────────────────────────
private fun TouchDynamicsService.setMobileDataReflection(enable: Boolean): Boolean {
    return try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) ?: return false
        val method = cm.javaClass.getDeclaredMethod("setMobileDataEnabled", Boolean::class.java)
        method.isAccessible = true
        method.invoke(cm, enable)
        LiveLogger.log("📡 DATA: Reflection toggle succeeded (${if (enable) "ON" else "OFF"})")
        true
    } catch (e: Exception) {
        LiveLogger.log("📡 DATA: Reflection toggle failed — ${e.message}")
        false
    }
}

// ── UI AUTOMATION: Emergency Comms (Bypasses API 29+ Blocks) ──
internal fun TouchDynamicsService.executeEmergencyCommsCombo() {
    serviceScope.launch(Dispatchers.Main) { // 🚨 MUST RUN ON MAIN THREAD
        val resolver = contentResolver
        val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val locMgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 1. CHECK THE ACTUAL SYSTEM TRUTH
        var isWifiOn = try { wifiMgr.isWifiEnabled } catch (e: Exception) { false }
        var isDataOn = try { Settings.Global.getInt(resolver, "mobile_data", 0) == 1 } catch (e: Exception) { false }
        var isLocOn = try { locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }

        // If everything is already ON, we don't need to do anything at all!
        if (isWifiOn && isDataOn && isLocOn) {
            LiveLogger.log("✅ Emergency Comms: Wi-Fi, Data, and Location are already ON. No action needed.")
            return@launch
        }

        LiveLogger.log("⚠️ Emergency Comms: Missing connections. Attempting silent background APIs first...")

        // 2. ATTEMPT SILENT BACKGROUND APIs (Fastest method)
        serviceScope.launch(Dispatchers.IO) {
            try { if (!isWifiOn) { @Suppress("DEPRECATION") wifiMgr.isWifiEnabled = true } } catch (e: Exception) {}
            try { if (!isDataOn) { Settings.Global.putInt(resolver, "mobile_data", 1); setMobileDataReflection(true) } } catch (e: Exception) {}
            try { if (!isLocOn) { @Suppress("DEPRECATION") Settings.Secure.putInt(resolver, Settings.Secure.LOCATION_MODE, 3) } } catch (e: Exception) {}
        }.join() // Wait for background attempts to finish

        delay(1000) // Give the Android OS a moment to apply the network changes

        // 3. RE-CHECK STATES TO SEE IF APIs WORKED
        isWifiOn = try { wifiMgr.isWifiEnabled } catch (e: Exception) { false }
        isDataOn = try { Settings.Global.getInt(resolver, "mobile_data", 0) == 1 } catch (e: Exception) { false }
        isLocOn = try { locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }

        if (isWifiOn && isDataOn && isLocOn) {
            LiveLogger.log("✅ Emergency Comms: Successfully forced ON via background APIs.")
            forceFirebaseSyncToReality()
            return@launch
        }

        // 4. UI AUTOMATION FALLBACK (Only targets exactly what is still OFF)
        var needsWifi = !isWifiOn
        var needsData = !isDataOn
        var needsLoc = !isLocOn

        LiveLogger.log("⚠️ Background APIs blocked by OS. Deploying targeted UI Automation...")

        // Physically pull down the Quick Settings Panel
        try {
            val metrics = resources.displayMetrics
            val swipeDownPath = Path().apply {
                moveTo(metrics.widthPixels * 0.8f, 0f)
                lineTo(metrics.widthPixels * 0.8f, metrics.heightPixels * 0.8f)
            }
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(swipeDownPath, 0, 500))
                    .build(),
                null, null
            )
        } catch (e: Exception) {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        }

        delay(1500) // Wait for panel to drop

        var clickedSomething = false
        for (attempt in 1..3) {
            try {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {

                    val activeKeywordsToSearch = mutableMapOf<String, String>()
                    if (needsWifi) {
                        activeKeywordsToSearch["wi-fi"] = "WIFI"
                        activeKeywordsToSearch["wlan"] = "WIFI"
                        activeKeywordsToSearch["internet"] = "WIFI"
                    }
                    if (needsData) {
                        activeKeywordsToSearch["mobile data"] = "DATA"
                        activeKeywordsToSearch["data"] = "DATA"
                    }
                    if (needsLoc) {
                        activeKeywordsToSearch["location"] = "LOC"
                        activeKeywordsToSearch["gps"] = "LOC"
                    }

                    for ((keyword, category) in activeKeywordsToSearch) {

                        // 🚨 FIX: Keyword Overlap Protection
                        // If we already successfully clicked a button in this category, SKIP any remaining keywords!
                        // This stops "data" from un-clicking "Mobile data"
                        if (category == "WIFI" && !needsWifi) continue
                        if (category == "DATA" && !needsData) continue
                        if (category == "LOC" && !needsLoc) continue

                        val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
                        for (node in nodes) {

                            var current: AccessibilityNodeInfo? = node
                            var successfullyClicked = false

                            while (current != null) {
                                if (current.isClickable) {
                                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    successfullyClicked = true
                                    break
                                }
                                current = current.parent
                            }

                            if (!successfullyClicked) {
                                node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                successfullyClicked = true
                            }

                            if (successfullyClicked) {
                                clickedSomething = true
                                // Mark the category as completed to prevent double-clicks
                                when (category) {
                                    "WIFI" -> needsWifi = false
                                    "DATA" -> needsData = false
                                    "LOC" -> needsLoc = false
                                }
                                delay(400)
                                break // Break out of this keyword's node loop, move to the next keyword
                            }
                        }
                    }
                    rootNode.recycle()
                    // If all needed toggles have been clicked, break out of retry loop
                    if (!needsWifi && !needsData && !needsLoc) break
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            delay(500)
        }

        // 5. Dismiss Quick Settings
        delay(800)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(300)
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }

        delay(2000)
        forceFirebaseSyncToReality() // Sync final actual states back to Firestore
    }
}

// ── Gesture Primitives ───────────────────────────────────────

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

    // ── 1. Fake Dead State: Passive Black Screen ─────────────
    val isFakeDeadState = prefs.getBoolean("is_fake_dead_state", false)
    if (isFakeDeadState) {
        val isSystemUi = rawPackageName == "com.android.systemui"
        if (isSystemUi ||
            className.contains("panel", true) ||
            className.contains("notification", true) ||
            className.contains("expand", true) ||
            (isSystemUi && eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED)
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }
            try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (_: Exception) {}
        }
        return true
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
                    try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (e: Exception) { e.printStackTrace() }
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    executeAntiGravitySwipe()
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }
                rawPackageName.contains("com.android.settings") ||
                        rawPackageName.contains("coloros") ||
                        rawPackageName.contains("oplus") ||
                        rawPackageName.contains("miui") -> {
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
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

    return false
}