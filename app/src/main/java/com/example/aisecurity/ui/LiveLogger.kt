package com.example.aisecurity.ui

import androidx.lifecycle.MutableLiveData

object LiveLogger {
    // This holds the text that appears on the screen
    val logData = MutableLiveData<String>()
    private val history =  ArrayDeque<String>()

    fun log(message: String) {
        // Keep only the last 4 messages to prevent clutter
        if (history.size >= 4) history.removeFirst()
        history.addLast(message)

        // Combine them into one string for the UI
        logData.postValue(history.joinToString("\n\n"))
    }

    // --- NEW: CLEAR FUNCTION ---
    fun clear() {
        history.clear()
        logData.postValue(">_ SYSTEM MEMORY WIPED.\nReady for new data...")
    }
}