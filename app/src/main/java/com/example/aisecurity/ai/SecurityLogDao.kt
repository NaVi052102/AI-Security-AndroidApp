package com.example.aisecurity.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SecurityLogDao {
    @Insert
    suspend fun insertLog(log: SecurityLog)

    @Query("SELECT * FROM security_logs ORDER BY id DESC")
    suspend fun getAllLogs(): List<SecurityLog>

    @Query("DELETE FROM security_logs")
    suspend fun clearAllLogs()

    // --- NEW: Deletes only the specific logs you checked ---
    @Query("DELETE FROM security_logs WHERE id IN (:idList)")
    suspend fun deleteLogsByIds(idList: List<Int>)
}