package com.example.aisecurity.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.ui.main.LoginActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class AccountSettingsFragment : Fragment() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private var currentFirstName = ""
    private var currentLastName = ""
    private var currentMI = ""
    private var currentPhotoUri = ""

    // Main UI Avatar
    private lateinit var mainAvatarImage: ImageView
    private lateinit var mainAvatarInitials: TextView

    // Modal Elements
    private var dialogAvatarImage: ImageView? = null
    private var dialogAvatarInitials: TextView? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                requireActivity().contentResolver.takePersistableUriPermission(it, takeFlags)
                currentPhotoUri = it.toString()
                dialogAvatarImage?.setImageURI(it)
                dialogAvatarImage?.visibility = View.VISIBLE
                dialogAvatarInitials?.visibility = View.GONE
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_account_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        mainAvatarInitials = view.findViewById(R.id.tvAccountInitials)
        mainAvatarImage = view.findViewById(R.id.imgAccountAvatar)

        val tvNameHeader = view.findViewById<TextView>(R.id.tvAccountNameHeader)
        val tvFullName = view.findViewById<TextView>(R.id.tvAccountFullName)
        val tvEmail = view.findViewById<TextView>(R.id.tvAccountEmail)
        val tvPhone = view.findViewById<TextView>(R.id.tvAccountPhone)

        val btnEditProfile = view.findViewById<Button>(R.id.btnEditProfile)
        val btnChangePhone = view.findViewById<TextView>(R.id.btnChangePhone)
        val btnLogout = view.findViewById<Button>(R.id.btnLogout)

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        applyAvatarBackground(mainAvatarInitials, isNightMode)
        applyPrimaryButton(btnEditProfile, isNightMode)
        applyDestructiveGhostButton(btnLogout, isNightMode)

        loadUserData(tvNameHeader, tvFullName, tvEmail, tvPhone)

        btnEditProfile.setOnClickListener {
            showEditProfileDialog(tvNameHeader, tvFullName)
        }

        // ==========================================
        // SECURE PHONE UPDATE FLOW
        // ==========================================
        btnChangePhone.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Secure Number Verification")
                .setMessage("Changing your master phone number requires an SMS One-Time Password (OTP) to prove ownership. Do you wish to proceed to the verification flow?")
                .setPositiveButton("Proceed") { _, _ ->
                    // INDUSTRY STANDARD: You should route the user to a dedicated Activity here
                    // where they enter the new number, receive a Firebase SMS, and verify it.
                    // For now, we simulate the hook:
                    Toast.makeText(requireContext(), "Routing to Secure OTP Verification Activity...", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnLogout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Secure Logout")
                .setMessage("Are you sure you want to log out? AI continuous protection will be suspended.")
                .setPositiveButton("Log Out") { _, _ ->
                    val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("is_logged_in", false).apply()
                    auth.signOut()
                    val intent = Intent(requireActivity(), LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun loadUserData(header: TextView, full: TextView, email: TextView, phone: TextView) {
        val userId = auth.currentUser?.uid ?: return

        db.collection("Users").document(userId).get().addOnSuccessListener { document ->
            if (document != null && document.exists()) {
                currentFirstName = document.getString("firstName") ?: ""
                currentLastName = document.getString("lastName") ?: ""
                currentMI = document.getString("middleInitial") ?: ""
                currentPhotoUri = document.getString("photoUri") ?: ""

                if (currentFirstName.isEmpty() && currentLastName.isEmpty()) {
                    val legacyName = document.getString("fullName") ?: "Unknown User"
                    val parts = legacyName.split(" ")
                    currentFirstName = parts.firstOrNull() ?: ""
                    currentLastName = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""
                }

                val displayMI = if (currentMI.isNotEmpty()) "$currentMI. " else ""
                val constructedName = "$currentFirstName $displayMI$currentLastName".trim()

                header.text = constructedName
                full.text = constructedName
                email.text = document.getString("email") ?: auth.currentUser?.email ?: "--"
                phone.text = document.getString("phoneNumber") ?: "--"

                updateMainAvatarUI()
            }
        }
    }

    private fun updateMainAvatarUI() {
        if (currentPhotoUri.isNotEmpty()) {
            try {
                mainAvatarImage.setImageURI(Uri.parse(currentPhotoUri))
                mainAvatarImage.visibility = View.VISIBLE
                mainAvatarInitials.visibility = View.GONE
            } catch (e: Exception) {
                mainAvatarImage.visibility = View.GONE
                mainAvatarInitials.visibility = View.VISIBLE
                mainAvatarInitials.text = getInitials(currentFirstName, currentLastName)
            }
        } else {
            mainAvatarImage.visibility = View.GONE
            mainAvatarInitials.visibility = View.VISIBLE
            mainAvatarInitials.text = getInitials(currentFirstName, currentLastName)
        }
    }

    private fun showEditProfileDialog(tvNameHeader: TextView, tvFullName: TextView) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_profile, null)
        val dialogRoot = dialogView.findViewById<LinearLayout>(R.id.dialogRoot)
        val etFirstName = dialogView.findViewById<EditText>(R.id.etFirstName)
        val etLastName = dialogView.findViewById<EditText>(R.id.etLastName)
        val etMI = dialogView.findViewById<EditText>(R.id.etMiddleInitial)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        dialogAvatarImage = dialogView.findViewById(R.id.imgDialogAvatar)
        dialogAvatarInitials = dialogView.findViewById(R.id.tvDialogInitials)
        val cardDialogAvatar = dialogView.findViewById<CardView>(R.id.cardDialogAvatar)

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val dialogBg = GradientDrawable().apply {
            cornerRadius = 60f
            if (isNightMode) {
                setColor(Color.parseColor("#FA0F172A"))
                setStroke(2, Color.parseColor("#334155"))
            } else {
                setColor(Color.parseColor("#FAFFFFFF"))
                setStroke(2, Color.parseColor("#CBD5E1"))
            }
        }
        dialogRoot.background = dialogBg

        applyPrimaryButton(btnSave, isNightMode)
        applyGhostButton(btnCancel, isNightMode)
        applyAvatarBackground(dialogAvatarInitials!!, isNightMode)

        etFirstName.setText(currentFirstName)
        etLastName.setText(currentLastName)
        etMI.setText(currentMI)

        if (currentPhotoUri.isNotEmpty()) {
            dialogAvatarImage?.setImageURI(Uri.parse(currentPhotoUri))
            dialogAvatarImage?.visibility = View.VISIBLE
            dialogAvatarInitials?.visibility = View.GONE
        } else {
            dialogAvatarInitials?.text = getInitials(currentFirstName, currentLastName)
        }

        cardDialogAvatar.setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val fn = etFirstName.text.toString().trim()
            val ln = etLastName.text.toString().trim()
            val mi = etMI.text.toString().trim().uppercase()

            if (fn.isEmpty() || ln.isEmpty()) {
                Toast.makeText(requireContext(), "First and Last name are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSave.isEnabled = false
            btnSave.text = "SAVING..."

            val userId = auth.currentUser?.uid ?: return@setOnClickListener
            val displayMI = if (mi.isNotEmpty()) "$mi. " else ""
            val fullConst = "$fn $displayMI$ln".trim()

            val updates = hashMapOf(
                "firstName" to fn,
                "lastName" to ln,
                "middleInitial" to mi,
                "fullName" to fullConst,
                "photoUri" to currentPhotoUri
            )

            db.collection("Users").document(userId).set(updates, SetOptions.merge())
                .addOnSuccessListener {
                    currentFirstName = fn
                    currentLastName = ln
                    currentMI = mi

                    tvNameHeader.text = fullConst
                    tvFullName.text = fullConst
                    updateMainAvatarUI() // Update the background page!

                    Toast.makeText(requireContext(), "Profile Updated!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .addOnFailureListener {
                    btnSave.isEnabled = true
                    btnSave.text = "SAVE"
                    Toast.makeText(requireContext(), "Network Error. Try again.", Toast.LENGTH_SHORT).show()
                }
        }
        dialog.show()
    }

    private fun getInitials(first: String, last: String): String {
        val f = first.firstOrNull()?.uppercase() ?: ""
        val l = last.firstOrNull()?.uppercase() ?: ""
        return "$f$l".take(2)
    }

    private fun applyAvatarBackground(textView: TextView, isNightMode: Boolean) {
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        if (isNightMode) {
            bg.setColor(Color.parseColor("#1E293B"))
            textView.setTextColor(Color.parseColor("#94A3B8"))
        } else {
            bg.setColor(Color.parseColor("#E0E7FF"))
            textView.setTextColor(Color.parseColor("#1E3A8A"))
        }
        textView.background = bg
    }

    private fun applyPrimaryButton(button: Button, isNightMode: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 30f // Slightly squared edges feel more "Security Enterprise" than full pills
        }
        if (isNightMode) {
            bg.setColor(Color.parseColor("#3B82F6")) // Vivid vibrant blue
            button.setTextColor(Color.parseColor("#FFFFFF"))
        } else {
            bg.setColor(Color.parseColor("#2563EB")) // Deep bold blue
            button.setTextColor(Color.parseColor("#FFFFFF")) // White text on solid background
        }
        button.background = bg
    }

    private fun applyGhostButton(button: Button, isNightMode: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }
        if (isNightMode) {
            bg.colors = intArrayOf(Color.parseColor("#0F172A"), Color.parseColor("#020617"))
            bg.setStroke(3, Color.parseColor("#334155"))
            button.setTextColor(Color.parseColor("#94A3B8"))
        } else {
            bg.colors = intArrayOf(Color.parseColor("#F8FAFC"), Color.parseColor("#E2E8F0"))
            bg.setStroke(3, Color.parseColor("#CBD5E1"))
            button.setTextColor(Color.parseColor("#64748B"))
        }
        button.background = bg
    }

    private fun applyDestructiveGhostButton(button: Button, isNightMode: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 30f
        }
        if (isNightMode) {
            bg.setColor(Color.parseColor("#1AEF4444")) // 10% Opacity Red
            button.setTextColor(Color.parseColor("#F87171")) // Bright Red Text
        } else {
            bg.setColor(Color.parseColor("#1AEF4444")) // 10% Opacity Red
            button.setTextColor(Color.parseColor("#DC2626")) // Dark Red Text
        }
        button.background = bg
    }
}