package com.example.aisecurity.ui.phonecase

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.ble.CaseManager
import com.example.aisecurity.ui.map.RemoteCommandService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

class PhoneCaseFragment : Fragment(R.layout.fragment_phone_case) {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var firestoreListener: ListenerRegistration? = null
    private var lastKnownCloudState: Boolean? = null

    private var tvStatus: TextView? = null
    private var btnLock: Button? = null
    private var btnUnlock: Button? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Broadcast receiver — called by RemoteCommandService when a remote lock
    // or unlock command arrives.
    //
    // BroadcastReceiver.onReceive() always runs on the MAIN thread, so calling
    // CaseManager.triggerLock() / triggerUnlock() here is safe.
    // ─────────────────────────────────────────────────────────────────────────
    private val caseLockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RemoteCommandService.ACTION_CASE_LOCK_STATE_CHANGED) return

            val isLocked = intent.getBooleanExtra(RemoteCommandService.EXTRA_IS_LOCKED, false)

            // 1. Mark state so the Firestore listener (which fires a moment later
            //    because the service also wrote to Firebase) skips the hardware
            //    call and doesn't double-trigger the servo.
            lastKnownCloudState = isLocked

            // 2. Update button UI immediately
            applyUiState(isLocked)

            // 3. Fire the physical BLE servo — this is the PRIMARY trigger path
            //    for remote commands. onReceive() runs on Main thread, so BLE
            //    calls are safe here.
            val prefs = context?.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
            val isAutoCaseLock = prefs?.getBoolean("auto_case_lock", false) ?: false

            if (isAutoCaseLock) {
                if (CaseManager.connectedMacAddress != null) {
                    if (isLocked) {
                        CaseManager.triggerLock()
                        Toast.makeText(context, "🔒 Case locked by Remote!", Toast.LENGTH_SHORT).show()
                    } else {
                        CaseManager.triggerUnlock()
                        Toast.makeText(context, "🔓 Case unlocked by Remote!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Remote lock received — BLE case not connected.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        db   = FirebaseFirestore.getInstance()

        tvStatus  = view.findViewById(R.id.tvCaseStatus)
        btnLock   = view.findViewById(R.id.btnLockCase)
        btnUnlock = view.findViewById(R.id.btnUnlockCase)

        val isNightMode = (requireContext().resources.configuration.uiMode
                and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        applyGlassButton(btnLock!!, "#EF4444", isNightMode)
        applyGlassButton(btnUnlock!!, "#10B981", isNightMode)

        // ── Firestore listener ────────────────────────────────────────────────
        // Handles state changes that come from sources OTHER than the remote
        // lock command (e.g. someone writing is_case_locked directly in Firebase
        // console, or another device). Also keeps the UI in sync after app restart.
        //
        // For remote lock commands, lastKnownCloudState is pre-stamped by the
        // broadcast receiver so this path skips the hardware to avoid
        // double-triggering the servo.
        val uid = auth.currentUser?.uid
        if (uid != null) {
            firestoreListener = db.collection("Users").document(uid)
                .addSnapshotListener { snapshot, e ->
                    if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

                    val isLocked = snapshot.getBoolean("is_case_locked") ?: false

                    // Skip if we already handled this state (broadcast receiver
                    // already processed the remote-lock path)
                    if (lastKnownCloudState == isLocked) return@addSnapshotListener
                    lastKnownCloudState = isLocked

                    applyUiState(isLocked)

                    // Hardware trigger for the non-remote-command path
                    // (e.g. Firebase console change, screen-off auto-lock, etc.)
                    val prefs = requireContext()
                        .getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                    val isAutoCaseLock = prefs.getBoolean("auto_case_lock", false)

                    if (isAutoCaseLock && CaseManager.connectedMacAddress != null) {
                        if (isLocked) {
                            CaseManager.triggerLock()
                            Toast.makeText(requireContext(), "🔒 Auto-Lock Triggered!", Toast.LENGTH_SHORT).show()
                        } else {
                            CaseManager.triggerUnlock()
                            Toast.makeText(requireContext(), "🔓 Auto-Unlock Triggered!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
        }

        // ── Hardware disconnect ───────────────────────────────────────────────
        CaseManager.isConnected.observe(viewLifecycleOwner) { isConnected ->
            if (!isConnected) {
                tvStatus?.text = "HARDWARE DISCONNECTED"
                tvStatus?.setTextColor(Color.parseColor("#94A3B8"))
            }
        }

        // ── Manual LOCK button ────────────────────────────────────────────────
        btnLock?.setOnClickListener {
            if (CaseManager.connectedMacAddress != null) {
                lastKnownCloudState = true
                CaseManager.triggerLock()
                updateFirebaseCaseState(true)
                applyUiState(true)
                Toast.makeText(requireContext(), "Locking Case...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Connect the case in Bluetooth settings first.", Toast.LENGTH_SHORT).show()
            }
        }

        // ── Manual UNLOCK button ──────────────────────────────────────────────
        btnUnlock?.setOnClickListener {
            if (CaseManager.connectedMacAddress != null) {
                lastKnownCloudState = false
                CaseManager.triggerUnlock()
                updateFirebaseCaseState(false)
                applyUiState(false)
                Toast.makeText(requireContext(), "Unlocking Case...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Connect the case in Bluetooth settings first.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Register the broadcast receiver.
    // RECEIVER_NOT_EXPORTED is required on Android 13+ (API 33).
    // ─────────────────────────────────────────────────────────────────────────
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            requireContext(),
            caseLockReceiver,
            IntentFilter(RemoteCommandService.ACTION_CASE_LOCK_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        requireContext().unregisterReceiver(caseLockReceiver)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pure UI update — button states and status text only, no hardware calls.
    // ─────────────────────────────────────────────────────────────────────────
    private fun applyUiState(isLocked: Boolean) {
        if (isLocked) {
            tvStatus?.text = "CASE SECURED 🔒"
            tvStatus?.setTextColor(Color.parseColor("#EF4444"))
            btnLock?.alpha      = 0.4f
            btnLock?.isEnabled  = false
            btnUnlock?.alpha    = 1.0f
            btnUnlock?.isEnabled = true
        } else {
            tvStatus?.text = "CASE UNLOCKED 🔓"
            tvStatus?.setTextColor(Color.parseColor("#10B981"))
            btnUnlock?.alpha    = 0.4f
            btnUnlock?.isEnabled = false
            btnLock?.alpha      = 1.0f
            btnLock?.isEnabled  = true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun updateFirebaseCaseState(isLocked: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("Users").document(uid).set(
            hashMapOf("is_case_locked" to isLocked),
            SetOptions.merge()
        )
    }

    private fun applyGlassButton(button: Button, strokeColor: String, isNightMode: Boolean) {
        val bg = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            orientation  = GradientDrawable.Orientation.TOP_BOTTOM
        }
        if (isNightMode) {
            bg.colors = intArrayOf(Color.parseColor("#1E293B"), Color.parseColor("#080E1A"))
            bg.setStroke(4, Color.parseColor(strokeColor))
            button.setTextColor(Color.parseColor("#FFFFFF"))
        } else {
            bg.colors = intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#F1F5F9"))
            bg.setStroke(4, Color.parseColor(strokeColor))
            button.setTextColor(Color.parseColor("#1E293B"))
        }
        button.background = bg
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onDestroyView() {
        super.onDestroyView()
        firestoreListener?.remove()
        tvStatus  = null
        btnLock   = null
        btnUnlock = null
    }
}