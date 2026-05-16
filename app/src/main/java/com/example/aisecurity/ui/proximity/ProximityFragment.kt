package com.example.aisecurity.ui.proximity

import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.aisecurity.R
import com.example.aisecurity.ble.WatchManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class ProximityFragment : Fragment(), SensorEventListener {

    private val rssiHistory = mutableListOf<Float>()

    // ─── Local distance smoother (EMA on top of Kalman) ───────────────
    private var emaDistance: Double = 0.0
    private var emaInitialized = false
    private val EMA_ALPHA = 0.25               // lower = smoother, higher = more reactive
    private val distanceWindow = ArrayDeque<Double>(10)
    private val OUTLIER_MAX_JUMP = 6.0         // meters — ignore if jumps more than this
    // ──────────────────────────────────────────────────────────────────

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var proximitySensor: Sensor? = null
    private var lightSensor: Sensor? = null

    private lateinit var tvAccelData: TextView
    private lateinit var tvGyroData: TextView
    private lateinit var tvPocketStatus: TextView
    private lateinit var tvPeakDistance: TextView
    private lateinit var tvSessionTimer: TextView
    private lateinit var rssiGraph: RssiChartView
    private lateinit var tvCurrentRssiValue: TextView

    private var currentAccel = floatArrayOf(0f, 0f, 0f)
    private var currentGyro = floatArrayOf(0f, 0f, 0f)
    private var lastAccel = floatArrayOf(0f, 0f, 0f)
    private var lastGyro = floatArrayOf(0f, 0f, 0f)

    private var lastMovementAlertTime = 0L
    private val MOVEMENT_THRESHOLD = 2.5f
    private val GYRO_THRESHOLD = 1.5f

    private var isObjectClose = false
    private var isEnvironmentDark = false
    private var currentPocketState = "Out of Pocket"
    private var lastLoggedPocketState = ""

    private var sessionStartTime = 0L
    private var historicPeakDistance = 0.0

    private var telemetrySyncJob: Job? = null
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_proximity, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        db   = FirebaseFirestore.getInstance()

        sensorManager  = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor    = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor     = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        lightSensor    = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        val tvScanStatus    = view.findViewById<TextView>(R.id.tvScanStatus)
        val tvDistance      = view.findViewById<TextView>(R.id.tvDistance)
        val tvHeartRate     = view.findViewById<TextView>(R.id.tvHeartRate)
        val tvSecurityStatus = view.findViewById<TextView>(R.id.tvSecurityStatus)
        val tvTouchStatus   = view.findViewById<TextView>(R.id.tvTouchStatus)

        rssiGraph          = view.findViewById(R.id.rssiGraph)
        tvCurrentRssiValue = view.findViewById(R.id.tvCurrentRssiValue)
        tvAccelData        = view.findViewById(R.id.tvAccelData)
        tvGyroData         = view.findViewById(R.id.tvGyroData)
        tvPocketStatus     = view.findViewById(R.id.tvPocketStatus)
        tvPeakDistance     = view.findViewById(R.id.tvPeakDistance)
        tvSessionTimer     = view.findViewById(R.id.tvSessionTimer)

        if (accelSensor == null) tvAccelData.text = "No Hardware"
        if (gyroSensor  == null) tvGyroData.text  = "No Hardware"

        // ── Connection status ──────────────────────────────────────────
        WatchManager.liveStatus.observe(viewLifecycleOwner) { status ->
            when {
                status.contains("Disconnected", true) || status.contains("Lost", true) -> {
                    tvScanStatus.text = "Disconnected"
                    tvScanStatus.setTextColor(Color.parseColor("#EF4444"))
                    tvDistance.text = "-- m"
                    tvDistance.setTextColor(Color.parseColor("#94A3B8"))
                    tvHeartRate.text = "-- bpm"
                    tvSecurityStatus.text = "DISCONNECTED"
                    tvSecurityStatus.setTextColor(Color.parseColor("#EF4444"))
                    tvCurrentRssiValue.text = "-- dBm"
                    tvCurrentRssiValue.setTextColor(Color.parseColor("#94A3B8"))

                    // Reset local smoother on disconnect
                    emaInitialized = false
                    distanceWindow.clear()
                }
                status.contains("Secure Link Established", true) ||
                        status.contains("Connected", true) -> {
                    tvScanStatus.text = "Connected"
                    tvScanStatus.setTextColor(Color.parseColor("#10B981"))
                }
                else -> {
                    tvScanStatus.text = status
                    tvScanStatus.setTextColor(Color.parseColor("#94A3B8"))
                }
            }
        }

        // ── Distance observer with local EMA smoother ─────────────────
        WatchManager.liveDistance.observe(viewLifecycleOwner) { rawValue ->
            try {
                val raw = rawValue.toDouble()

                // 1. Outlier rejection: ignore impossible jumps
                if (emaInitialized && abs(raw - emaDistance) > OUTLIER_MAX_JUMP) return@observe

                // 2. EMA smoothing layer on top of Kalman from WatchManager
                emaDistance = if (!emaInitialized) {
                    emaInitialized = true
                    raw
                } else {
                    EMA_ALPHA * raw + (1.0 - EMA_ALPHA) * emaDistance
                }

                // 3. Track window for stability confidence
                distanceWindow.addLast(emaDistance)
                if (distanceWindow.size > 10) distanceWindow.removeFirst()

                val smoothed = emaDistance
                val rounded  = smoothed.roundToInt()

                // 4. Stability indicator: low variance = stable reading
                val stable = if (distanceWindow.size >= 5) {
                    val mean = distanceWindow.average()
                    val variance = distanceWindow.map { (it - mean) * (it - mean) }.average()
                    variance < 0.5
                } else false

                tvDistance.text = if (stable) "$rounded m" else "~$rounded m"

                // 5. Update peak
                if (smoothed > historicPeakDistance && smoothed < 40.0) {
                    historicPeakDistance = smoothed
                    tvPeakDistance.text = "${historicPeakDistance.roundToInt()} m"
                }

                // 6. Color by threshold
                val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                val lockThreshold    = prefs.getFloat("radar_threshold_meters", 5.0f)
                val warningThreshold = prefs.getFloat("radar_warning_meters", 2.0f)

                tvDistance.setTextColor(Color.parseColor(when {
                    smoothed <= warningThreshold -> "#10B981"
                    smoothed < lockThreshold     -> "#F59E0B"
                    else                         -> "#EF4444"
                }))

            } catch (e: NumberFormatException) {
                tvDistance.text = "-- m"
                tvDistance.setTextColor(Color.parseColor("#94A3B8"))
            }
        }

        // ── RSSI graph ─────────────────────────────────────────────────
        WatchManager.liveRSSI.observe(viewLifecycleOwner) { rssi ->
            val signal = rssi.toFloat()

            if (rssi == 0) {
                tvCurrentRssiValue.text = "Wi-Fi RTT Active"
                tvCurrentRssiValue.setTextColor(Color.parseColor("#3B82F6"))
                return@observe
            }

            tvCurrentRssiValue.text = "$rssi dBm"

            rssiHistory.add(signal)
            if (rssiHistory.size > 40) rssiHistory.removeAt(0)

            rssiGraph.setData(rssiHistory)
            rssiGraph.visibility = View.VISIBLE

            tvCurrentRssiValue.setTextColor(Color.parseColor(
                when {
                    signal < -85 -> "#EF4444"
                    signal < -70 -> "#F59E0B"
                    else         -> "#10B981"
                }
            ))
        }

        WatchManager.liveHeartRate.observe(viewLifecycleOwner) { hr ->
            tvHeartRate.text = "$hr bpm"
        }

        WatchManager.wristStatus.observe(viewLifecycleOwner) { wrist ->
            val wristStr = wrist.toString()
            tvSecurityStatus.text = wristStr.uppercase()
            tvSecurityStatus.setTextColor(Color.parseColor(
                if (wristStr.contains("ON WRIST", true)) "#10B981" else "#EF4444"
            ))
        }

        WatchManager.touchStatus.observe(viewLifecycleOwner) { touch ->
            tvTouchStatus.text = touch.toString()
        }
    }

    override fun onResume() {
        super.onResume()
        if (sessionStartTime == 0L) sessionStartTime = System.currentTimeMillis()

        accelSensor?.let    { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroSensor?.let     { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        lightSensor?.let    { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        startFirebaseTelemetrySync()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        telemetrySyncJob?.cancel()
    }

    private fun checkMovementAndAlert() {
        val deltaAccel = abs(currentAccel[0] - lastAccel[0]) + abs(currentAccel[1] - lastAccel[1]) + abs(currentAccel[2] - lastAccel[2])
        val deltaGyro  = abs(currentGyro[0]  - lastGyro[0])  + abs(currentGyro[1]  - lastGyro[1])  + abs(currentGyro[2]  - lastGyro[2])

        if (deltaAccel > MOVEMENT_THRESHOLD || deltaGyro > GYRO_THRESHOLD) {
            val dist = emaDistance.toFloat()           // use smoothed value, not raw
            val now  = System.currentTimeMillis()

            val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
            val warningMeters = prefs.getFloat("radar_warning_meters", 2.0f)

            if (dist >= warningMeters && (now - lastMovementAlertTime > 15000)) {
                lastMovementAlertTime = now
                WatchManager.logAndNotify(
                    requireContext(),
                    "⚠️ THEFT ALERT",
                    "Device movement detected while outside safe zone (${dist.roundToInt()} m away).",
                    2
                )
            }
        }

        lastAccel = currentAccel.clone()
        lastGyro  = currentGyro.clone()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                currentAccel = event.values.clone()
                tvAccelData.text = String.format(Locale.US, "X: %.2f\nY: %.2f\nZ: %.2f", currentAccel[0], currentAccel[1], currentAccel[2])
                checkMovementAndAlert()
            }
            Sensor.TYPE_GYROSCOPE -> {
                currentGyro = event.values.clone()
                tvGyroData.text = String.format(Locale.US, "X: %.2f\nY: %.2f\nZ: %.2f", currentGyro[0], currentGyro[1], currentGyro[2])
                checkMovementAndAlert()
            }
            Sensor.TYPE_PROXIMITY -> {
                isObjectClose = event.values[0] < event.sensor.maximumRange && event.values[0] <= 5.0f
                evaluatePocketMode()
            }
            Sensor.TYPE_LIGHT -> {
                isEnvironmentDark = event.values[0] < 40.0f
                evaluatePocketMode()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun evaluatePocketMode() {
        val zAxis = currentAccel[2]
        val yAxis = currentAccel[1]

        currentPocketState = when {
            zAxis <= -7.0f && isObjectClose                                   -> "Face Down"
            (yAxis >= 5.0f || yAxis <= -5.0f) && isObjectClose && isEnvironmentDark -> "Concealed (Pocket)"
            isObjectClose && isEnvironmentDark                                -> "Covered / In Bag"
            else                                                              -> "Visible"
        }

        tvPocketStatus.text = currentPocketState
        tvPocketStatus.setTextColor(Color.parseColor(when {
            currentPocketState.contains("Concealed") -> "#8B5CF6"
            currentPocketState.contains("Face Down") -> "#F59E0B"
            currentPocketState.contains("Covered")   -> "#3B82F6"
            else                                      -> "#10B981"
        }))

        if (currentPocketState != lastLoggedPocketState && lastLoggedPocketState.isNotEmpty()) {
            lastLoggedPocketState = currentPocketState
            WatchManager.logAndNotify(requireContext(), "Environment Update", "Device spatial state shifted to: $currentPocketState", 0)
        } else if (lastLoggedPocketState.isEmpty()) {
            lastLoggedPocketState = currentPocketState
        }
    }

    private fun startFirebaseTelemetrySync() {
        val userId = auth.currentUser?.uid ?: return

        telemetrySyncJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val telemetryData = hashMapOf<String, Any>(
                        "pocketMode" to currentPocketState,
                        "telemetryUpdated" to com.google.firebase.Timestamp.now()
                    )
                    db.collection("Users").document(userId).set(telemetryData, SetOptions.merge())

                    if (WatchManager.isConnected.value == true) {
                        val elapsed = System.currentTimeMillis() - sessionStartTime
                        val hours   = TimeUnit.MILLISECONDS.toHours(elapsed)
                        val mins    = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
                        val timeStr = String.format(Locale.US, "%02dh %02dm", hours, mins)

                        withContext(Dispatchers.Main) { tvSessionTimer.text = timeStr }

                        val rssi    = rssiHistory.lastOrNull()?.toInt() ?: 0
                        val peakStr = "${historicPeakDistance.roundToInt()} m"
                        val accelStr = String.format(Locale.US, "X:%.2f Y:%.2f Z:%.2f", currentAccel[0], currentAccel[1], currentAccel[2])
                        val gyroStr  = String.format(Locale.US, "X:%.2f Y:%.2f Z:%.2f", currentGyro[0],  currentGyro[1],  currentGyro[2])

                        WatchManager.sendProximityTelemetry(rssi, peakStr, timeStr)
                        delay(250)
                        WatchManager.sendSensorTelemetry(accelStr, gyroStr, currentPocketState)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(3000)
            }
        }
    }
}

class RssiChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var dataPoints: List<Float> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setData(newData: List<Float>) {
        dataPoints = newData.toList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        val xStep = w / 39f
        val minRssi = -100f
        val maxRssi = -40f

        val path = Path()
        val fillPath = Path()

        val startY = h - ((dataPoints[0].coerceIn(minRssi, maxRssi) - minRssi) / (maxRssi - minRssi) * h)
        var prevX = 0f
        var prevY = startY

        path.moveTo(prevX, prevY)
        fillPath.moveTo(0f, h)
        fillPath.lineTo(prevX, prevY)

        for (i in 1 until dataPoints.size) {
            val x = i * xStep
            val y = h - ((dataPoints[i].coerceIn(minRssi, maxRssi) - minRssi) / (maxRssi - minRssi) * h)
            path.cubicTo(prevX + (x - prevX) / 2, prevY, prevX + (x - prevX) / 2, y, x, y)
            fillPath.cubicTo(prevX + (x - prevX) / 2, prevY, prevX + (x - prevX) / 2, y, x, y)
            prevX = x; prevY = y
        }

        fillPath.lineTo(prevX, h)
        fillPath.close()
        fillPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#6610B981"), Color.TRANSPARENT, Shader.TileMode.CLAMP)

        val latest = dataPoints.last()
        linePaint.color = Color.parseColor(when {
            latest < -85 -> "#EF4444"
            latest < -70 -> "#F59E0B"
            else         -> "#10B981"
        })

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}

