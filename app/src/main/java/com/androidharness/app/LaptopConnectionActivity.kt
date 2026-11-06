package com.androidharness.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.androidharness.app.R

class LaptopConnectionActivity : AppCompatActivity() {

    private lateinit var connectivityManager: ConnectivityManager
    private var isTetheringEnabled = false

    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var enableButton: Button
    private lateinit var copyButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_laptop_connection)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        statusText = findViewById(R.id.laptop_status)
        addressText = findViewById(R.id.laptop_ip)
        enableButton = findViewById(R.id.laptop_enable_button)
        copyButton = findViewById(R.id.laptop_copy_button)

        copyButton.setOnClickListener {
            val address = addressText.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("laptop_address", address))
            Toast.makeText(this, R.string.laptop_copied, Toast.LENGTH_SHORT).show()
        }

        enableButton.setOnClickListener {
            if (isTetheringEnabled) {
                stopUsbTethering()
            } else {
                startUsbTethering()
            }
        }

        refresh()
    }

    private fun startUsbTethering() {
        val success = tryUsbTethering(true)
        if (!success) {
            statusText.text = getString(R.string.laptop_status_unavailable)
            statusText.setTextColor(android.graphics.Color.RED)
            return
        }

        statusText.text = getString(R.string.laptop_status_tethering_on)
        statusText.setTextColor(android.graphics.Color.GREEN)
        isTetheringEnabled = true
        enableButton.text = getString(R.string.laptop_disable_tethering)
        copyButton.isEnabled = true
        addressText.text = getString(R.string.laptop_address_example)
    }

    private fun stopUsbTethering() {
        tryUsbTethering(false)
        isTetheringEnabled = false
        enableButton.text = getString(R.string.laptop_enable_tethering)
        addressText.text = getString(R.string.laptop_address_placeholder)
        copyButton.isEnabled = false
        statusText.text = getString(R.string.laptop_status_tethering_off)
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

    private fun refresh() {
        addressText.text = getString(R.string.laptop_address_example)
        statusText.text = getString(R.string.laptop_status_tethering_off)
        statusText.setTextColor(android.graphics.Color.WHITE)
        isTetheringEnabled = false
        enableButton.text = getString(R.string.laptop_enable_tethering)
        copyButton.isEnabled = false
    }

    @SuppressLint("MissingPermission")
    private fun updateUi(enabled: Boolean) {
        isTetheringEnabled = enabled
        enableButton.text = if (enabled) getString(R.string.laptop_disable_tethering) else getString(R.string.laptop_enable_tethering)
        copyButton.isEnabled = enabled
    }

    override fun onDestroy() {
        if (isTetheringEnabled) {
            stopUsbTethering()
        }
        super.onDestroy()
    }
}
