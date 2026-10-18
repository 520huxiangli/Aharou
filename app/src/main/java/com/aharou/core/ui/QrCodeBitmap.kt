package com.aharou.core.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 把一段文本编码成二维码位图，供界面直接显示。
 *
 * 微信 iLink 的扫码登录只给一个 H5 链接（`qrcode_img_content`），不是图片，
 * 所以要在本地把链接编成二维码——扫码方（微信）认的是「内容是这个链接的二维码」。
 *
 * 编码失败（内容过长、生成器异常）返回 null，由调用方决定怎么兜底。
 */
@Composable
fun rememberQrBitmap(content: String, sizePx: Int = 720): ImageBitmap? = remember(content, sizePx) {
    runCatching {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bitmap.asImageBitmap()
    }.getOrNull()
}
