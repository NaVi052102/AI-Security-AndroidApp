package com.example.aisecurity.ui.main

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.aisecurity.R

@Suppress("DEPRECATION")
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val splashLogo = findViewById<ImageView>(R.id.splashLogo)
        val splashText = findViewById<TextView>(R.id.splashText)
        val splashReflection = findViewById<TextView>(R.id.splashReflection)
        val splashGlow = findViewById<View>(R.id.splashGlow)

        val finalWord = "SENTRY"
        val hackerChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@#$%"

        // 1. Shield violently snaps in
        splashLogo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1200)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        // 2. Ambient Glow softly pulses in (The Horizon Light)
        splashGlow.animate()
            .alpha(1f)
            .setStartDelay(600)
            .setDuration(2000)
            .start()

        // 3. Text glides up
        splashText.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(400)
            .setDuration(800)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 4. The Cryptographic Decryption
        val decryptAnimator = ValueAnimator.ofFloat(0f, 1f)
        decryptAnimator.startDelay = 400
        decryptAnimator.duration = 800

        decryptAnimator.addUpdateListener { animation ->
            val progress = animation.animatedFraction

            if (progress >= 1f) {
                splashText.text = finalWord
                splashReflection.text = finalWord

                // BOOM: Haptic Feedback on lock-in
                splashText.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                // 🚨 Apply the metallic floor fade shader directly to the text!
                val textShader = android.graphics.LinearGradient(
                    0f, 0f, 0f, splashReflection.textSize,
                    intArrayOf(android.graphics.Color.parseColor("#66FF8C00"), android.graphics.Color.TRANSPARENT),
                    null,
                    android.graphics.Shader.TileMode.CLAMP
                )
                splashReflection.paint.shader = textShader

                // Floor reflection fades in exactly when the word locks
                splashReflection.animate()
                    .alpha(1f) // Bring to full alpha, the shader handles the fading!
                    .setDuration(500)
                    .start()

            } else {
                val scrambled = java.lang.StringBuilder()
                for (i in finalWord.indices) {
                    if (i < (progress * finalWord.length).toInt()) {
                        scrambled.append(finalWord[i])
                    } else {
                        scrambled.append(hackerChars.random())
                    }
                }
                splashText.text = scrambled.toString()
                splashReflection.text = scrambled.toString()
            }
        }

        // 5. Navigate to Login after holding the final frame
        decryptAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                splashText.postDelayed({
                    startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    finish()
                }, 800)
            }
        })

        decryptAnimator.start()
    }
}