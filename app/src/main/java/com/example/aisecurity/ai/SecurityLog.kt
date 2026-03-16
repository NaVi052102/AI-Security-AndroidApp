package com.example.aisecurity.ai

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "security_logs")
data class SecurityLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: String,
    val title: String,
    val details: String,
    val severity: Int // 0 = Info, 1 = Warning, 2 = Critical
)