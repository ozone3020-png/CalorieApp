package com.calorieapp.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Turns a full-size camera JPEG (often 3–6 MB) into a small, upright JPEG as base64.
 * Around 1024px on the long side is plenty for food recognition and keeps uploads
 * (and the model's free-tier cost per photo) small.
 */
object ImageEncoder {

    fun encodeForUpload(file: File, maxSide: Int = 1024, quality: Int = 80): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unreadable image: $file" }

        // Cheap power-of-two downsample at decode time, then an exact scale below.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Failed to decode image: $file")

        val scale = maxSide.toFloat() / max(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            postRotate(ExifInterface(file).rotationDegrees.toFloat())
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (upright !== decoded) decoded.recycle()

        val bytes = ByteArrayOutputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
        upright.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
