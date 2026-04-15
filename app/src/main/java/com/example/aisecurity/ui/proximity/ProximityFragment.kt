package com.example.aisecurity.ui.proximity

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.ble.WatchManager

class ProximityFragment : Fragment() {

    private val rssiHistory = mutableListOf<Float>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_proximity, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvScanStatus = view.findViewById<TextView>(R.id.tvScanStatus)
        val tvDistance = view.findViewById<TextView>(R.id.tvDistance)
        val tvHeartRate = view.findViewById<TextView>(R.id.tvHeartRate)
        val tvSecurityStatus = view.findViewById<TextView>(R.id.tvSecurityStatus)
        val tvTouchStatus = view.findViewById<TextView>(R.id.tvTouchStatus)

        // 🚀 NEW WIDGETS
        val rssiGraph = view.findViewById<RssiChartView>(R.id.rssiGraph)
        val tvCurrentRssiValue = view.findViewById<TextView>(R.id.tvCurrentRssiValue)

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
                tvScanStatus.text = "Connected & Secured"
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
                when {
                    distance <= 3.0f -> {
                        tvDistance.setTextColor(Color.parseColor("#10B981")) // Green
                    }
                    distance <= 8.0f -> {
                        tvDistance.setTextColor(Color.parseColor("#F59E0B")) // Orange
                    }
                    else -> {
                        tvDistance.setTextColor(Color.parseColor("#EF4444")) // Red
                    }
                }
            } catch (e: NumberFormatException) {
                tvDistance.setTextColor(Color.parseColor("#10B981"))
            }
        }

        // 🚀 NEW: REALTIME RSSI GRAPH LOGIC
        WatchManager.liveRSSI.observe(viewLifecycleOwner) { rssi ->
            val signal = rssi.toFloat()
            tvCurrentRssiValue.text = "${rssi} dBm"

            // Add to history and keep only last 40 points
            rssiHistory.add(signal)
            if (rssiHistory.size > 40) rssiHistory.removeAt(0)

            // Update Graph
            rssiGraph.setData(rssiHistory)

            // Color code text based on strength
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
}

// =========================================================
// 🚀 SENIOR DESIGN: REALTIME RSSI LINE GRAPH
// =========================================================
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
        val xStep = w / 39f // Always scale for 40 points

        // RSSI Range is roughly -100 (Worst) to -40 (Best)
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

            // Smooth Bezier Curve
            path.cubicTo(prevX + (x - prevX)/2, prevY, prevX + (x - prevX)/2, y, x, y)
            fillPath.cubicTo(prevX + (x - prevX)/2, prevY, prevX + (x - prevX)/2, y, x, y)
            prevX = x; prevY = y
        }

        fillPath.lineTo(prevX, h); fillPath.close()
        fillPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#6610B981"), Color.TRANSPARENT, Shader.TileMode.CLAMP)

        // Dynamically change line color based on latest RSSI
        val latest = dataPoints.last()
        linePaint.color = if (latest < -85) Color.parseColor("#EF4444") else if (latest < -70) Color.parseColor("#F59E0B") else Color.parseColor("#10B981")

        canvas.drawPath(fillPath, fillPaint); canvas.drawPath(path, linePaint)
    }
}