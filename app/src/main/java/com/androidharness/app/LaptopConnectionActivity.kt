package com.androidharness.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.androidharness.app.R

/**
 * Laptop Connection Activity — hosts the AndroidHarness AI on this phone so your laptop
 * can use it directly through a web browser.
 *
 * Flow:
 *   1. Connect phone to laptop via Type-C charging cable
 *   2. Tap "Enable USB Tethering" — phone becomes a network gateway
 *   3. Phone displays its IP address (e.g., 192.168.42.1:8080)
 *   4. Open Chrome/Firefox/Edge on your laptop → type the IP shown on phone
 *   5. You're now using the AI directly on your laptop browser
 *
 * No camera needed. No software install on laptop.
 */
class LaptopConnectionActivity : AppCompatActivity() {

    private lateinit var connectivityManager: ConnectivityManager
    private var isTetheringEnabled = false

    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var enableButton: Button
    private lateinit var copyButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_laptop_connection)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        statusText = findViewById(R.id.laptop_status)
        ipText = findViewById(R.id.laptop_ip)
        enableButton = findViewById(R.id.laptop_enable_button)
        copyButton = findViewById(R.id.laptop_copy_button)

        copyButton.setOnClickListener {
            val ip = ipText.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("laptop_ip", ip))
            Toast.makeText(this, "IP copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        enableButton.setOnClickListener {
            if (isTetheringEnabled) {
                stopUsbTethering()
            } else {
                startUsbTethering()
            }
        }

        updateUi(false)
    }

    private fun startUsbTethering() {
        val success = tryUsbTethering(true)
        if (!success) {
            statusText.text = "USB tethering not available on this device"
            statusText.setTextColor(android.graphics.Color.RED)
            return
        }

        statusText.text = "USB tethering enabled — check your phone settings for IP address"
        statusText.setTextColor(android.graphics.Color.GREEN)
        isTetheringEnabled = true
        enableButton.text = "Disable USB Tethering"
        copyButton.isEnabled = true
        ipText.text = "192.168.42.1:8080 (typical)"
    }

    private fun stopUsbTethering() {
        tryUsbTethering(false)
        isTetheringEnabled = false
        enableButton.text = "Enable USB Tethering"
        ipText.text = "—"
        copyButton.isEnabled = false
        statusText.text = "USB tethering disabled"
        statusText.setTextColor(android.graphics.Color.WHITE)
    }

    @SuppressLint("MissingPermission")
    private fun tryUsbTethering(enable: Boolean): Boolean {
        return try {
            val method = connectivityManager::class.java.getMethod(
                if (enable) "startUsbTethering" else "stopUsbTethering"
            )
            method.isAccessible = true
            method.invoke(connectivityManager) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    private fun updateUi(enabled: Boolean) {
        isTetheringEnabled = enabled
        enableButton.text = if (enabled) "Disable USB Tethering" else "Enable USB Tethering"
        copyButton.isEnabled = enabled
    }

    override fun onDestroy() {
        if (isTetheringEnabled) {
            stopUsbTethering()
        }
        super.onDestroy()
    }
}
