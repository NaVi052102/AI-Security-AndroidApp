package com.example.aisecurity.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class HiddenCameraActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            return
        }

        val cameraType = intent.getStringExtra("CAMERA_TYPE") ?: "Back"
        takeHiddenPhoto(cameraType)
    }

    private fun takeHiddenPhoto(cameraType: String) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                // Select Front or Back Lens
                val lensFacing = if (cameraType.equals("Front", ignoreCase = true)) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)

                // Save direct to Gallery
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Sentry_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                }

                val outputOptions = ImageCapture.OutputFileOptions.Builder(
                    contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ).build()

                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Toast.makeText(this@HiddenCameraActivity, "📸 Sentry: $cameraType Photo Saved!", Toast.LENGTH_SHORT).show()
                            finish() // Kills the invisible activity instantly
                        }

                        override fun onError(exc: ImageCaptureException) {
                            Log.e("SENTRY_CAM", "Capture failed: ${exc.message}", exc)
                            finish()
                        }
                    }
                )

            } catch (exc: Exception) {
                Log.e("SENTRY_CAM", "Camera init failed.", exc)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            takeHiddenPhoto(intent.getStringExtra("CAMERA_TYPE") ?: "Back")
        } else {
            Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}