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

class ProximityFragment : Fragment(), SensorEventListener {

    private val rssiHistory = mutableListOf<Float>()

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

    private var currentAccel = floatArrayOf(0f, 0f, 0f)
    private var currentGyro = floatArrayOf(0f, 0f, 0f)
    private var isObjectClose = false
    private var isEnvironmentDark = false
    private var currentPocketState = "Out of Pocket"

    private var sessionStartTime = 0L
    private var historicPeakDistance = 0.0

    private var telemetrySyncJob: Job? = null
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_proximity, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        val tvScanStatus = view.findViewById<TextView>(R.id.tvScanStatus)
        val tvDistance = view.findViewById<TextView>(R.id.tvDistance)
        val tvHeartRate = view.findViewById<TextView>(R.id.tvHeartRate)
        val tvSecurityStatus = view.findViewById<TextView>(R.id.tvSecurityStatus)
        val tvTouchStatus = view.findViewById<TextView>(R.id.tvTouchStatus)

        val rssiGraph = view.findViewById<RssiChartView>(R.id.rssiGraph)
        val tvCurrentRssiValue = view.findViewById<TextView>(R.id.tvCurrentRssiValue)

        tvAccelData = view.findViewById(R.id.tvAccelData)
        tvGyroData = view.findViewById(R.id.tvGyroData)
        tvPocketStatus = view.findViewById(R.id.tvPocketStatus)

        tvPeakDistance = view.findViewById(R.id.tvPeakDistance)
        tvSessionTimer = view.findViewById(R.id.tvSessionTimer)

        if (accelSensor == null) tvAccelData.text = "No Hardware"
        if (gyroSensor == null) tvGyroData.text = "No Hardware"

        WatchManager.liveStatus.observe(viewLifecycleOwner) { status ->
            if (status.contains("Disconnected", ignoreCase = true) || status.contains("Lost", ignoreCase = true)) {
                tvScanStatus.text = "Disconnected"
                tvScanStatus.setTextColor(Color.parseColor("#EF4444"))

                tvDistance.text = "-- m"
                tvDistance.setTextColor(Color.parseColor("#94A3B8"))
                tvHeartRate.text = "-- bpm"
                tvSecurityStatus.text = "DISCONNECTED"
                tvSecurityStatus.setTextColor(Color.parseColor("#EF4444"))
            }
            else if (status.contains("Secure Link Established", ignoreCase = true) ||
                status.contains("Connected", ignoreCase = true)) {
                tvScanStatus.text = "Connected"
                tvScanStatus.setTextColor(Color.parseColor("#10B981"))
            }
            else {
                tvScanStatus.text = status
                tvScanStatus.setTextColor(Color.parseColor("#94A3B8"))
            }
        }

        WatchManager.liveDistance.observe(viewLifecycleOwner) { distanceStr ->
            tvDistance.text = "$distanceStr m"
            try {
                val distance = distanceStr.toFloat()

                if (distance > historicPeakDistance && distance < 40.0) {
                    historicPeakDistance = distance.toDouble()
                    tvPeakDistance.text = String.format(Locale.US, "%.1f m", historicPeakDistance)
                }

                when {
                    distance <= 3.0f -> tvDistance.setTextColor(Color.parseColor("#10B981"))
                    distance <= 8.0f -> tvDistance.setTextColor(Color.parseColor("#F59E0B"))
                    else -> tvDistance.setTextColor(Color.parseColor("#EF4444"))
                }
            } catch (e: NumberFormatException) {
                tvDistance.setTextColor(Color.parseColor("#10B981"))
            }
        }

        WatchManager.liveRSSI.observe(viewLifecycleOwner) { rssi ->
            val signal = rssi.toFloat()
            tvCurrentRssiValue.text = "${rssi} dBm"

            rssiHistory.add(signal)
            if (rssiHistory.size > 40) rssiHistory.removeAt(0)

            rssiGraph.setData(rssiHistory)

            val color = if (signal < -85) "#EF4444" else if (signal < -70) "#F59E0B" else "#10B981"
            tvCurrentRssiValue.setTextColor(Color.parseColor(color))
        }

        WatchManager.liveHeartRate.observe(viewLifecycleOwner) { hr ->
            tvHeartRate.text = "$hr bpm"
        }

        WatchManager.wristStatus.observe(viewLifecycleOwner) { wrist ->
            val wristStr = wrist.toString()
            tvSecurityStatus.text = wristStr.uppercase()
            if (wristStr.contains("ON WRIST", ignoreCase = true)) {
                tvSecurityStatus.setTextColor(Color.parseColor("#10B981"))
            } else {
                tvSecurityStatus.setTextColor(Color.parseColor("#EF4444"))
            }
        }

