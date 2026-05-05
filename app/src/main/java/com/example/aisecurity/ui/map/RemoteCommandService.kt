package com.example.aisecurity.ui.map



import android.app.Service

import android.content.Intent

import android.os.IBinder

import android.util.Log

import com.example.aisecurity.ai.SecurityEnforcer

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



    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())



    override fun onCreate() {

        super.onCreate()

        securityEnforcer = SecurityEnforcer(this)

        startListeningForCommands()

    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        return START_STICKY

    }



    private fun startListeningForCommands() {

        val uid = auth.currentUser?.uid ?: return



        listener = db.collection("Users").document(uid).addSnapshotListener { snapshot, e ->

            if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener



            try {

                // ---------------------------------------------------------

                // 1. REMOTE LOCK COMMAND

                // ---------------------------------------------------------

                val shouldLock = snapshot.getBoolean("cmd_lock_device") ?: false

                if (shouldLock) {

                    Log.d("REMOTE_CMD", "Received Remote Lock Command!")

                    db.collection("Users").document(uid).set(

                        hashMapOf("cmd_lock_device" to false),

                        SetOptions.merge()

                    )

                    securityEnforcer.lockDevice("Remote Override", "ORDINARY", triggerLockFlag = false)

                }



                // ---------------------------------------------------------

                // 2. REMOTE CAMERA COMMAND

                // ---------------------------------------------------------

                val cameraCmd = snapshot.getString("cmd_take_photo") ?: ""

                if (cameraCmd == "Front" || cameraCmd == "Back") {

                    Log.d("REMOTE_CMD", "Received Secret Camera Command: $cameraCmd")



                    db.collection("Users").document(uid).set(

                        hashMapOf("cmd_take_photo" to ""),

                        SetOptions.merge()

                    )



                    serviceScope.launch {

                        if (shouldLock) {

                            delay(1500)

                        }



                        // 🚨 MIUI FIX: Do NOT call startActivity() from this background service!

                        // Send the command to the Accessibility Service, which MIUI trusts to launch screens.

                        val poltergeistIntent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")

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



    override fun onDestroy() {

        super.onDestroy()

        listener?.remove()

        serviceScope.cancel()

    }



    override fun onBind(intent: Intent?): IBinder? = null

}



