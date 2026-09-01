package com.portavion.stickerboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.FileProvider
import java.io.File

data class Sticker(val file: File) {
    val mimeType: String
        get() = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "image/*"
        }
}

class StickerStore(private val context: Context) {
    private val dir: File
        get() = File(context.filesDir, "stickers").also { it.mkdirs() }

    fun list(): List<Sticker> =
        dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in EXTS }
            ?.sortedByDescending { it.lastModified() }
            ?.map(::Sticker)
            ?: emptyList()

    fun import(uri: Uri) {
        val mime = context.contentResolver.getType(uri)
        val ext = extFor(mime, uri) ?: return
        val dest = File(dir, "${System.currentTimeMillis()}-$n.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }

    fun delete(sticker: Sticker) {
        sticker.file.delete()
    }

    fun contentUri(sticker: Sticker): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sticker.file)

    companion object {
        private val EXTS = setOf("png", "webp", "gif", "jpg", "jpeg")
        private var n = 0
            get() = field++

        fun thumbnail(file: File, maxPx: Int = 256): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val w = bounds.outWidth.coerceAtLeast(1)
            val h = bounds.outHeight.coerceAtLeast(1)
            var sample = 1
            while (w / sample > maxPx || h / sample > maxPx) sample *= 2
            return BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }

        private fun extFor(mime: String?, uri: Uri): String? {
            when (mime) {
                "image/png" -> return "png"
                "image/webp" -> return "webp"
                "image/gif" -> return "gif"
                "image/jpeg" -> return "jpg"
            }
            val fromName = uri.lastPathSegment?.substringAfterLast('.')?.lowercase()
            return fromName.takeIf { it in EXTS }
        }
    }
}

@Composable
fun StickerThumb(sticker: Sticker, modifier: Modifier = Modifier) {
    val bitmap = remember(sticker.file.path, sticker.file.lastModified()) {
        StickerStore.thumbnail(sticker.file)
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = sticker.file.nameWithoutExtension,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    }
}
