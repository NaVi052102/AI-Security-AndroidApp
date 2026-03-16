package com.example.aisecurity.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity // <-- THE FIX IS HERE
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.aisecurity.ai.SecurityEnforcer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// <-- AND HERE
class LockdownActivity : ComponentActivity() {

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No setContentView! This screen is a completely invisible ghost.

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startSilentCamera()
        } else {
            Log.e("Lockdown", "Camera permission missing!")
            lockPhoneAndExit() // If no camera, just lock the phone immediately
        }
    }

    private fun startSilentCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)

                // Snap the photo silently after a tiny 300ms delay to let the lens open
                Handler(Looper.getMainLooper()).postDelayed({
                    takePhoto()
                }, 300)

            } catch (exc: Exception) {
                Log.e("Lockdown", "Camera failed to start", exc)
                lockPhoneAndExit()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val photoFile = File(
            filesDir,
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis()) + ".jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("Lockdown", "Photo failed: ${exc.message}")
                    lockPhoneAndExit() // Lock it anyway if it fails!
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("Lockdown", "📸 STEALTH INTRUDER CAPTURED! Saved at: ${photoFile.absolutePath}")
                    lockPhoneAndExit() // The split second it saves, lock the phone!
                }
            }
        )
    }

    private fun lockPhoneAndExit() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, SecurityEnforcer.AdminReceiver::class.java)

        if (dpm.isAdminActive(adminComponent)) {
            Log.d("Lockdown", "Executing immediate screen lock...")
            dpm.lockNow()
        } else {
            Log.e("Lockdown", "Cannot lock screen: Admin permission missing!")
        }

        // Destroy this invisible screen so it doesn't stay running
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}