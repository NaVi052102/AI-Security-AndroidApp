package com.example.aisecurity.ui.map

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.aisecurity.ai.SecurityEnforcer
import com.example.aisecurity.ble.CaseManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.*

class RemoteCommandService : Service() {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var listener: ListenerRegistration? = null
    private lateinit var securityEnforcer: SecurityEnforcer

    // Coroutine scope pinned to Main — BLE requires the Main thread
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var screenStateReceiver: BroadcastReceiver? = null
    private var lastAutoLockTime = 0L

    companion object {
        /** Sent so PhoneCaseFragment can sync its UI buttons. */
        const val ACTION_CASE_LOCK_STATE_CHANGED =
            "com.example.aisecurity.CASE_LOCK_STATE_CHANGED"

        /** Boolean extra – true = locked, false = unlocked. */
        const val EXTRA_IS_LOCKED = "extra_is_locked"
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        securityEnforcer = SecurityEnforcer(this)
        startListeningForCommands()
        registerScreenStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Screen-state receiver – auto-lock on screen-off / auto-unlock on unlock
    // ─────────────────────────────────────────────────────────────────────────
    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        screenStateReceiver = object : BroadcastReceiver() {
            @SuppressLint("WakelockTimeout")
            override fun onReceive(context: Context?, intent: Intent?) {
                val ctx = context ?: return
                val prefs = ctx.applicationContext
                    .getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                val isAutoCaseLock = prefs.getBoolean("auto_case_lock", false)

                if (!isAutoCaseLock) return

                val uid = auth.currentUser?.uid
                val now = System.currentTimeMillis()

                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (now - lastAutoLockTime > 5000) {
                            lastAutoLockTime = now
                            Log.d("REMOTE_CMD", "Screen OFF → auto-locking case.")
                            serviceScope.launch {
                                // BLE on Main thread
                                triggerCaseHardware(isLocked = true)

                                withContext(Dispatchers.IO) {
                                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                                    val wl = pm.newWakeLock(
                                        PowerManager.PARTIAL_WAKE_LOCK, "Sentry::AutoLock"
                                    )
                                    wl.acquire(1500)
                                    if (uid != null) {
                                        db.collection("Users").document(uid).set(
                                            hashMapOf("is_case_locked" to true),
                                            SetOptions.merge()
                                        )
                                    }
                                    wl.release()
                                }
                                sendCaseLockBroadcast(true)
                            }
                        }
                    }

                    Intent.ACTION_USER_PRESENT -> {
                        if (now - lastAutoLockTime > 5000) {
                            lastAutoLockTime = now
                            Log.d("REMOTE_CMD", "Phone unlocked → auto-unlocking case.")
                            serviceScope.launch {
                                // BLE on Main thread
                                triggerCaseHardware(isLocked = false)

                                withContext(Dispatchers.IO) {
                                    if (uid != null) {
                                        db.collection("Users").document(uid).set(
                                            hashMapOf("is_case_locked" to false),
                                            SetOptions.merge()
                                        )
                                    }
                                }
                                sendCaseLockBroadcast(false)
                            }
                        }
                    }
                }
            }
        }
        registerReceiver(screenStateReceiver, filter)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firebase remote-command listener
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressLint("WakelockTimeout")
    private fun startListeningForCommands() {
        val uid = auth.currentUser?.uid ?: return

        // addSnapshotListener delivers on the Main thread by default,
        // so we are already on Main here — safe to call BLE directly.
        listener = db.collection("Users").document(uid)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

                try {
                    // ─────────────────────────────────────────────────────────
                    // 1. REMOTE LOCK COMMAND
                    // ─────────────────────────────────────────────────────────
                    val shouldLock = snapshot.getBoolean("cmd_lock_device") ?: false
                    if (shouldLock) {
                        Log.d("REMOTE_CMD", "Remote Lock Command received!")

                        // Reset the one-shot flag immediately
                        db.collection("Users").document(uid).set(
                            hashMapOf("cmd_lock_device" to false),
                            SetOptions.merge()
                        )

                        val prefs = applicationContext
                            .getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                        val isAutoCaseLock = prefs.getBoolean("auto_case_lock", false)

                        serviceScope.launch {

                            // ── STEP 1: ALWAYS write is_case_locked = true ────
                            // Unconditional — case lock state always mirrors phone
                            // lock state, regardless of any setting.
                            withContext(Dispatchers.IO) {
                                db.collection("Users").document(uid).set(
                                    hashMapOf("is_case_locked" to true),
                                    SetOptions.merge()
                                )
                            }
                            Log.d("REMOTE_CMD", "Firebase: is_case_locked → true")

                            // ── STEP 2: Notify PhoneCaseFragment UI ───────────
                            sendCaseLockBroadcast(true)

                            // ── STEP 3: Fire the physical BLE servo ───────────
                            // triggerCaseHardware() MUST be called on the Main
                            // thread. serviceScope is pinned to Dispatchers.Main
                            // so this is safe here.
                            // Only fires when Auto-Case-Lock is ON in settings.
                            if (isAutoCaseLock) {
                                lastAutoLockTime = System.currentTimeMillis()
                                triggerCaseHardware(isLocked = true)

                                // Wait for BLE packet to transmit before the
                                // screen goes off and the radio is throttled.
                                delay(1500)
                            }

                            // ── STEP 4: Lock the Android screen ──────────────
                            securityEnforcer.lockDevice(
                                "Remote Override", "ORDINARY", triggerLockFlag = false
                            )
                        }
                    }

                    // ─────────────────────────────────────────────────────────
                    // 2. REMOTE CAMERA COMMAND
                    // ─────────────────────────────────────────────────────────
                    val cameraCmd = snapshot.getString("cmd_take_photo") ?: ""
                    if (cameraCmd == "Front" || cameraCmd == "Back") {
                        db.collection("Users").document(uid).set(
                            hashMapOf("cmd_take_photo" to ""),
                            SetOptions.merge()
                        )
                        serviceScope.launch {
                            if (shouldLock) delay(2000)
                            val poltergeistIntent =
                                Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
                            poltergeistIntent.putExtra("TARGET_SETTING", "TAKE_PHOTO")
                            poltergeistIntent.putExtra("CAMERA_TYPE", cameraCmd)
                            sendBroadcast(poltergeistIntent)
                        }
                    }

                } catch (ex: Exception) {
                    Log.e("REMOTE_CMD", "Error processing remote command: ${ex.message}")
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Trigger the physical BLE case hardware.
    //
    // ⚠️  Android BLE operations MUST run on the Main thread.
    //     This function is always called from serviceScope (Dispatchers.Main).
    // ─────────────────────────────────────────────────────────────────────────
    private fun triggerCaseHardware(isLocked: Boolean) {
        if (CaseManager.connectedMacAddress == null) {
            Log.w("REMOTE_CMD", "BLE case not connected — skipping hardware trigger.")
            return
        }
        if (isLocked) {
            CaseManager.triggerLock()
            Log.d("REMOTE_CMD", "✅ CaseManager.triggerLock() fired on Main thread.")
        } else {
            CaseManager.triggerUnlock()
            Log.d("REMOTE_CMD", "✅ CaseManager.triggerUnlock() fired on Main thread.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Local broadcast → PhoneCaseFragment updates its button UI
    // ─────────────────────────────────────────────────────────────────────────
    private fun sendCaseLockBroadcast(isLocked: Boolean) {
        val intent = Intent(ACTION_CASE_LOCK_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_LOCKED, isLocked)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d("REMOTE_CMD", "Broadcast sent → is_case_locked = $isLocked")
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onDestroy() {
        super.onDestroy()
        listener?.remove()
        serviceScope.cancel()
        screenStateReceiver?.let { unregisterReceiver(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
