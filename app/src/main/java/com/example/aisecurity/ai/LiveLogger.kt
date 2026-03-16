package com.example.aisecurity.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LiveLogger {
    fun logEvent(context: Context, title: String, details: String, severity: Int) {
        val db = SecurityDatabase.get(context)
        val timeFormat = SimpleDateFormat("MMM dd, yyyy - HH:mm:ss", Locale.getDefault())
        val currentTime = timeFormat.format(Date())

        val newLog = SecurityLog(
            timestamp = currentTime,
            title = title,
            details = details,
            severity = severity
        )

        // Run on background thread so it doesn't freeze the app
        CoroutineScope(Dispatchers.IO).launch {
            db.securityLogDao().insertLog(newLog)
        }
    }
}