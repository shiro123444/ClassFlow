package com.xingheyuzhuan.shiguangschedule.ui.campus.qrscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.QrScanEngine
import zxingcpp.BarcodeReader

/**
 * 从相册图片里解出二维码原文，按当前选择 [QrScanEngine] 走对应实现。
 *
 * 只做「图片 → 文本」，文本的合法性与后续扫码/确认流程复用相机那条路径（[CasQrLink] + 扫码端协议）。
 */
internal object QrImageDecoder {

    private const val TAG = "QrImageDecoder"

    /** 超过该边长先降采样：ZXing 在超大图上很慢，且对识别率没有帮助。 */
    private const val MAX_SIDE = 1600

    fun decode(context: Context, uri: Uri, engine: QrScanEngine): String? = try {
        when (engine) {
            QrScanEngine.ZXING -> decodeWithZxing(context, uri)
            QrScanEngine.ZXING_CPP -> decodeWithZxingCpp(context, uri)
        }
    } catch (e: Exception) {
        Log.w(TAG, "image decode failed", e)
        null
    }

    private fun decodeWithZxing(context: Context, uri: Uri): String? {
        val bitmap = loadBitmap(context, uri) ?: return null
        val scaled = downscale(bitmap)
        val width = scaled.width
        val height = scaled.height
        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        return try {
            MultiFormatReader().decode(
                binary,
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                )
            )?.text
        } catch (e: NotFoundException) {
            // 图片里没有可识别二维码
            null
        }
    }

    /** zxing-cpp 解图片（与相机那条路同一套解码器）。 */
    private fun decodeWithZxingCpp(context: Context, uri: Uri): String? {
        val bitmap = loadBitmap(context, uri) ?: return null
        val scaled = downscale(bitmap)
        val reader = BarcodeReader(
            BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.QR_CODE),
                tryHarder = true,
                tryRotate = true,
                tryInvert = true
            )
        )
        return reader.read(scaled).firstOrNull()?.text
    }

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder 会按 EXIF 自动转正
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                decoder.isMutableRequired = false
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            // API 26/27 无 EXIF 转正，横拍照片可能解不出
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
        return bitmap
    }

    private fun downscale(source: Bitmap): Bitmap {
        val maxSide = maxOf(source.width, source.height)
        if (maxSide <= MAX_SIDE || maxSide <= 0) return source
        val ratio = MAX_SIDE.toFloat() / maxSide
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
