package com.example.aisecurity.ai

import android.content.Context
import com.example.aisecurity.ui.LiveLogger
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.pow
import kotlin.math.sqrt

class BehavioralAuthClassifier(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val checkpointFile = File(context.filesDir, "brain_checkpoint.ckpt")

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

    fun getError(features: FloatArray): Float {
        return try {
            val ai = interpreter ?: return 1.0f
            val inputMap = mapOf("x" to arrayOf(features))

            val outputBuffer = ByteBuffer.allocateDirect(6 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            val outputMap = mapOf("output" to outputBuffer)
            ai.runSignature(inputMap, outputMap, "infer")

            val reconstruction = FloatArray(6)
            outputBuffer.rewind()
            outputBuffer.get(reconstruction)

            var mseError = 0f

            // 🚨 MASSIVE UPGRADE: Physical Biometrics are now heavily weighted.
            // Velocity (Index 0) is now 15x. Pressure/Duration (Index 1) is 10x.
            // This forces the AI to instantly catch unfamiliar, erratic, or fast swipes.
            val weights = floatArrayOf(15.0f, 10.0f, 1.0f, 1.0f, 1.0f, 1.0f)
            val totalWeight = 29.0f

            for (i in 0..5) {
                val diff = features[i] - reconstruction[i]
                mseError += (diff * diff) * weights[i]
            }
            mseError / totalWeight
        } catch (e: Exception) {
            LiveLogger.log("INFER ERROR: ${e.message}")
            1.0f
        }
    }

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

            if (rawLoss.isNaN() || rawLoss.isInfinite()) 1.0f else rawLoss.coerceIn(0f, 1f)

        } catch (e: Exception) {
            if (e.message?.contains("variable != nullptr") == true) return 1.0f
            LiveLogger.log("TRAIN ERROR: ${e.message}")
            1.0f
        }
    }

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

    fun wipeMemory() {
        if (checkpointFile.exists()) checkpointFile.delete()
        setupInterpreter()
    }

    companion object {
        const val EMA_ALPHA = 0.002f

        fun emaStep(prev: Float, raw: Float): Float =
            EMA_ALPHA * raw + (1f - EMA_ALPHA) * prev

        fun calculateThreshold(errors: List<Float>): Float {
            if (errors.isEmpty()) return 1.0f
            val mean = errors.average()
            val stdDev = sqrt(errors.map { (it - mean).pow(2) }.average())

            // 🚨 TIGHTENED: Lowered from 1.5 to 1.0.
            // The standard deviation allowance is now razor-thin.
            val sensitivity = 1.0
            return (mean + (sensitivity * stdDev)).toFloat()
        }
    }
}