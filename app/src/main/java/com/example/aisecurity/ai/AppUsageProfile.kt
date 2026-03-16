package com.example.aisecurity.ai

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_usage_stats")
data class AppUsageProfile(
    @PrimaryKey val packageName: String,
    val avgVelocity: Float,
    val avgDuration: Float,
    val interactionCount: Int
)