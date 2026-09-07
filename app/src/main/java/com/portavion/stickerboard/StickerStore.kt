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
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException

enum class ImageFormat(val ext: String, val mime: String) {
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
    GIF("gif", "image/gif"),
    JPEG("jpg", "image/jpeg");

    companion object {
        fun sniff(header: ByteArray): ImageFormat? = when {
            header.startsMagic(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
            header.startsMagic(0, 0x47, 0x49, 0x46, 0x38) -> GIF
            header.startsMagic(0, 0xFF, 0xD8, 0xFF) -> JPEG
            header.startsMagic(0, 0x52, 0x49, 0x46, 0x46) &&
                header.startsMagic(8, 0x57, 0x45, 0x42, 0x50) -> WEBP
            else -> null
        }

        fun fromExtension(ext: String): ImageFormat? = when (ext.lowercase()) {
            "png" -> PNG
            "webp" -> WEBP
            "gif" -> GIF
            "jpg", "jpeg" -> JPEG
            else -> null
        }
    }
}

private fun ByteArray.startsMagic(offset: Int, vararg bytes: Int): Boolean {
    if (size < offset + bytes.size) return false
    return bytes.indices.all { this[offset + it] == bytes[it].toByte() }
}

data class Sticker(val file: File, val format: ImageFormat)

sealed interface ImportResult {
    data class Saved(val sticker: Sticker) : ImportResult
    data object NotAnImage : ImportResult
    data object Unreadable : ImportResult
}

class StickerStore(private val context: Context) {
    private val dir: File
        get() = File(context.filesDir, "stickers").also { it.mkdirs() }

    fun list(): List<Sticker> =
        dir.listFiles()
            ?.filter { it.isFile }
            ?.mapNotNull { file ->
                ImageFormat.fromExtension(file.extension)?.let { Sticker(file, it) }
            }
            ?.sortedByDescending { it.file.lastModified() }
            ?: emptyList()

    fun import(uri: Uri): ImportResult {
        val raw = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: SecurityException) {
            return ImportResult.Unreadable
        } catch (_: IOException) {
            return ImportResult.Unreadable
        } ?: return ImportResult.Unreadable

        return BufferedInputStream(raw).use { input ->
            input.mark(12)
            val header = ByteArray(12)
            var filled = 0
            try {
                while (filled < header.size) {
                    val nread = input.read(header, filled, header.size - filled)
                    if (nread <= 0) break
                    filled += nread
                }
            } catch (_: IOException) {
                return@use ImportResult.Unreadable
            }
            if (filled <= 0) return@use ImportResult.NotAnImage
            val format = ImageFormat.sniff(header.copyOf(filled))
                ?: return@use ImportResult.NotAnImage
            try {
                input.reset()
            } catch (_: IOException) {
                return@use ImportResult.Unreadable
            }
            val dest = File(dir, "${System.currentTimeMillis()}-$n.${format.ext}")
            val tmp = File(dir, "${dest.name}.tmp")
            try {
                tmp.outputStream().use { input.copyTo(it) }
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    dest.delete()
                    return@use ImportResult.Unreadable
                }
                ImportResult.Saved(Sticker(dest, format))
            } catch (_: IOException) {
                tmp.delete()
                dest.delete()
                ImportResult.Unreadable
            }
        }
    }

    fun delete(sticker: Sticker) {
        sticker.file.delete()
    }

    fun contentUri(sticker: Sticker): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sticker.file)

    companion object {
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
