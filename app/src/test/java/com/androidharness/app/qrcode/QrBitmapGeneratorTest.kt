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
        assertTrue(qr.size >= 21) // Version 1 is at least 21x21 modules
    }
}
