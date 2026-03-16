package com.example.aisecurity.ble

class KalmanFilter(
    private var processNoise: Double = 0.008,     // How fast the object moves (Q)
    private var measurementNoise: Double = 0.5,   // How noisy the signal is (R)
    private var estimatedError: Double = 1.0,     // Initial error estimate
    private var lastEstimate: Double = 0.0        // Starting value
) {
    fun update(measurement: Double): Double {
        // 1. Prediction Step
        estimatedError += processNoise

        // 2. Measurement Update (Kalman Gain)
        val kalmanGain = estimatedError / (estimatedError + measurementNoise)
        val currentEstimate = lastEstimate + kalmanGain * (measurement - lastEstimate)
        estimatedError *= (1 - kalmanGain)

        lastEstimate = currentEstimate
        return currentEstimate
    }

    fun reset() {
        lastEstimate = 0.0
        estimatedError = 1.0
    }
}