package com.example.aisecurity.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.aisecurity.R
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()
        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        // 1. CHECK FOR INCOMPLETE VERIFICATION (Trap Logic)
        if (prefs.getInt("reg_state", 0) > 0) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        // 2. CHECK FOR AUTO-LOGIN (User is fully verified)
        if (prefs.getBoolean("is_logged_in", false) && auth.currentUser != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        val etIdentifier = findViewById<EditText>(R.id.etLoginIdentifier)
        val etPassword = findViewById<EditText>(R.id.etLoginPassword)
        val btnLogin = findViewById<MaterialButton>(R.id.btnLogin)
        val tvGoToRegister = findViewById<TextView>(R.id.tvGoToRegister)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)

        btnLogin.setOnClickListener {
            val email = etIdentifier.text.toString().trim()
            val password = etPassword.text.toString().trim()

            // 🚨 RIGOROUS VALIDATION
            if (email.isEmpty()) {
                showSentryToast("Email cannot be empty.", isLong = false)
                return@setOnClickListener
            }
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                showSentryToast("Please enter a valid email format.", isLong = false)
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                showSentryToast("Password cannot be empty.", isLong = false)
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            btnLogin.text = "AUTHENTICATING..."

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        prefs.edit().putBoolean("is_logged_in", true).apply()
                        showSentryToast("Welcome to Sentry", isLong = false)
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    } else {
                        btnLogin.isEnabled = true
                        btnLogin.text = "LOGIN"
                        // 🚨 FIX: Replaced the ugly Firebase exception with a clean, professional message
                        showSentryToast("Incorrect Email or Password.", isLong = true)
                    }
                }
        }

        // 🚨 FIREBASE FORGOT PASSWORD FLOW
        tvForgotPassword.setOnClickListener {
            showForgotPasswordDialog(etIdentifier.text.toString().trim())
        }

        tvGoToRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun showForgotPasswordDialog(prefilledEmail: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_forgot_password, null)
        val dialogRoot = dialogView.findViewById<LinearLayout>(R.id.dialogRoot)
        val etEmail = dialogView.findViewById<EditText>(R.id.etRecoveryEmail)
        val btnSend = dialogView.findViewById<Button>(R.id.btnSendReset)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        if (prefilledEmail.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(prefilledEmail).matches()) {
            etEmail.setText(prefilledEmail)
        }

        val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        dialogRoot.background = GradientDrawable().apply {
            cornerRadius = 60f
            if (isNightMode) {
                setColor(Color.parseColor("#FA0F172A"))
                setStroke(2, Color.parseColor("#334155"))
            } else {
                setColor(Color.parseColor("#FAFFFFFF"))
                setStroke(2, Color.parseColor("#CBD5E1"))
            }
        }

        btnSend.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 30f
            setColor(Color.parseColor("#3B82F6"))
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSend.setOnClickListener {
            val email = etEmail.text.toString().trim()
            if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                showSentryToast("Please enter a valid recovery email.", isLong = false)
                return@setOnClickListener
            }

            btnSend.isEnabled = false
            btnSend.text = "SENDING..."

            auth.sendPasswordResetEmail(email).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    showSentryToast("Recovery link sent to $email", isLong = true)
                    dialog.dismiss()
                } else {
                    btnSend.isEnabled = true
                    btnSend.text = "SEND RECOVERY LINK"
                    showSentryToast("Error: ${task.exception?.message}", isLong = true)
                }
            }
        }

        dialog.show()
    }

    // 🚨 SENTRY CUSTOM TOAST (Added directly to Login Activity)
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