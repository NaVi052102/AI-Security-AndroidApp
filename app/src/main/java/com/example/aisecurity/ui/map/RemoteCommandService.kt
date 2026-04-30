package com.example.aisecurity.ui.map

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.example.aisecurity.ai.SecurityEnforcer
import com.example.aisecurity.ui.HiddenCameraActivity // 🚨 THIS IS THE FIX!
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

class RemoteCommandService : Service() {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var listener: ListenerRegistration? = null
    private lateinit var securityEnforcer: SecurityEnforcer

    override fun onCreate() {
        super.onCreate()
        securityEnforcer = SecurityEnforcer(this)
        startListeningForCommands()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keeps the service running reliably in the background
        return START_STICKY
    }

    private fun startListeningForCommands() {
        val uid = auth.currentUser?.uid ?: return

        listener = db.collection("Users").document(uid).addSnapshotListener { snapshot, e ->
            if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

            try {
                // ---------------------------------------------------------
                // 1. LISTEN FOR REMOTE LOCK COMMANDS
                // ---------------------------------------------------------
                val shouldLock = snapshot.getBoolean("cmd_lock_device") ?: false
                if (shouldLock) {
                    Log.d("REMOTE_CMD", "Received Remote Lock Command!")

                    // Immediately wipe the command from Firebase so it doesn't loop forever
                    db.collection("Users").document(uid).set(
                        hashMapOf("cmd_lock_device" to false),
                        SetOptions.merge()
                    )

                    // Execute the physical lock using your existing Enforcer
                    securityEnforcer.lockDevice("Remote Override", "ORDINARY")
                }

                // ---------------------------------------------------------
                // 2. LISTEN FOR REMOTE CAMERA COMMANDS
                // ---------------------------------------------------------
                val cameraCmd = snapshot.getString("cmd_take_photo") ?: ""
                if (cameraCmd == "Front" || cameraCmd == "Back") {
                    Log.d("REMOTE_CMD", "Received Secret Camera Command: $cameraCmd")

                    // Immediately wipe the command from Firebase so it doesn't loop forever
                    db.collection("Users").document(uid).set(
                        hashMapOf("cmd_take_photo" to ""),
                        SetOptions.merge()
                    )

                    // Launch the invisible HiddenCameraActivity to snap the photo silently
                    val intent = Intent(this, HiddenCameraActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        putExtra("CAMERA_FACING", cameraCmd)
                    }
                    startActivity(intent)
                }

            } catch (ex: Exception) {
                Log.e("REMOTE_CMD", "Error processing remote command: ${ex.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.remove() // Clean up the listener to prevent memory leaks
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We don't need to bind this service to an activity
    }
}