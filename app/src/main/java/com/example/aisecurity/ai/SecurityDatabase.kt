package com.example.aisecurity.ai

import android.content.Context
import androidx.room.*

// ── TABLE 1: RAW SWIPE LOGS ───────────────────────────────────────────────────
@Entity(tableName = "touch_profiles")
data class TouchProfile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val duration: Float,
    val velocityX: Float,
    val pressure: Float,
    @ColumnInfo(name = "app_name") val appName: String
)

// ── TABLE 2: TRAINING LOSS  (raw + EMA stored side-by-side) ──────────────────
@Entity(tableName = "training_loss")
data class LossPoint(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    /** Raw per-sample MSE from TFLite — noisy, kept for debugging */
    val lossValue: Float,
    /** Exponential moving average of lossValue — this is what the graph plots */
    @ColumnInfo(name = "ema_value") val emaValue: Float = lossValue
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
    @Query("SELECT * FROM app_transitions")
    suspend fun getAllTransitions(): List<TransitionProfile>
    @Query("SELECT * FROM app_transitions WHERE fromApp = :from AND toApp = :to LIMIT 1")
    suspend fun getTransition(from: String, to: String): TransitionProfile?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateTransition(transition: TransitionProfile)
    @Query("DELETE FROM app_transitions")
    suspend fun clearTransitions()

    // 4. LOSS TRACKING COMMANDS
    @Insert
    suspend fun insertLossPoint(point: LossPoint)

    /** Returns the full history ordered oldest→newest */
    @Query("SELECT * FROM training_loss ORDER BY timestamp ASC")
    suspend fun getTrainingLossHistory(): List<LossPoint>

    /** Cheap query: only the latest EMA value, used to seed the classifier on restart */
    @Query("SELECT ema_value FROM training_loss ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestEma(): Float?

    @Query("DELETE FROM training_loss")
    suspend fun clearLossHistory()

    // ── MASTER RESET ──────────────────────────────────────────────────────────
    @Transaction
    suspend fun wipeTotalData() {
        clearSwipes()
        clearAppStats()
        clearTransitions()
        clearLossHistory()
    }
}

@Database(
    entities = [
        TouchProfile::class,
        AppUsageProfile::class,
        TransitionProfile::class,
        SecurityLog::class,
        LossPoint::class
    ],
    version = 6,   // bumped: ema_value column added to training_loss
    exportSchema = false
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