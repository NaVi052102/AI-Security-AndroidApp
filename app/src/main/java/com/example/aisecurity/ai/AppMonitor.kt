package com.example.aisecurity.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object AppMonitor {

    // Helper function to dynamically ask Android what the current Home Screen is
    private fun getDefaultLauncherPackage(context: Context): String {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName ?: ""
    }

    fun getFriendlyCategory(context: Context, packageName: String): String {
        val lowerPackage = packageName.lowercase()

        // 1. UNIVERSAL HOME SCREEN CHECK
        // Matches the exact default launcher of whatever phone this runs on, plus a fallback for safety
        val currentLauncher = getDefaultLauncherPackage(context)
        if (packageName == currentLauncher || lowerPackage.contains("launcher") || lowerPackage.contains("home")) {
            return "📱 System Home"
        }

        // 2. UNIVERSAL SYSTEM UI (Notification shade, lock screen)
        if (lowerPackage == "com.android.systemui") {
            return "⚙️ System UI"
        }

        // 3. UNIVERSAL KEYBOARD CATCH
        // Catches almost all Google, Samsung, and third-party keyboards
        if (lowerPackage.contains("inputmethod") || lowerPackage.contains("keyboard") || lowerPackage.contains("latin") || lowerPackage.contains("ime")) {
            return "⌨️ Keyboard"
        }

        // 4. THE MAGIC: Ask Android for the REAL App Name (YouTube, Facebook, Chrome, etc.)
        return getAppNameFromPackage(context, packageName)
    }

    private fun getAppNameFromPackage(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            // Fallback: Make it readable if the exact name isn't found
            packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
        }
    }
}