        WatchManager.touchStatus.observe(viewLifecycleOwner) { touch ->
            tvTouchStatus.text = touch.toString()
        }
    }

    override fun onResume() {
        super.onResume()
        if (sessionStartTime == 0L) sessionStartTime = System.currentTimeMillis()

        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        startFirebaseTelemetrySync()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        telemetrySyncJob?.cancel()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                currentAccel = event.values.clone()
                tvAccelData.text = String.format(Locale.US, "X: %.2f\nY: %.2f\nZ: %.2f", currentAccel[0], currentAccel[1], currentAccel[2])
            }
            Sensor.TYPE_GYROSCOPE -> {
                currentGyro = event.values.clone()
                tvGyroData.text = String.format(Locale.US, "X: %.2f\nY: %.2f\nZ: %.2f", currentGyro[0], currentGyro[1], currentGyro[2])
            }
            Sensor.TYPE_PROXIMITY -> {
                isObjectClose = event.values[0] < event.sensor.maximumRange
                evaluatePocketMode()
            }
            Sensor.TYPE_LIGHT -> {
                isEnvironmentDark = event.values[0] < 5.0f
                evaluatePocketMode()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun evaluatePocketMode() {
        currentPocketState = if (isObjectClose && isEnvironmentDark) {
            "Concealed (Pocket)"
        } else if (isObjectClose) {
            "Face Down"
        } else {
            "Open / Visible"
        }

        tvPocketStatus.text = currentPocketState
        if (currentPocketState.contains("Concealed")) {
            tvPocketStatus.setTextColor(Color.parseColor("#8B5CF6"))
        } else if (currentPocketState.contains("Face Down")) {
            tvPocketStatus.setTextColor(Color.parseColor("#F59E0B"))
        } else {
            tvPocketStatus.setTextColor(Color.parseColor("#10B981"))
        }
    }

    private fun startFirebaseTelemetrySync() {
        val userId = auth.currentUser?.uid ?: return

        telemetrySyncJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val telemetryData = hashMapOf(
                        "accelerometer" to mapOf("x" to currentAccel[0], "y" to currentAccel[1], "z" to currentAccel[2]),
                        "gyroscope" to mapOf("x" to currentGyro[0], "y" to currentGyro[1], "z" to currentGyro[2]),
                        "pocketMode" to currentPocketState,
                        "telemetryUpdated" to com.google.firebase.Timestamp.now()
                    )
                    db.collection("Users").document(userId).set(telemetryData, SetOptions.merge())

                    if (WatchManager.isConnected.value == true) {

                        val elapsed = System.currentTimeMillis() - sessionStartTime
                        val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
                        val mins = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
                        val timeStr = String.format(Locale.US, "%02dh %02dm", hours, mins)

                        withContext(Dispatchers.Main) { tvSessionTimer.text = timeStr }

                        val rssi = rssiHistory.lastOrNull()?.toInt() ?: 0
                        val peakStr = String.format(Locale.US, "%.1f m", historicPeakDistance)

                        // 🚨 CLEANED UP SPACING SO THE WATCH PARSES IT EASILY
                        val accelStr = String.format(Locale.US, "X:%.2f Y:%.2f Z:%.2f", currentAccel[0], currentAccel[1], currentAccel[2])
                        val gyroStr = String.format(Locale.US, "X:%.2f Y:%.2f Z:%.2f", currentGyro[0], currentGyro[1], currentGyro[2])

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
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#10B981"); strokeWidth = 5f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setData(newData: List<Float>) { dataPoints = newData.toList(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        val xStep = w / 39f

        val minRssi = -100f
        val maxRssi = -40f

        val path = Path(); val fillPath = Path()

        var startY = h - ((dataPoints[0].coerceIn(minRssi, maxRssi) - minRssi) / (maxRssi - minRssi) * h)
        var prevX = 0f; var prevY = startY

        path.moveTo(prevX, prevY); fillPath.moveTo(0f, h); fillPath.lineTo(prevX, prevY)

        for (i in 1 until dataPoints.size) {
            val x = i * xStep
            val normalized = dataPoints[i].coerceIn(minRssi, maxRssi)
            val y = h - ((normalized - minRssi) / (maxRssi - minRssi) * h)

            path.cubicTo(prevX + (x - prevX)/2, prevY, prevX + (x - prevX)/2, y, x, y)
            fillPath.cubicTo(prevX + (x - prevX)/2, prevY, prevX + (x - prevX)/2, y, x, y)
            prevX = x; prevY = y
        }

        fillPath.lineTo(prevX, h); fillPath.close()
        fillPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#6610B981"), Color.TRANSPARENT, Shader.TileMode.CLAMP)

        val latest = dataPoints.last()
        linePaint.color = if (latest < -85) Color.parseColor("#EF4444") else if (latest < -70) Color.parseColor("#F59E0B") else Color.parseColor("#10B981")

        canvas.drawPath(fillPath, fillPaint); canvas.drawPath(path, linePaint)
    }
}

