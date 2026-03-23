package com.example.aisecurity.ai

import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.IBinder
import com.example.aisecurity.R
import com.example.aisecurity.ui.LiveLogger
import kotlinx.coroutines.*
import com.example.aisecurity.SecurityAdminReceiver

class NuclearLockdownService : Service() {

    private lateinit var audioManager: AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var lockJob: Job? = null

    // =========================================================
    // THE SUFFOCATE PROTOCOL: Intercept Volume Buttons
    // =========================================================
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                // If the thief tries to turn it down, instantly force it back to 100%
                if (currentVolume < maxVolume) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
                    LiveLogger.log("🚨 THIEF ATTEMPTED TO MUTE: Volume forced to MAX")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, SecurityAdminReceiver::class.java)

        // Register the Volume Interceptor
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LiveLogger.log("☢️ NUCLEAR PROTOCOL INITIATED")

        // 1. Force Volume to Maximum
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)

        // 2. Start the Siren (Looping)
        mediaPlayer = MediaPlayer.create(this, R.raw.siren)
        mediaPlayer?.isLooping = true
        mediaPlayer?.start()

        // 3. Start the Aggressive Lock Loop (Fires twice a second)
        lockJob = serviceScope.launch {
            // WARNING: Temporary 15-second safety kill-switch for testing!
            val startTime = System.currentTimeMillis()

            while (isActive && (System.currentTimeMillis() - startTime) < 15000) {
                if (dpm.isAdminActive(adminComponent)) {
                    dpm.lockNow()
                }
                delay(2500) // Sleep the screen every 500ms
            }

            // Auto-stop after 15 seconds so you can use your emulator again
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        LiveLogger.log("🛑 NUCLEAR PROTOCOL DISENGAGED")

        // Clean up everything so the phone goes back to normal
        lockJob?.cancel()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        unregisterReceiver(volumeReceiver)
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}