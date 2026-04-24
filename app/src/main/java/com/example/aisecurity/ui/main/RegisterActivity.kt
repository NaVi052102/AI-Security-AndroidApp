package com.example.aisecurity.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.aisecurity.R
import com.google.android.material.button.MaterialButton
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit

class RegisterActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private var storedVerificationId: String = ""
    private var pendingFormattedPhone: String = ""
    private var pendingFullName: String = ""
    private var pendingEmail: String = ""

    // STATE MACHINE: 0 = Form, 1 = Email Wait, 2 = OTP Wait
    private var currentState = 0

    private lateinit var tvRegisterHeader: TextView
    private lateinit var layoutRegistrationForm: LinearLayout
    private lateinit var layoutEmailVerification: LinearLayout
    private lateinit var layoutOtpVerification: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        tvRegisterHeader = findViewById(R.id.tvRegisterHeader)
        layoutRegistrationForm = findViewById(R.id.layoutRegistrationForm)
        layoutEmailVerification = findViewById(R.id.layoutEmailVerification)
        layoutOtpVerification = findViewById(R.id.layoutOtpVerification)

        val etName = findViewById<EditText>(R.id.etRegName)
        val etEmail = findViewById<EditText>(R.id.etRegEmail)
        val etPhone = findViewById<EditText>(R.id.etRegPhone)
        val etPassword = findViewById<EditText>(R.id.etRegPassword)
        val etConfirmPassword = findViewById<EditText>(R.id.etRegConfirmPassword)

        val btnRegister = findViewById<MaterialButton>(R.id.btnRegister)
        val btnCheckEmailVerified = findViewById<MaterialButton>(R.id.btnCheckEmailVerified)
        val btnVerifyOtp = findViewById<MaterialButton>(R.id.btnVerifyOtp)

        val etOtpCode = findViewById<EditText>(R.id.etOtpCode)
        val tvGoToLogin = findViewById<TextView>(R.id.tvGoToLogin)

        // PREVENT BACK BUTTON ESCAPE
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentState > 0) {
                    showSentryToast("You must complete verification to continue.", isLong = false)
                } else {
                    finish()
                }
            }
        })

        // LOAD SAVED STATE
        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        currentState = prefs.getInt("reg_state", 0)

        if (currentState > 0) {
            pendingFullName = prefs.getString("reg_name", "") ?: ""
            pendingEmail = prefs.getString("reg_email", "") ?: ""
            pendingFormattedPhone = prefs.getString("reg_phone", "") ?: ""

            layoutRegistrationForm.visibility = View.GONE

            if (currentState == 1) {
                layoutEmailVerification.visibility = View.VISIBLE
                tvRegisterHeader.text = "Verify Email"
            } else if (currentState == 2) {
                layoutOtpVerification.visibility = View.VISIBLE
                tvRegisterHeader.text = "Phone Verification"
                sendSmsVerification(pendingFormattedPhone)
            }
        }

        // ==========================================
        // STAGE 1: SIGN UP CLICKED (WITH NEW VALIDATIONS)
        // ==========================================
        btnRegister.setOnClickListener {
            pendingFullName = etName.text.toString().trim()
            pendingEmail = etEmail.text.toString().trim()
            val rawPhone = etPhone.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val confirmPassword = etConfirmPassword.text.toString().trim()

            // 🚨 STRICT VALIDATION CHECKS
            if (pendingFullName.length < 3) {
                showSentryToast("Please enter your full, real name.", isLong = false)
                return@setOnClickListener
            }
            if (!Patterns.EMAIL_ADDRESS.matcher(pendingEmail).matches()) {
                showSentryToast("Please enter a valid email format.", isLong = false)
                return@setOnClickListener
            }
            if (rawPhone.length < 7) {
                showSentryToast("Please enter a valid phone number.", isLong = false)
                return@setOnClickListener
            }
            if (password.length < 6) {
                showSentryToast("Password must be at least 6 characters.", isLong = false)
                return@setOnClickListener
            }
            if (password != confirmPassword) {
                showSentryToast("Passwords do not match.", isLong = false)
                return@setOnClickListener
            }

            pendingFormattedPhone = if (rawPhone.startsWith("+")) rawPhone else "+63${rawPhone.dropWhile { it == '0' }}"

            btnRegister.isEnabled = false
            btnRegister.text = "Deploying Account..."

            auth.createUserWithEmailAndPassword(pendingEmail, password).addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    auth.currentUser?.sendEmailVerification()
                    updateAndSaveState(1)
                } else {
                    btnRegister.isEnabled = true
                    btnRegister.text = "Sign Up"
                    showSentryToast("Error: ${task.exception?.message}", isLong = true)
                }
            }
        }

        // ==========================================
        // STAGE 2: CHECK EMAIL VERIFICATION
        // ==========================================
        btnCheckEmailVerified.setOnClickListener {
            btnCheckEmailVerified.isEnabled = false
            btnCheckEmailVerified.text = "Checking Server..."

            auth.currentUser?.reload()?.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    if (auth.currentUser?.isEmailVerified == true) {
                        updateAndSaveState(2)
                        sendSmsVerification(pendingFormattedPhone)
                    } else {
                        btnCheckEmailVerified.isEnabled = true
                        btnCheckEmailVerified.text = "I Have Clicked The Link"
                        showSentryToast("Not verified yet! Please check your spam folder.", isLong = true)
                    }
                } else {
                    btnCheckEmailVerified.isEnabled = true
                    btnCheckEmailVerified.text = "I Have Clicked The Link"
                    showSentryToast("Network Error. Try again.", isLong = false)
                }
            }
        }

        // ==========================================
        // STAGE 3: VERIFY OTP AND AUTO-LOGIN
        // ==========================================
        btnVerifyOtp.setOnClickListener {
            val code = etOtpCode.text.toString().trim()

            if (code.length < 6) {
                showSentryToast("Please enter the 6-digit code", isLong = false)
                return@setOnClickListener
            }

            if (storedVerificationId.isEmpty()) {
                showSentryToast("Please wait, still requesting SMS session...", isLong = false)
                return@setOnClickListener
            }

            btnVerifyOtp.isEnabled = false
            btnVerifyOtp.text = "Verifying..."

            try {
                val credential = PhoneAuthProvider.getCredential(storedVerificationId, code)

                auth.currentUser?.linkWithCredential(credential)?.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        finalizeAccountAndLogin()
                    } else {
                        btnVerifyOtp.isEnabled = true
                        btnVerifyOtp.text = "Verify Security Code"
                        showSentryToast("Incorrect Code: ${task.exception?.message}", isLong = true)
                    }
                }
            } catch (e: Exception) {
                btnVerifyOtp.isEnabled = true
                btnVerifyOtp.text = "Verify Security Code"
                showSentryToast("Verification Error. Try again.", isLong = true)
            }
        }

        tvGoToLogin.setOnClickListener { finish() }
    }

    private fun updateAndSaveState(newState: Int) {
        currentState = newState
        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("reg_state", currentState)
            .putString("reg_name", pendingFullName)
            .putString("reg_email", pendingEmail)
            .putString("reg_phone", pendingFormattedPhone)
            .apply()

        if (currentState == 1) {
            layoutRegistrationForm.visibility = View.GONE
            layoutEmailVerification.visibility = View.VISIBLE
            tvRegisterHeader.text = "Verify Email"
        } else if (currentState == 2) {
            layoutEmailVerification.visibility = View.GONE
            layoutOtpVerification.visibility = View.VISIBLE
            tvRegisterHeader.text = "Phone Verification"
        }
    }

    private fun finalizeAccountAndLogin() {
        val userId = auth.currentUser?.uid ?: ""
        val userProfile = hashMapOf("fullName" to pendingFullName, "email" to pendingEmail, "phoneNumber" to pendingFormattedPhone)

        db.collection("Users").document(userId).set(userProfile).addOnSuccessListener {

            val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("reg_state", 0)
                .putBoolean("is_logged_in", true)
                .apply()

            showSentryToast("Registration Complete! System Armed.", isLong = true)
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }

    private fun sendSmsVerification(phoneNumber: String) {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                auth.currentUser?.linkWithCredential(credential)?.addOnSuccessListener {
                    finalizeAccountAndLogin() // Auto-Verified!
                }
            }

            override fun onVerificationFailed(e: FirebaseException) {
                showSentryToast("SMS Failed: ${e.message}", isLong = true)
            }

            override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                storedVerificationId = verificationId
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(callbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    // 🚨 SENTRY CUSTOM TOAST (Added to Registration)
    private fun showSentryToast(message: String, isLong: Boolean) {
        val toast = Toast(this)
        toast.duration = if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT

        val customLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.parseColor("#12151C"))
                setStroke(3, Color.parseColor("#3B82F6"))
            }
            setPadding(50, 30, 50, 30)
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_sentry_half_gold)
            layoutParams = LinearLayout.LayoutParams(60, 75).apply {
                setMargins(0, 0, 30, 0)
            }
        }

        val textView = TextView(this).apply {
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