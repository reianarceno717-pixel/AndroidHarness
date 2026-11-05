package com.androidharness.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Splash screen shown on cold start while HarnessApp initializes subsystems.
 * Eliminates the blank flash that previously appeared between process start
 * and the first Compose frame of MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Minimum splash duration so the GOD-AI logo is visible long enough
        // to feel intentional, then hand off to MainActivity.
        lifecycleScope.launch {
            delay(800)
            if (isFinishing || isDestroyed) return@launch
            startActivity(Intent(this@SplashActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }
}
