package com.androidharness.app.qrcode

import android.graphics.Bitmap
import android.graphics.Color
import io.nayuki.qrcodegen.QrCode

object QrBitmapGenerator {

    /**
     * Generates a Crisp Android Bitmap of the given text using standard Nayuki QR Code generator.
     */
    fun createQrBitmap(text: String, sizePx: Int = 512, margin: Int = 2): Bitmap {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val qrSize = qr.size
        val totalModules = qrSize + margin * 2
        val scale = (sizePx / totalModules).coerceAtLeast(1)
        val imageWidth = totalModules * scale
        val imageHeight = totalModules * scale

        val bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(imageWidth * imageHeight)

        for (y in 0 until imageHeight) {
            for (x in 0 until imageWidth) {
                val moduleX = (x / scale) - margin
                val moduleY = (y / scale) - margin
                val isDark = if (moduleX in 0 until qrSize && moduleY in 0 until qrSize) {
                    qr.getModule(moduleX, moduleY)
                } else {
                    false
                }
                pixels[y * imageWidth + x] = if (isDark) Color.BLACK else Color.WHITE
            }
        }

        bitmap.setPixels(pixels, 0, imageWidth, 0, 0, imageWidth, imageHeight)
        return bitmap
    }
}
