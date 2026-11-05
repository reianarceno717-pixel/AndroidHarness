package com.androidharness.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
 * No camera needed. No software install on laptop. Manual typing of the link is the fallback
 * when the laptop has no camera to scan a QR code.
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
        // Try to enable USB tethering via ConnectivityManager
        val success = try {
            connectivityManager.startUsbTethering()
        } catch (e: Exception) {
            false
        }

        if (!success) {
            statusText.text = "USB tethering not available on this device"
            statusText.setTextColor(android.graphics.Color.RED)
            return
        }

        statusText.text = "Enabling USB tethering..."
        statusText.setTextColor(android.graphics.Color.YELLOW)

        // Start monitoring for IP address
        monitorIpAddress()
    }

    private fun stopUsbTethering() {
        try {
            connectivityManager.stopUsbTethering()
        } catch (e: Exception) {
            // Ignore
        }
        isTetheringEnabled = false
        enableButton.text = "Enable USB Tethering"
        ipText.text = "—"
        copyButton.isEnabled = false
        statusText.text = "USB tethering disabled"
        statusText.setTextColor(android.graphics.Color.WHITE)
    }

    private fun monitorIpAddress() {
        // Poll for IP address every 1 second for up to 15 seconds
        var attempts = 0
        val maxAttempts = 15

        val checkIp = object : Runnable {
            override fun run() {
                attempts++
                val ip = getUsbIpAddress()
                if (ip != null && attempts < maxAttempts) {
                    runOnUiThread {
                        ipText.text = ip
                        statusText.text = "USB tethering active — laptop can connect now"
                        statusText.setTextColor(android.graphics.Color.GREEN)
                        isTetheringEnabled = true
                        enableButton.text = "Disable USB Tethering"
                        copyButton.isEnabled = true
                    }
                } else if (attempts >= maxAttempts) {
                    runOnUiThread {
                        statusText.text = "Unable to get IP address. Try reconnecting USB cable."
                        statusText.setTextColor(android.graphics.Color.RED)
                    }
                } else {
                    // Retry after 1 second
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 1000)
                }
            }
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(checkIp, 1000)
    }

    private fun getUsbIpAddress(): String? {
        return try {
            val networks = connectivityManager.allNetworks
            for (network in networks) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                if (caps != null && caps.hasTransport(ConnectivityManager.TRANSPORT_ETHERNET)) {
                    val addrs = connectivityManager.getLinkAddresses(network)
                    for (addr in addrs) {
                        if (addr is LinkAddress && !addr.isLinkLocalAddress) {
                            return addr.address.hostAddress
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun updateUi(enabled: Boolean) {
        isTetheringEnabled = enabled
        enableButton.text = if (enabled) "Disable USB Tethering" else "Enable USB Tethering"
        copyButton.isEnabled = enabled && ipText.text.toString() != "—"
    }

    override fun onDestroy() {
        if (isTetheringEnabled) {
            stopUsbTethering()
        }
        super.onDestroy()
    }
}
