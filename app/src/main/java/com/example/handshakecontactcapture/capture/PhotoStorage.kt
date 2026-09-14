package com.example.handshakecontactcapture.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.handshakecontactcapture.ai.AiFailure
import com.example.handshakecontactcapture.data.newId
import java.io.File

class PhotoStorage(private val context: Context) {
    private val directory = File(context.filesDir, "photos").apply { mkdirs() }
    fun file(name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9-]+\\.jpg")))
        return File(directory, name)
    }
    fun cameraFile(): File = File(context.filesDir, "camera").apply { mkdirs() }
        .let { File(it, "${newId()}.jpg") }
    fun cameraUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)

    /** Re-encoding strips GPS/EXIF metadata; pixels are rotated upright before upload. */
    fun import(uri: Uri): String {
        val temporary = File(directory, "${newId()}.input")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 30 * 1024 * 1024) throw AiFailure("Choose an image smaller than 30 MB.")
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw AiFailure("Could not read that photo. Please choose it again.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw AiFailure("This file is not a supported image.")
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
            val bitmap = BitmapFactory.decodeFile(temporary.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw AiFailure("Could not decode this image.")
            val exif = ExifInterface(temporary)
            val matrix = Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val name = "${newId()}.jpg"
            try {
                file(name).outputStream().use { check(upright.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } catch (e: Exception) { file(name).delete(); throw e }
            finally { if (upright !== bitmap) upright.recycle(); bitmap.recycle() }
            return name
        } finally { temporary.delete() }
    }
    fun delete(name: String) { file(name).delete() }
    fun preview(name: String): Bitmap? = BitmapFactory.decodeFile(file(name).path,
        BitmapFactory.Options().apply { inSampleSize = 2 })
}
