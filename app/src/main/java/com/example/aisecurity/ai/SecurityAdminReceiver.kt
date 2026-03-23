package com.example.aisecurity.ai

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.example.aisecurity.ui.LiveLogger

class SecurityAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        LiveLogger.log("🛡️ Device Admin Privileges Granted")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        LiveLogger.log("⚠️ Device Admin Privileges Revoked")
    }
}