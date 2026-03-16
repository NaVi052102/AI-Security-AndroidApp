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

    // Physical file where the AI stores its neural weights
    private val checkpointFile = File(context.filesDir, "brain_checkpoint.ckpt")

    init {
        setupInterpreter()
    }

    private fun setupInterpreter() {
        try {
            val options = Interpreter.Options()
            // Loading the pure-math dynamic model generated in Colab
            interpreter = Interpreter(loadModelFile(), options)

            // Only try to restore if we have a valid memory file
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
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    // ==========================================
    // SIGNATURE 1: INFER (Verification Mode)
    // ==========================================
    fun getError(features: FloatArray): Float {
        try {
            val ai = interpreter ?: return 1.0f
            val inputMap = mapOf("x" to arrayOf(features))

            // Allocate 5 floats (4 bytes each) for the reconstruction output
            val outputBuffer = ByteBuffer.allocateDirect(5 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val outputMap = mapOf("output" to outputBuffer)

            ai.runSignature(inputMap, outputMap, "infer")

            val reconstruction = FloatArray(5)
            outputBuffer.rewind()
            outputBuffer.get(reconstruction)

            // Manual calculation of Mean Absolute Error (MAE)
            var error = 0f
            for (i in 0..4) {
                error += abs(features[i] - reconstruction[i])
            }
            return error / 5f
        } catch (e: Exception) {
            LiveLogger.log("INFER ERROR: ${e.message}")
            return 1.0f
        }
    }

    // ==========================================
    // SIGNATURE 2: TRAIN (Learning Mode)
    // ==========================================
    fun trainAI(features: FloatArray): Float {
        try {
            val ai = interpreter ?: return 1.0f

            // Create the shell file if it doesn't exist yet
            if (!checkpointFile.exists()) {
                checkpointFile.createNewFile()
            }

            val inputMap = mapOf("x" to arrayOf(features))
            val lossBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val outputMap = mapOf("loss" to lossBuffer)

            ai.runSignature(inputMap, outputMap, "train")

            lossBuffer.rewind()
            val loss = lossBuffer.get()

            // Immediately commit the new weights to disk
            saveWeights()
            return loss
        } catch (e: Exception) {
            // This handles the initial nullptr while the AI is still "seeding" its first variables
            if (e.message?.contains("variable != nullptr") == true) {
                return 1.0f
            }
            LiveLogger.log("TRAIN ERROR: ${e.message}")
            return 1.0f
        }
    }

    // ==========================================
    // SIGNATURE 3 & 4: SAVE and RESTORE
    // ==========================================
    private fun saveWeights() {
        try {
            val ai = interpreter ?: return
            val inputMap = mapOf("checkpoint_path" to checkpointFile.absolutePath)
            // Signature requires an output map even if empty
            val outputMap = mutableMapOf<String, Any>()
            ai.runSignature(inputMap, outputMap, "save")
        } catch (e: Exception) {
            // Expected catch for TFLite signature return quirks
        }
    }

    private fun restoreWeights() {
        try {
            val ai = interpreter ?: return
            val inputMap = mapOf("checkpoint_path" to checkpointFile.absolutePath)
            val outputMap = mutableMapOf<String, Any>()
            ai.runSignature(inputMap, outputMap, "restore")
            LiveLogger.log("🧠 Brain memories restored.")
        } catch (e: Exception) {
            // Logged only if the restoration physically fails
            LiveLogger.log("RESTORE NOTE: Syncing brain structure...")
        }
    }

    // ==========================================
    // UTILITIES
    // ==========================================
    fun wipeMemory() {
        if (checkpointFile.exists()) {
            checkpointFile.delete()
        }
        setupInterpreter()
    }

    companion object {
        fun calculateThreshold(errors: List<Float>): Float {
            if (errors.isEmpty()) return 1.0f
            val mean = errors.average()
            val stdDev = sqrt(errors.map { (it - mean).pow(2) }.average())

            // ========================================================
            // THESIS TESTING MODE: SENSITIVITY MULTIPLIER
            // ========================================================
            // 3.0 = Production Mode (Very forgiving, requires 1000+ swipes)
            // 1.5 = Moderate (Good balance)
            // 0.5 = Hyper-Sensitive (Will trigger lockdown very easily)

            val sensitivity = 0.5

            return (mean + (sensitivity * stdDev)).toFloat()
        }
    }
}