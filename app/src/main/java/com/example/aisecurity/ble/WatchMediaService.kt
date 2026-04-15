package com.example.aisecurity.ble

import android.app.Notification
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast

class WatchMediaService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d("BLE_MUSIC_DEBUG", "🟢 ULTIMATE SERVICE IS ALIVE AND LISTENING!")

    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.d("BLE_MUSIC_DEBUG", "🔴 SERVICE DISCONNECTED BY ANDROID!")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        processNotification(sbn)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val pkg = sbn.packageName

        // ==========================================
        // 🎵 PATH A: IS IT A MUSIC NOTIFICATION?
        // ==========================================
        if (pkg.contains("spotify", true) || pkg.contains("music", true) || extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {

            Log.d("BLE_MUSIC_DEBUG", "🎯 CAUGHT MUSIC from $pkg!")
            var title = "Unknown Title"
            var artist = "Unknown Artist"
            var album = ""
            var isPlaying = true

            val token = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            if (token != null) {
                try {
                    val controller = MediaController(applicationContext, token)
                    val metadata = controller.metadata
                    if (metadata != null) {
                        title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: title
                        artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: artist
                        album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: album
                    }
                    isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                } catch (e: Exception) {
                    title = extras.getString(Notification.EXTRA_TITLE) ?: title
                    artist = extras.getString(Notification.EXTRA_TEXT) ?: artist
                }
            } else {
                title = extras.getString(Notification.EXTRA_TITLE) ?: title
                artist = extras.getString(Notification.EXTRA_TEXT) ?: artist
            }

            WatchManager.sendMusicToWatch(title, artist, album, isPlaying)
            return // 🚨 STOP HERE so we don't send it as a text message too!
        }

        // ==========================================
        // 💬 PATH B: IS IT A NORMAL NOTIFICATION? (SMS, Facebook, etc)
        // ==========================================
        val title = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        // Filter out annoying invisible system background alerts
        if (title.isEmpty() || text.isEmpty() || pkg == "android") return

        Log.d("BLE_WATCH_NOTIF", "Intercepted Normal Notif: $title - $text")
        WatchManager.sendNotificationToWatch(title, text)
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private var instance: WatchMediaService? = null

        fun syncCurrentMedia() {
            try {
                Log.d("BLE_MUSIC_DEBUG", "🔄 Attempting to sync current media...")
                val activeNotifs = instance?.activeNotifications
                if (activeNotifs == null) {
                    Log.d("BLE_MUSIC_DEBUG", "⚠️ activeNotifications is NULL. Service is blocked.")
                    return
                }

                activeNotifs.forEach { sbn ->
                    val pkg = sbn.packageName
                    if (pkg.contains("spotify", true) || pkg.contains("music", true) || sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                        instance?.processNotification(sbn)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e("BLE_MUSIC_DEBUG", "Service error: ${e.message}")
            }
        }

        fun sendMediaCommand(context: Context, keyCode: Int) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(eventDown)
            audioManager.dispatchMediaKeyEvent(eventUp)
        }
    }
}