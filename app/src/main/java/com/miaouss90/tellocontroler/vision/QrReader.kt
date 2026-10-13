package com.miaouss90.tellocontroler.vision

import com.google.zxing.BinaryBitmap
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** ZXing adapter: the text of the QR code in a color frame, or null. Needs the bigger (480x360) grab. */
object QrReader {
    fun decode(frame: GrayFrame): String? {
        val argb = frame.argb ?: return null
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(frame.width, frame.height, argb)))).text
        } catch (e: ReaderException) {
            null
        }
    }
}
