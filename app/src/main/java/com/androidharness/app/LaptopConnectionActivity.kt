package com.androidharness.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkAddress
import android.net.NetworkRequest
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
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
            android.content.ClipboardManager.getSystemService(this)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("laptop_ip", ip))
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
        val usbIf = findUsbInterface()
        if (usbIf == null) {
            statusText.text = "USB cable not detected. Connect your phone to laptop via Type-C cable."
            statusText.setTextColor(android.graphics.Color.RED)
            return
        }

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    val ip = getIpAddress(network)
                    if (ip != null) {
                        ipText.text = ip
                        statusText.text = "USB tethering active — laptop can connect now"
                        statusText.setTextColor(android.graphics.Color.GREEN)
                        isTetheringEnabled = true
                        enableButton.text = "Disable USB Tethering"
                        copyButton.isEnabled = true
                    } else {
                        statusText.text = "Waiting for IP address..."
                        statusText.setTextColor(android.graphics.Color.YELLOW)
                    }
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    statusText.text = "USB connection lost"
                    statusText.setTextColor(android.graphics.Color.RED)
                    isTetheringEnabled = false
                    enableButton.text = "Enable USB Tethering"
                    ipText.text = "—"
                    copyButton.isEnabled = false
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(ConnectivityManager.TRANSPORT_ETHERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)

        // Start USB tethering via reflection (Android API)
        val tetherResult = tryConnectivityManagerTether(connectivityManager, true)
        if (!tetherResult) {
            statusText.text = "USB tethering not available on this device"
            statusText.setTextColor(android.graphics.Color.RED)
        } else {
            statusText.text = "Enabling USB tethering..."
            statusText.setTextColor(android.graphics.Color.YELLOW)
        }
    }

    private fun stopUsbTethering() {
        tryConnectivityManagerTether(connectivityManager, false)
        connectivityManager.unregisterNetworkCallback(networkCallback)
        isTetheringEnabled = false
        enableButton.text = "Enable USB Tethering"
        ipText.text = "—"
        copyButton.isEnabled = false
        statusText.text = "USB tethering disabled"
        statusText.setTextColor(android.graphics.Color.WHITE)
    }

    private fun tryConnectivityManagerTether(cm: ConnectivityManager, enable: Boolean): Boolean {
        return try {
            val method = java.lang.reflect.Method::class.java.getDeclaredMethod(
                "setUsbTethering",
                java.lang.Boolean::class.java.javaObjectType
            )
            method.isAccessible = true
            method.invoke(cm, enable) as Boolean
        } catch (e: Exception) {
            // Fallback: try startUsbTethering (may not exist on all versions)
            try {
                val method = java.lang.reflect.Method::class.java.getDeclaredMethod(
                    "startUsbTethering"
                )
                method.isAccessible = true
                method.invoke(cm)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    private fun findUsbInterface(): String? {
        return try {
            val method = java.lang.reflect.Method::class.java.getDeclaredMethod(
                "getTetheredIfaces"
            )
            method.isAccessible = true
            val ifaces = method.invoke(connectivityManager) as? Array<out String>
            ifaces?.firstOrNull { it.startsWith("usb") || it.startsWith("rndis") }
        } catch (e: Exception) {
            null
        }
    }

    private fun getIpAddress(network: Network): String? {
        return try {
            val method = java.lang.reflect.Method::class.java.getDeclaredMethod(
                "getLinkAddresses",
                java.lang.Network::class.java
            )
            method.isAccessible = true
            val addrs = method.invoke(connectivityManager, network) as? java.util.List<*>
            addrs?.firstOrNull { it is android.net.LinkAddress }?.let {
                (it as android.net.LinkAddress).address.hostAddress
            }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
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
