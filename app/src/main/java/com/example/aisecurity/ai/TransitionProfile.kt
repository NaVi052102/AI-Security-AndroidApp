package com.example.aisecurity.ai

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_transitions")
data class TransitionProfile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fromApp: String,
    val toApp: String,
    val avgTime: Long,      // How long you usually take to switch
    val frequency: Int      // How often you do this specific switch
)