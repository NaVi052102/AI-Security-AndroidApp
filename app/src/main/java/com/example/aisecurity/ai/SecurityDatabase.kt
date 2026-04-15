package com.example.aisecurity.ai

import android.content.Context
import androidx.room.*

// --- TABLE 1: RAW SWIPE LOGS ---
@Entity(tableName = "touch_profiles")
data class TouchProfile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val duration: Float,
    val velocityX: Float,
    val pressure: Float,
    @ColumnInfo(name = "app_name") val appName: String
)

@Dao
interface SecurityDao {
    // 1. SWIPE COMMANDS
    @Insert suspend fun insertTouch(profile: TouchProfile)

    @Query("SELECT * FROM touch_profiles")
    suspend fun getTrainingData(): List<TouchProfile>

    @Query("SELECT COUNT(*) FROM touch_profiles")
    suspend fun getTotalTouchCount(): Int

    @Query("DELETE FROM touch_profiles")
    suspend fun clearSwipes()

    // 2. APP USAGE COMMANDS
    @Query("SELECT * FROM app_usage_stats ORDER BY interactionCount DESC")
    suspend fun getAllAppStats(): List<AppUsageProfile>

    @Query("SELECT * FROM app_usage_stats WHERE packageName = :pkgName")
    suspend fun getAppStats(pkgName: String): AppUsageProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateAppStats(stats: AppUsageProfile)

    @Query("DELETE FROM app_usage_stats")
    suspend fun clearAppStats()

    // 3. TRANSITION COMMANDS
    // 🚨 FIXED: Added this missing function to resolve the Dashboard errors
    @Query("SELECT * FROM app_transitions")
    suspend fun getAllTransitions(): List<TransitionProfile>

    @Query("SELECT * FROM app_transitions WHERE fromApp = :from AND toApp = :to LIMIT 1")
    suspend fun getTransition(from: String, to: String): TransitionProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateTransition(transition: TransitionProfile)

    @Query("DELETE FROM app_transitions")
    suspend fun clearTransitions()

    // --- THE MASTER RESET SWITCH ---
    @Transaction
    suspend fun wipeTotalData() {
        clearSwipes()
        clearAppStats()
        clearTransitions()
    }
}

@Database(
    entities = [
        TouchProfile::class,
        AppUsageProfile::class,
        TransitionProfile::class,
        SecurityLog::class
    ],
    version = 4
)
abstract class SecurityDatabase : RoomDatabase() {
    abstract fun dao(): SecurityDao
    abstract fun securityLogDao(): SecurityLogDao

    companion object {
        @Volatile private var INSTANCE: SecurityDatabase? = null

        fun get(context: Context): SecurityDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, SecurityDatabase::class.java, "ai_db")
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}