package com.androidharness.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Laptop bridge helper.
 *
 * The phone is the host. This screen finds the address the laptop should open
 * when the two are connected with the Type-C charging cable (USB tethering), so
 * nothing has to be installed on the laptop and no camera is needed.
 *
 * Turning USB tethering itself on is a system action, so the screen links to the
 * Android tethering settings instead of trying to flip the switch for the user.
 */
class LaptopConnectionActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var enableButton: Button
    private lateinit var copyButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_laptop_connection)

        statusText = findViewById(R.id.laptop_status)
        addressText = findViewById(R.id.laptop_ip)
        enableButton = findViewById(R.id.laptop_enable_button)
        copyButton = findViewById(R.id.laptop_copy_button)

        copyButton.setOnClickListener {
            val address = currentAddress()
            if (address != null) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("laptop address", address))
                Toast.makeText(this, R.string.laptop_copied, Toast.LENGTH_SHORT).show()
            }
        }

        enableButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(intent) }
                .onFailure { Toast.makeText(this, R.string.laptop_status_unavailable, Toast.LENGTH_LONG).show() }
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val address = detectUsbAddress() ?: detectAnySiteLocalAddress()
        if (address != null) {
            addressText.text = "$address:8080"
            statusText.text = getString(R.string.laptop_status_tethering_on)
            copyButton.isEnabled = true
        } else {
            addressText.text = getString(R.string.laptop_address_placeholder)
            statusText.text = getString(R.string.laptop_status_tethering_off)
            copyButton.isEnabled = false
        }
        enableButton.text = getString(R.string.laptop_enable_tethering)
    }

    private fun currentAddress(): String? = addressText.text?.toString()
        ?.takeIf { it.isNotBlank() && it != getString(R.string.laptop_address_placeholder) }

    /** Prefer the USB/RNDIS interface, which is what the laptop is attached to. */
    private fun detectUsbAddress(): String? = runCatching {
        allAddresses().firstOrNull { (name, _) ->
            val n = name.lowercase()
            n.startsWith("usb") || n.startsWith("rndis") || n.startsWith("eth")
        }?.second
    }.getOrNull()

    private fun detectAnySiteLocalAddress(): String? = runCatching {
        allAddresses().firstOrNull()?.second
    }.getOrNull()

    private fun allAddresses(): List<Pair<String, String>> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                Collections.list(iface.inetAddresses).mapNotNull { addr ->
                    val v4 = addr as? Inet4Address ?: return@mapNotNull null
                    if (v4.isLoopbackAddress || !v4.isSiteLocalAddress) return@mapNotNull null
                    iface.name to (v4.hostAddress ?: return@mapNotNull null)
                }
            }
    }.getOrDefault(emptyList())
}
