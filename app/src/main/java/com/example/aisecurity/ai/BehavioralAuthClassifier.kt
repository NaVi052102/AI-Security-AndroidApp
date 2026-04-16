package com.example.aisecurity.ai

import android.content.Context
import com.example.aisecurity.ui.LiveLogger
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class BehavioralAuthClassifier(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val checkpointFile = File(context.filesDir, "brain_checkpoint.ckpt")

    // ─── EMA state lives here so it persists across calls within a session ───
    // On cold start it's loaded from SharedPreferences in TouchDynamicsService
    // before any training call.  We expose a setter so the service can seed it.
    private var emaSeed: Float? = null

    fun seedEma(value: Float) { emaSeed = value }

    init { setupInterpreter() }

    private fun setupInterpreter() {
        try {
            val options = Interpreter.Options()
            interpreter = Interpreter(loadModelFile(), options)
            if (checkpointFile.exists() && checkpointFile.length() > 0) {
                restoreWeights()
            } else {
                LiveLogger.log("🌱 AI initialized: Waiting for first training swipe.")
            }
        } catch (e: Exception) {
            LiveLogger.log("INIT ERROR: ${e.message}")
        }
    }

    private fun loadModelFile(): ByteBuffer {
        val fileDescriptor = context.assets.openFd("dynamic_behavioral_model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    // ─────────────────────────────────────────────────────────────
    // SIGNATURE 1 — INFER  (verification / error scoring)
    // ─────────────────────────────────────────────────────────────
    fun getError(features: FloatArray): Float {
        return try {
            val ai = interpreter ?: return 1.0f
            val inputMap = mapOf("x" to arrayOf(features))
            val outputBuffer = ByteBuffer.allocateDirect(4 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            val outputMap = mapOf("output" to outputBuffer)
            ai.runSignature(inputMap, outputMap, "infer")

            val reconstruction = FloatArray(4)
            outputBuffer.rewind()
            outputBuffer.get(reconstruction)

            var error = 0f
            for (i in 0..3) error += abs(features[i] - reconstruction[i])
            error / 4f
        } catch (e: Exception) {
            LiveLogger.log("INFER ERROR: ${e.message}")
            1.0f
        }
    }

    // ─────────────────────────────────────────────────────────────
    // SIGNATURE 2 — TRAIN
    // Returns the RAW per-sample MSE from TFLite (untouched).
    // The caller (TouchDynamicsService) owns EMA smoothing so it
    // can also persist the EMA across process restarts.
    // ─────────────────────────────────────────────────────────────
    fun trainAI(features: FloatArray): Float {
        return try {
            val ai = interpreter ?: return 1.0f
            if (!checkpointFile.exists()) checkpointFile.createNewFile()

            val inputMap = mapOf("x" to arrayOf(features))
            val lossBuffer = ByteBuffer.allocateDirect(4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            val outputMap = mapOf("loss" to lossBuffer)

            ai.runSignature(inputMap, outputMap, "train")
            lossBuffer.rewind()
            val rawLoss = lossBuffer.get()

            saveWeights()

            // Clamp to [0, 1] — TFLite can occasionally spit out NaN/Inf on the
            // very first call before variables are fully seeded.
            if (rawLoss.isNaN() || rawLoss.isInfinite()) 1.0f else rawLoss.coerceIn(0f, 1f)

        } catch (e: Exception) {
            if (e.message?.contains("variable != nullptr") == true) return 1.0f
            LiveLogger.log("TRAIN ERROR: ${e.message}")
            1.0f
        }
    }

    // ─────────────────────────────────────────────────────────────
    // SIGNATURE 3 & 4 — SAVE / RESTORE
    // ─────────────────────────────────────────────────────────────
    private fun saveWeights() {
        try {
            val ai = interpreter ?: return
            val inputMap = mapOf("checkpoint_path" to checkpointFile.absolutePath)
            ai.runSignature(inputMap, mutableMapOf<String, Any>(), "save")
        } catch (_: Exception) { }
    }

    private fun restoreWeights() {
        try {
            val ai = interpreter ?: return
            val inputMap = mapOf("checkpoint_path" to checkpointFile.absolutePath)
            ai.runSignature(inputMap, mutableMapOf<String, Any>(), "restore")
            LiveLogger.log("🧠 Brain memories restored.")
        } catch (_: Exception) {
            LiveLogger.log("RESTORE NOTE: Syncing brain structure...")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // UTILITIES
    // ─────────────────────────────────────────────────────────────
    fun wipeMemory() {
        if (checkpointFile.exists()) checkpointFile.delete()
        setupInterpreter()
    }

    companion object {
        // alpha = 0.05 → very smooth, slow to react (good for a 24-hour curve)
        // alpha = 0.15 → medium smoothing
        // alpha = 0.30 → responsive but still filters noise
        const val EMA_ALPHA = 0.05f

        /**
         * Apply one EMA step.
         * @param prev   previous EMA value (loaded from prefs on cold start)
         * @param raw    raw per-sample MSE from trainAI()
         */
        fun emaStep(prev: Float, raw: Float): Float =
            EMA_ALPHA * raw + (1f - EMA_ALPHA) * prev

        fun calculateThreshold(errors: List<Float>): Float {
            if (errors.isEmpty()) return 1.0f
            val mean = errors.average()
            val stdDev = sqrt(errors.map { (it - mean).pow(2) }.average())
            val sensitivity = 0.5
            return (mean + (sensitivity * stdDev)).toFloat()
        }
    }
}