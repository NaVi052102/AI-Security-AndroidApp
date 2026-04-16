package com.example.aisecurity.ai

import android.content.Context
import android.util.Base64
import com.example.aisecurity.ui.LiveLogger
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.io.File
import java.io.FileOutputStream

class AiCloudSyncManager(private val context: Context) {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val checkpointFile = File(context.filesDir, "brain_checkpoint.ckpt")
    private val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

    fun backupBrainToCloud(onComplete: (Boolean) -> Unit) {
        val user = auth.currentUser
        if (user == null) return

        if (!checkpointFile.exists() || checkpointFile.length() == 0L) return

        try {
            val fileBytes = checkpointFile.readBytes()
            val base64String = Base64.encodeToString(fileBytes, Base64.DEFAULT)

            val accumulatedTime = prefs.getLong("accumulated_time", 0L)
            val isReady = prefs.getBoolean("ai_ready", false)
            val threshold = prefs.getFloat("threshold", 1.0f)

            // 🚨 THE FIX: Grab the total swipe count to back it up!
            val totalSwipes = prefs.getInt("total_swipes", 0)

            val data = hashMapOf(
                "ai_brain_weights" to base64String,
                "ai_accumulated_time" to accumulatedTime,
                "ai_is_ready" to isReady,
                "ai_threshold" to threshold,
                "ai_swipe_count" to totalSwipes // 🚨 Save it to Firestore
            )

            db.collection("Users").document(user.uid)
                .set(data, SetOptions.merge())
                .addOnSuccessListener { onComplete(true) }
                .addOnFailureListener { onComplete(false) }
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete(false)
        }
    }

    fun restoreBrainFromCloud(onComplete: (Boolean) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(false)
            return
        }

        db.collection("Users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (document != null && document.contains("ai_brain_weights")) {
                    try {
                        val base64String = document.getString("ai_brain_weights") ?: ""
                        val fileBytes = Base64.decode(base64String, Base64.DEFAULT)

                        val fos = FileOutputStream(checkpointFile)
                        fos.write(fileBytes)
                        fos.close()

                        val savedTime = document.getLong("ai_accumulated_time") ?: 0L
                        val isReady = document.getBoolean("ai_is_ready") ?: false
                        val threshold = document.getDouble("ai_threshold")?.toFloat() ?: 1.0f

                        // 🚨 THE FIX: Retrieve the saved swipe count!
                        val savedSwipes = document.getLong("ai_swipe_count")?.toInt() ?: 0

                        prefs.edit()
                            .putLong("accumulated_time", savedTime)
                            .putBoolean("ai_ready", isReady)
                            .putFloat("threshold", threshold)
                            .putInt("restored_swipe_count", savedSwipes) // 🚨 Track historical swipes
                            .putInt("total_swipes", savedSwipes)
                            .apply()

                        LiveLogger.log("🧠 Cloud Brain & Progress Restored Successfully!")
                        onComplete(true)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        onComplete(false)
                    }
                } else {
                    onComplete(false)
                }
            }
            .addOnFailureListener { onComplete(false) }
    }
}