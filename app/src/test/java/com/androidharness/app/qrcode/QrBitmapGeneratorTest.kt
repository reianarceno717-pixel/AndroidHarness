package com.androidharness.app.qrcode

import io.nayuki.qrcodegen.QrCode
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrBitmapGeneratorTest {

    @Test
    fun `encodes standard qr code cleanly`() {
        val qr = QrCode.encodeText("http://god-ai.local:8765/c", QrCode.Ecc.MEDIUM)
        assertNotNull(qr)
        assertTrue(qr.size >= 21)
    }

    @Test
    fun `verifies qr modules geometry`() {
        val qr = QrCode.encodeText("http://192.168.42.1:8765/c", QrCode.Ecc.MEDIUM)
        assertTrue(qr.getModule(0, 0)) // Top-left position pattern finder corner is black
    }
}
