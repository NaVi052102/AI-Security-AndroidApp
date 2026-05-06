package com.example.aisecurity.ui.map

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream

object RemoteCameraUploader {

    fun uploadAndNotifyMap(capturedBitmap: Bitmap) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // 1. Create a reference in Firebase Storage
        val storageRef = FirebaseStorage.getInstance().reference.child("intruder_captures/$uid.jpg")

        // 2. Compress the image to JPEG (CRITICAL to prevent OOM memory crashes)
        val baos = ByteArrayOutputStream()
        capturedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        val data = baos.toByteArray()

        Log.d("SENTRY_CAMERA", "Uploading ${data.size} bytes to Firebase Storage...")

        // 3. Upload the byte array to Firebase
        storageRef.putBytes(data).addOnSuccessListener {

            // 4. Once uploaded, fetch the public Download URL
            storageRef.downloadUrl.addOnSuccessListener { uri ->
                val downloadUrl = uri.toString()

                // 5. Save the URL to Firestore!
                // The MapFragment will instantly detect this new link and download the photo.
                FirebaseFirestore.getInstance().collection("Users").document(uid)
                    .set(hashMapOf("latestCapturedPhotoUrl" to downloadUrl), SetOptions.merge())

                Log.d("SENTRY_CAMERA", "Upload Success! Map UI will now update.")
            }

        }.addOnFailureListener { e ->
            Log.e("SENTRY_CAMERA", "Upload to Storage Failed: ${e.message}")
        }
    }
}