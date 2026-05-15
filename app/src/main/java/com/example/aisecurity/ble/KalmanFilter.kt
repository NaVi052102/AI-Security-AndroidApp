package com.example.aisecurity.ble

import kotlin.math.abs

class KalmanFilter(
    var processNoise: Double = 0.008,
    var measurementNoise: Double = 1.5
) {
    private var estimatedError: Double = 1.0
    private var lastEstimate: Double = 0.0
    private var isInitialized = false

    // Adaptive noise tracking
    private val recentMeasurements = ArrayDeque<Double>()
    private val WINDOW_SIZE = 8

    fun update(measurement: Double): Double {
        if (!isInitialized) {
            lastEstimate = measurement
            isInitialized = true
            recentMeasurements.addLast(measurement)
            return measurement
        }

        // Outlier rejection: skip readings that jump more than 8m in one step
        if (isInitialized && abs(measurement - lastEstimate) > 8.0) {
            return lastEstimate
        }

        // Track recent measurements for adaptive noise
        recentMeasurements.addLast(measurement)
        if (recentMeasurements.size > WINDOW_SIZE) recentMeasurements.removeFirst()

        // Adapt measurement noise to current signal variance
        if (recentMeasurements.size >= 4) {
            val mean = recentMeasurements.average()
            val variance = recentMeasurements.map { (it - mean) * (it - mean) }.average()
            measurementNoise = (variance * 0.6).coerceIn(0.3, 6.0)
        }

        // Prediction step
        estimatedError += processNoise

        // Correction step
        val kalmanGain = estimatedError / (estimatedError + measurementNoise)
        lastEstimate += kalmanGain * (measurement - lastEstimate)
        estimatedError *= (1.0 - kalmanGain)

        return lastEstimate
    }

    fun reset() {
        isInitialized = false
        estimatedError = 1.0
        lastEstimate = 0.0
        recentMeasurements.clear()
    }
}