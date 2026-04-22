package com.example.aisecurity.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
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

        // ==========================================
        // PREVENT BACK BUTTON ESCAPE
        // ==========================================
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentState > 0) {
                    Toast.makeText(this@RegisterActivity, "You must complete verification to continue.", Toast.LENGTH_SHORT).show()
                } else {
                    finish() // Normal back behavior if still on the form
                }
            }
        })

        // ==========================================
        // LOAD SAVED STATE (If App Was Closed)
        // ==========================================
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
                sendSmsVerification(pendingFormattedPhone) // Resend fresh code on app restart
            }
        }

        // ==========================================
        // STAGE 1: SIGN UP CLICKED
        // ==========================================
        btnRegister.setOnClickListener {
            pendingFullName = etName.text.toString().trim()
            pendingEmail = etEmail.text.toString().trim()
            val rawPhone = etPhone.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val confirmPassword = etConfirmPassword.text.toString().trim()

            if (pendingFullName.isEmpty() || pendingEmail.isEmpty() || rawPhone.isEmpty()) {
                Toast.makeText(this, "All fields are required!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (password.length < 6 || password != confirmPassword) {
                Toast.makeText(this, "Passwords must match and be 6+ chars", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            pendingFormattedPhone = if (rawPhone.startsWith("+")) rawPhone else "+63${rawPhone.dropWhile { it == '0' }}"

            btnRegister.isEnabled = false
            btnRegister.text = "Creating Account..."

            auth.createUserWithEmailAndPassword(pendingEmail, password).addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    auth.currentUser?.sendEmailVerification()

                    // Transition to Stage 1 and SAVE
                    updateAndSaveState(1)

                } else {
                    btnRegister.isEnabled = true
                    btnRegister.text = "Sign Up"
                    Toast.makeText(this, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
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

                        // Transition to Stage 2 and SAVE
                        updateAndSaveState(2)
                        sendSmsVerification(pendingFormattedPhone)

                    } else {
                        btnCheckEmailVerified.isEnabled = true
                        btnCheckEmailVerified.text = "I Have Clicked The Link"
                        Toast.makeText(this, "Not verified yet! Please check your spam folder.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    btnCheckEmailVerified.isEnabled = true
                    btnCheckEmailVerified.text = "I Have Clicked The Link"
                    Toast.makeText(this, "Network Error. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ==========================================
        // STAGE 3: VERIFY OTP AND AUTO-LOGIN
        // ==========================================
        btnVerifyOtp.setOnClickListener {
            val code = etOtpCode.text.toString().trim()

            if (code.length < 6) {
                Toast.makeText(this, "Please enter the 6-digit code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 🚨 CRITICAL FIX: Prevent crash if clicked before SMS Session is ready
            if (storedVerificationId.isEmpty()) {
                Toast.makeText(this, "Please wait, still requesting SMS session...", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this, "Incorrect Code: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                // Catch any other unexpected formatting crashes
                btnVerifyOtp.isEnabled = true
                btnVerifyOtp.text = "Verify Security Code"
                Toast.makeText(this, "Verification Error. Try again.", Toast.LENGTH_LONG).show()
            }
        }

        tvGoToLogin.setOnClickListener { finish() }
    }

    // ==========================================
    // HELPER: STATE MANAGER
    // ==========================================
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
                .putInt("reg_state", 0) // Clear the trap!
                .putBoolean("is_logged_in", true) // This triggers the Auto-Login!
                .apply()

            Toast.makeText(this, "Registration Complete! Welcome.", Toast.LENGTH_LONG).show()
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }

    // ==========================================
    // FIREBASE SMS SENDER LOGIC
    // ==========================================
    private fun sendSmsVerification(phoneNumber: String) {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                auth.currentUser?.linkWithCredential(credential)?.addOnSuccessListener {
                    finalizeAccountAndLogin() // Auto-Verified!
                }
            }

            override fun onVerificationFailed(e: FirebaseException) {
                Toast.makeText(this@RegisterActivity, "SMS Failed: ${e.message}", Toast.LENGTH_LONG).show()
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
}