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
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.TimeUnit

class AccountSettingsFragment : Fragment() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private var currentFirstName = ""
    private var currentLastName = ""
    private var currentMI = ""
    private var currentPhotoUri = ""
    private var currentPhone = ""

    private lateinit var mainAvatarImage: ImageView
    private lateinit var mainAvatarInitials: TextView

    private var dialogAvatarImage: ImageView? = null
    private var dialogAvatarInitials: TextView? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) uploadPhotoToFirebase(uri)
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
        val btnChangeEmail = view.findViewById<TextView>(R.id.btnChangeEmail)
        val btnLogout = view.findViewById<Button>(R.id.btnLogout)

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        applyAvatarBackground(mainAvatarInitials, isNightMode)
        applyPrimaryButton(btnEditProfile, isNightMode)
        applyDestructiveGhostButton(btnLogout, isNightMode)

        loadUserData(tvNameHeader, tvFullName, tvEmail, tvPhone)

        btnEditProfile.setOnClickListener { showEditProfileDialog(tvNameHeader, tvFullName) }

        btnChangePhone.setOnClickListener {
            if (currentPhone.isEmpty() || currentPhone == "--") {
                showSentryToast("No phone number linked. Please edit profile first.", false)
                return@setOnClickListener
            }
            showChangePhoneDialog(tvPhone, isNightMode)
        }

        btnChangeEmail.setOnClickListener {
            showSecureVerificationDialog(
                title = "Email Verification",
                message = "Changing your recovery email address requires us to send a verification link to your current email. Do you wish to proceed?",
                isNightMode = isNightMode
            ) { showSentryToast("Sending verification link...", isLong = true) }
        }

        btnLogout.setOnClickListener {
            showSecureVerificationDialog(
                title = "Secure Logout",
                message = "Are you sure you want to log out? AI continuous protection will be suspended.",
                isNightMode = isNightMode
            ) {
                val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("is_logged_in", false).apply()

                com.example.aisecurity.ble.WatchManager.disconnect()
                auth.signOut()

                val intent = Intent(requireActivity(), LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            }
        }
    }

    // ==========================================
    // 🚨 2-STEP PHONE CHANGE LOGIC
    // ==========================================
    // ==========================================
    // 🚨 2-STEP PHONE CHANGE LOGIC (FIXED BINDING)
    // ==========================================
    // ==========================================
    // 🚨 2-STEP PHONE CHANGE LOGIC (FIXED BINDING)
    // ==========================================
    private fun showChangePhoneDialog(tvPhone: TextView, isNightMode: Boolean) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_change_phone, null)
        val dialog = AlertDialog.Builder(requireContext()).setView(dialogView).setCancelable(false).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val dialogRoot: LinearLayout = dialogView.findViewById(R.id.dialogRoot)
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

        // Phase 1 Views Declared Explicitly
        val layoutPhase1: LinearLayout = dialogView.findViewById(R.id.layoutPhase1)
        val tvOldPhoneDesc: TextView = dialogView.findViewById(R.id.tvOldPhoneDesc)
        val etOldCode: EditText = dialogView.findViewById(R.id.etOldCode)
        val btnGetOldCode: TextView = dialogView.findViewById(R.id.btnGetOldCode)
        val btnVerifyOld: Button = dialogView.findViewById(R.id.btnVerifyOld)
        val btnCancelOld: Button = dialogView.findViewById(R.id.btnCancelOld)

        // Phase 2 Views Declared Explicitly
        val layoutPhase2: LinearLayout = dialogView.findViewById(R.id.layoutPhase2)
        val etNewPhone: EditText = dialogView.findViewById(R.id.etNewPhone)
        val etNewCode: EditText = dialogView.findViewById(R.id.etNewCode)
        val cardNewOtp: CardView = dialogView.findViewById(R.id.cardNewOtp)
        val btnGetNewCode: TextView = dialogView.findViewById(R.id.btnGetNewCode)
        val btnUpdatePhone: Button = dialogView.findViewById(R.id.btnUpdatePhone)
        val btnCancelNew: Button = dialogView.findViewById(R.id.btnCancelNew)

        var oldVerificationId = ""
        var newVerificationId = ""
        var pendingNewPhone = ""

        tvOldPhoneDesc.text = "An OTP will be sent to your current number ending in ${currentPhone.takeLast(4)} to verify your identity."

        btnCancelOld.setOnClickListener { dialog.dismiss() }
        btnCancelNew.setOnClickListener { dialog.dismiss() }

        // Phase 1 Actions
        btnGetOldCode.setOnClickListener {
            btnGetOldCode.text = "SENDING..."
            btnGetOldCode.isEnabled = false
            requestFirebaseOtp(
                phone = currentPhone,
                onCodeSent = { id ->
                    oldVerificationId = id
                    btnGetOldCode.text = "SENT"
                    showSentryToast("Security OTP sent", false)
                },
                onAutoVerified = { cred ->
                    verifyOldPhone(cred, layoutPhase1, layoutPhase2, btnVerifyOld)
                },
                onError = { err ->
                    btnGetOldCode.isEnabled = true
                    btnGetOldCode.text = "RETRY"
                    showSentryToast("SMS Error: $err", true)
                }
            )
        }

        btnVerifyOld.setOnClickListener {
            val code = etOldCode.text.toString().trim()
            if (code.length < 6) return@setOnClickListener showSentryToast("Enter full code", false)
            if (oldVerificationId.isEmpty()) return@setOnClickListener showSentryToast("Request OTP first", false)

            btnVerifyOld.text = "VERIFYING..."
            btnVerifyOld.isEnabled = false
            try {
                val credential = PhoneAuthProvider.getCredential(oldVerificationId, code)
                verifyOldPhone(credential, layoutPhase1, layoutPhase2, btnVerifyOld)
            } catch (e: Exception) {
                btnVerifyOld.text = "VERIFY"
                btnVerifyOld.isEnabled = true
                showSentryToast("Invalid Code", true)
            }
        }

        // Phase 2 Actions
        btnGetNewCode.setOnClickListener {
            val inputRaw = etNewPhone.text.toString().trim()
            if (inputRaw.length < 7) return@setOnClickListener showSentryToast("Enter valid number", false)

            pendingNewPhone = if (inputRaw.startsWith("+")) inputRaw else "+63${inputRaw.dropWhile { it == '0' }}"

            btnGetNewCode.text = "SENDING..."
            btnGetNewCode.isEnabled = false

            requestFirebaseOtp(
                phone = pendingNewPhone,
                onCodeSent = { id ->
                    newVerificationId = id
                    btnGetNewCode.text = "SENT"
                    cardNewOtp.visibility = View.VISIBLE
                    showSentryToast("OTP sent to new number", false)
                },
                onAutoVerified = { cred ->
                    updatePhoneRecord(cred, pendingNewPhone, dialog, tvPhone, btnUpdatePhone)
                },
                onError = { err ->
                    btnGetNewCode.isEnabled = true
                    btnGetNewCode.text = "RETRY"
                    showSentryToast("SMS Error: $err", true)
                }
            )
        }

        btnUpdatePhone.setOnClickListener {
            val code = etNewCode.text.toString().trim()
            if (code.length < 6) return@setOnClickListener showSentryToast("Enter full code", false)
            if (newVerificationId.isEmpty()) return@setOnClickListener showSentryToast("Request OTP first", false)

            btnUpdatePhone.text = "UPDATING..."
            btnUpdatePhone.isEnabled = false
            try {
                val credential = PhoneAuthProvider.getCredential(newVerificationId, code)
                updatePhoneRecord(credential, pendingNewPhone, dialog, tvPhone, btnUpdatePhone)
            } catch (e: Exception) {
                btnUpdatePhone.text = "UPDATE"
                btnUpdatePhone.isEnabled = true
                showSentryToast("Invalid Code", true)
            }
        }

        dialog.show()
    }

    private fun verifyOldPhone(cred: PhoneAuthCredential, phase1: View, phase2: View, btnVerify: Button) {
        auth.currentUser?.reauthenticate(cred)?.addOnSuccessListener {
            showSentryToast("Identity Verified. Enter new number.", false)
            phase1.visibility = View.GONE
            phase2.visibility = View.VISIBLE
        }?.addOnFailureListener {
            btnVerify.text = "VERIFY"
            btnVerify.isEnabled = true
            showSentryToast("Verification Failed", true)
        }
    }

    private fun updatePhoneRecord(cred: PhoneAuthCredential, newPhone: String, dialog: AlertDialog, tvPhone: TextView, btnUpdate: Button) {
        auth.currentUser?.updatePhoneNumber(cred)?.addOnSuccessListener {
            db.collection("Users").document(auth.currentUser!!.uid)
                .update("phoneNumber", newPhone).addOnSuccessListener {
                    currentPhone = newPhone
                    tvPhone.text = newPhone
                    showSentryToast("Phone Number Successfully Updated!", true)
                    dialog.dismiss()
                }
        }?.addOnFailureListener {
            btnUpdate.text = "UPDATE"
            btnUpdate.isEnabled = true
            showSentryToast("Failed to update: ${it.message}", true)
        }
    }

    private fun requestFirebaseOtp(phone: String, onCodeSent: (String) -> Unit, onAutoVerified: (PhoneAuthCredential) -> Unit, onError: (String) -> Unit) {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) { onAutoVerified(credential) }
            override fun onVerificationFailed(e: FirebaseException) { onError(e.message ?: "Error") }
            override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) { onCodeSent(verificationId) }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(requireActivity())
            .setCallbacks(callbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    // ==========================================
    // PROFILE & IMAGE UPLOADS
    // ==========================================
    private fun uploadPhotoToFirebase(fileUri: Uri) {
        val uid = auth.currentUser?.uid ?: return
        showSentryToast("Uploading photo...", isLong = true)

        val storageRef = FirebaseStorage.getInstance().reference.child("profile_pictures/$uid.jpg")

        storageRef.putFile(fileUri)
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                    currentPhotoUri = downloadUri.toString()

                    db.collection("Users").document(uid)
                        .set(hashMapOf("photoUri" to currentPhotoUri), SetOptions.merge())

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val bitmap = android.graphics.BitmapFactory.decodeStream(URL(currentPhotoUri).openStream())
                            withContext(Dispatchers.Main) {
                                dialogAvatarImage?.setImageBitmap(bitmap)
                                dialogAvatarImage?.visibility = View.VISIBLE
                                dialogAvatarInitials?.visibility = View.GONE
                                updateMainAvatarUI()
                                showSentryToast("Photo uploaded successfully!", isLong = false)
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }
            .addOnFailureListener { showSentryToast("Failed to upload photo.", isLong = false) }
    }

    private fun showSecureVerificationDialog(title: String, message: String, isNightMode: Boolean, onProceed: () -> Unit) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_secure_verification, null)
        val dialogRoot = dialogView.findViewById<LinearLayout>(R.id.dialogRoot)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvVerificationTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvVerificationMessage)
        val btnProceed = dialogView.findViewById<Button>(R.id.btnProceed)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        tvTitle.text = title
        tvMessage.text = message

        val dialogBg = GradientDrawable().apply {
            cornerRadius = 60f
            if (isNightMode) { setColor(Color.parseColor("#FA0F172A")); setStroke(2, Color.parseColor("#334155")) }
            else { setColor(Color.parseColor("#FAFFFFFF")); setStroke(2, Color.parseColor("#CBD5E1")) }
        }
        dialogRoot.background = dialogBg

        if (title.contains("Logout", ignoreCase = true)) {
            applyDestructivePrimaryButton(btnProceed, isNightMode)
            btnProceed.text = "LOG OUT"
        } else {
            applyPrimaryButton(btnProceed, isNightMode)
        }
        applyGhostButton(btnCancel, isNightMode)

        val dialog = AlertDialog.Builder(requireContext()).setView(dialogView).setCancelable(false).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnProceed.setOnClickListener { dialog.dismiss(); onProceed() }
        dialog.show()
    }

    private fun loadUserData(header: TextView, full: TextView, email: TextView, phone: TextView) {
        val userId = auth.currentUser?.uid ?: return

        db.collection("Users").document(userId).get().addOnSuccessListener { document ->
            if (document != null && document.exists()) {
                currentFirstName = document.getString("firstName") ?: ""
                currentLastName = document.getString("lastName") ?: ""
                currentMI = document.getString("middleInitial") ?: ""
                currentPhotoUri = document.getString("photoUri") ?: ""
                currentPhone = document.getString("phoneNumber") ?: ""

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
                phone.text = if (currentPhone.isNotEmpty()) currentPhone else "--"

                updateMainAvatarUI()
            }
        }
    }

    private fun updateMainAvatarUI() {
        if (currentPhotoUri.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val bitmap = if (currentPhotoUri.startsWith("http")) {
                        android.graphics.BitmapFactory.decodeStream(URL(currentPhotoUri).openStream())
                    } else {
                        requireContext().contentResolver.openInputStream(Uri.parse(currentPhotoUri))?.use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        if (bitmap != null && isAdded) {
                            mainAvatarImage.setImageBitmap(bitmap)
                            mainAvatarImage.visibility = View.VISIBLE
                            mainAvatarInitials.visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            mainAvatarImage.visibility = View.GONE
                            mainAvatarInitials.visibility = View.VISIBLE
                            mainAvatarInitials.text = getInitials(currentFirstName, currentLastName)
                        }
                    }
                }
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
            if (isNightMode) { setColor(Color.parseColor("#FA0F172A")); setStroke(2, Color.parseColor("#334155")) }
            else { setColor(Color.parseColor("#FAFFFFFF")); setStroke(2, Color.parseColor("#CBD5E1")) }
        }
        dialogRoot.background = dialogBg

        applyPrimaryButton(btnSave, isNightMode)
        applyGhostButton(btnCancel, isNightMode)
        applyAvatarBackground(dialogAvatarInitials!!, isNightMode)

        etFirstName.setText(currentFirstName)
        etLastName.setText(currentLastName)
        etMI.setText(currentMI)

        if (currentPhotoUri.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val bitmap = if (currentPhotoUri.startsWith("http")) {
                        android.graphics.BitmapFactory.decodeStream(URL(currentPhotoUri).openStream())
                    } else {
                        requireContext().contentResolver.openInputStream(Uri.parse(currentPhotoUri))?.use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        if (bitmap != null) {
                            dialogAvatarImage?.setImageBitmap(bitmap)
                            dialogAvatarImage?.visibility = View.VISIBLE
                            dialogAvatarInitials?.visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        dialogAvatarImage?.visibility = View.GONE
                        dialogAvatarInitials?.visibility = View.VISIBLE
                        dialogAvatarInitials?.text = getInitials(currentFirstName, currentLastName)
                    }
                }
            }
        } else {
            dialogAvatarInitials?.text = getInitials(currentFirstName, currentLastName)
        }

        cardDialogAvatar.setOnClickListener { pickImageLauncher.launch("image/*") }

        val dialog = AlertDialog.Builder(requireContext()).setView(dialogView).setCancelable(false).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val fn = etFirstName.text.toString().trim()
            val ln = etLastName.text.toString().trim()
            val mi = etMI.text.toString().trim().uppercase()

            if (fn.isEmpty() || ln.isEmpty()) {
                showSentryToast("First and Last name are required", isLong = false)
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
                    updateMainAvatarUI()

                    showSentryToast("Profile Updated!", isLong = false)
                    dialog.dismiss()
                }
                .addOnFailureListener {
                    btnSave.isEnabled = true
                    btnSave.text = "SAVE"
                    showSentryToast("Network Error. Try again.", isLong = false)
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
        val bg = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 30f }
        if (isNightMode) {
            bg.setColor(Color.parseColor("#3B82F6"))
            button.setTextColor(Color.parseColor("#FFFFFF"))
        } else {
            bg.setColor(Color.parseColor("#2563EB"))
            button.setTextColor(Color.parseColor("#FFFFFF"))
        }
        button.background = bg
    }

    private fun applyDestructivePrimaryButton(button: Button, isNightMode: Boolean) {
        val bg = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 30f }
        if (isNightMode) {
            bg.setColor(Color.parseColor("#EF4444"))
            button.setTextColor(Color.parseColor("#FFFFFF"))
        } else {
            bg.setColor(Color.parseColor("#DC2626"))
            button.setTextColor(Color.parseColor("#FFFFFF"))
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
        val bg = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 30f }
        if (isNightMode) {
            bg.setColor(Color.parseColor("#1AEF4444"))
            button.setTextColor(Color.parseColor("#F87171"))
        } else {
            bg.setColor(Color.parseColor("#1AEF4444"))
            button.setTextColor(Color.parseColor("#DC2626"))
        }
        button.background = bg
    }

    private fun showSentryToast(message: String, isLong: Boolean) {
        val toast = Toast(requireContext())
        toast.duration = if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT

        val customLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.parseColor("#12151C"))
                setStroke(3, Color.parseColor("#3B82F6"))
            }
            setPadding(50, 30, 50, 30)
        }

        val icon = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_sentry_half_gold)
            layoutParams = LinearLayout.LayoutParams(60, 75).apply { setMargins(0, 0, 30, 0) }
        }

        val textView = TextView(requireContext()).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        customLayout.addView(icon)
        customLayout.addView(textView)
        toast.view = customLayout
        toast.show()
    }
}