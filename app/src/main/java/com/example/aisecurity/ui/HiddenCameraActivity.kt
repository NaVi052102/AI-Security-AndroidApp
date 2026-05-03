package com.example.aisecurity.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage

class HiddenCameraActivity : AppCompatActivity() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        acquireWakeLock()
        applyScreenOnFlags()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                101
            )
            return
        }

        val cameraType = intent.getStringExtra("CAMERA_TYPE")
            ?: intent.getStringExtra("CAMERA_FACING")
            ?: "Back"
        takeHiddenPhoto(cameraType)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                @Suppress("DEPRECATION")
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        or PowerManager.ACQUIRE_CAUSES_WAKEUP
                        or PowerManager.ON_AFTER_RELEASE,
                "AISecurity::HiddenCamera"
            )
            wakeLock?.acquire(20_000L)
        } catch (e: Exception) {
            Log.e("SENTRY_CAM", "WakeLock acquire failed: ${e.message}")
        }
    }

    private fun applyScreenOnFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
    }

    private fun takeHiddenPhoto(cameraType: String) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val lensFacing = if (cameraType.equals("Front", ignoreCase = true))
                    CameraSelector.LENS_FACING_FRONT
                else
                    CameraSelector.LENS_FACING_BACK

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                // 🚨 MIUI FIX: Create an invisible dummy surface.
                // Redmi/MIUI camera hardware crashes if it tries to take a photo without a Preview running.
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider { request ->
                    val surfaceTexture = SurfaceTexture(10)
                    surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                    val surface = Surface(surfaceTexture)
                    request.provideSurface(surface, ContextCompat.getMainExecutor(this@HiddenCameraActivity)) {
                        surface.release()
                        surfaceTexture.release()
                    }
                }

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.unbindAll()

                // 🚨 Bind BOTH the dummy preview and the image capture to satisfy the Redmi HAL
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)

                val contentValues = ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        "Sentry_${System.currentTimeMillis()}.jpg"
                    )
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
                            Toast.makeText(
                                this@HiddenCameraActivity,
                                "📸 Sentry: $cameraType Photo Saved!",
                                Toast.LENGTH_SHORT
                            ).show()
                            uploadToFirebase(output.savedUri)
                        }

                        override fun onError(exc: ImageCaptureException) {
                            Log.e("SENTRY_CAM", "Capture failed: ${exc.message}", exc)
                            finish()
                        }
                    }
                )

            } catch (exc: Exception) {
                Log.e("SENTRY_CAM", "Camera init failed: ${exc.message}", exc)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun uploadToFirebase(fileUri: Uri?) {
        if (fileUri == null) { finish(); return }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) { finish(); return }

        val storageRef = FirebaseStorage.getInstance().reference
            .child("secret_snaps/$uid.jpg")

        storageRef.putFile(fileUri)
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                    FirebaseFirestore.getInstance()
                        .collection("Users")
                        .document(uid)
                        .set(
                            hashMapOf("latestSecretSnap" to downloadUri.toString()),
                            SetOptions.merge()
                        )
                        .addOnCompleteListener {
                            Log.d("SENTRY_CAM", "Upload complete. Closing.")
                            finish()
                        }
                }
            }
            .addOnFailureListener {
                Log.e("SENTRY_CAM", "Firebase upload failed")
                finish()
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101
            && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            val cameraType = intent.getStringExtra("CAMERA_TYPE")
                ?: intent.getStringExtra("CAMERA_FACING")
                ?: "Back"
            takeHiddenPhoto(cameraType)
        } else {
            Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.e("SENTRY_CAM", "WakeLock release failed: ${e.message}")
        }
    }
}