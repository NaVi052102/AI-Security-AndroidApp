package com.example.aisecurity.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage

class HiddenCameraActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            return
        }

        // Handle both your old intent extra and the new one from the RemoteCommandService
        val cameraType = intent.getStringExtra("CAMERA_TYPE") ?: intent.getStringExtra("CAMERA_FACING") ?: "Back"
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

                            // Upload the saved Gallery URI to Firebase
                            uploadToFirebase(output.savedUri)
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

    private fun uploadToFirebase(fileUri: Uri?) {
        if (fileUri == null) {
            finish()
            return
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            finish()
            return
        }

        // Upload to a dedicated secret_snaps folder
        val storageRef = FirebaseStorage.getInstance().reference.child("secret_snaps/$uid.jpg")

        storageRef.putFile(fileUri)
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { downloadUri ->

                    // 🚨 THE FIX IS HERE: We save to "latestSecretSnap" instead of "photoUri"
                    // This protects the original profile picture!
                    FirebaseFirestore.getInstance().collection("Users").document(uid).set(
                        hashMapOf("latestSecretSnap" to downloadUri.toString()),
                        SetOptions.merge()
                    ).addOnCompleteListener {
                        Log.d("SENTRY_CAM", "Upload complete. Destroying ghost activity.")
                        finish() // Mission Accomplished. Close the invisible activity.
                    }
                }
            }
            .addOnFailureListener {
                Log.e("SENTRY_CAM", "Firebase Upload Failed")
                finish()
            }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val cameraType = intent.getStringExtra("CAMERA_TYPE") ?: intent.getStringExtra("CAMERA_FACING") ?: "Back"
            takeHiddenPhoto(cameraType)
        } else {
            Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}