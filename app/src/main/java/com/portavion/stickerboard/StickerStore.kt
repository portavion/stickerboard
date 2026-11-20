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
import java.io.InputStream
import java.io.OutputStream

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

private fun peek(input: InputStream, header: ByteArray): Pair<InputStream, Int>? {
    val source = if (input.markSupported()) input else BufferedInputStream(input)
    return try {
        source.mark(header.size)
        var filled = 0
        while (filled < header.size) {
            val nread = source.read(header, filled, header.size - filled)
            if (nread <= 0) break
            filled += nread
        }
        source.reset()
        source to filled
    } catch (_: IOException) {
        null
    }
}

private fun isZipMagic(header: ByteArray): Boolean =
    header.startsMagic(0, 0x50, 0x4B, 0x03, 0x04) ||
        header.startsMagic(0, 0x50, 0x4B, 0x05, 0x06)

data class ImageFile(val file: File, val format: ImageFormat)

data class Sticker(val original: ImageFile, val look: ImageFile?) {
    val sent: ImageFile get() = look ?: original
    val stem: String get() = original.file.nameWithoutExtension
    val editable: Boolean get() = original.format != ImageFormat.GIF
}

sealed interface ImportResult {
    data class Saved(val sticker: Sticker) : ImportResult
    data object NotAnImage : ImportResult
    data object Unreadable : ImportResult
}

sealed interface RestoreResult {
    data class Done(val results: List<ImportResult>) : RestoreResult {
        val written: Int get() = results.count { it is ImportResult.Saved }
    }
    data object NotAZip : RestoreResult
    data object Unreadable : RestoreResult
}

class StickerStore(private val root: File, private val scratch: File) {

    constructor(context: Context) : this(File(context.filesDir, "stickers"), context.cacheDir)

    private val dir: File get() = root.also { it.mkdirs() }
    private val looksDir: File get() = File(dir, "looks").also { it.mkdirs() }

    fun list(): List<Sticker> =
        dir.listFiles()
            ?.filter { it.isFile }
            ?.mapNotNull { file ->
                ImageFormat.fromExtension(file.extension)?.let { ImageFile(file, it) }
            }
            ?.sortedByDescending { it.file.lastModified() }
            ?.map { original ->
                val lookFile = File(looksDir, "${original.file.nameWithoutExtension}.png")
                val look = if (lookFile.isFile) ImageFile(lookFile, ImageFormat.PNG) else null
                Sticker(original, look)
            }
            ?: emptyList()

    fun import(context: Context, uri: Uri): ImportResult {
        val raw = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: SecurityException) {
            return ImportResult.Unreadable
        } catch (_: IOException) {
            return ImportResult.Unreadable
        } ?: return ImportResult.Unreadable

        return raw.use { import(it) }
    }

    internal fun import(input: InputStream): ImportResult = writeOriginal(input, stem = null)

    fun saveLook(sticker: Sticker, pixels: Bitmap): Sticker? =
        writeLook(sticker) { out ->
            if (!pixels.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("compress")
        }

    internal fun saveLook(sticker: Sticker, png: InputStream): Sticker? {
        val header = ByteArray(12)
        val (input, filled) = peek(png, header) ?: return null
        if (ImageFormat.sniff(header.copyOf(filled)) != ImageFormat.PNG) return null
        return writeLook(sticker) { input.copyTo(it) }
    }

    fun clearLook(sticker: Sticker): Sticker {
        File(looksDir, "${sticker.stem}.png").delete()
        return Sticker(sticker.original, look = null)
    }

    fun delete(sticker: Sticker) {
        File(looksDir, "${sticker.stem}.png").delete()
        sticker.original.file.delete()
        sweepTmp(sticker.stem)
    }

    /**
     * Stickers the keyboard sent, most recent first. Stems whose sticker was deleted drop out.
     */
    fun recents(): List<Sticker> {
        val byStem = list().associateBy { it.stem }
        return readRecentStems().mapNotNull { byStem[it] }
    }

    /**
     * Moves [sticker] to the front of [recents], keeping at most [RECENT_LIMIT] stems.
     */
    fun recordSend(sticker: Sticker) {
        val stems = (listOf(sticker.stem) + readRecentStems().filter { it != sticker.stem })
            .take(RECENT_LIMIT)
        val file = File(dir, RECENTS_FILE)
        val tmp = File(dir, "$RECENTS_FILE.tmp")
        try {
            tmp.writeText(stems.joinToString("\n"))
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (_: IOException) {
            // Recents are a convenience; a failed write must never block a send.
            tmp.delete()
        }
    }

    // Not an image extension, so list(), export and restore never see this file.
    private fun readRecentStems(): List<String> =
        try {
            File(dir, RECENTS_FILE).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }
        } catch (_: IOException) {
            null
        } ?: emptyList()

    fun contentUri(context: Context, sticker: Sticker): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sticker.sent.file)

    internal fun export(out: OutputStream): Int {
        val stickers = list()
        zipFiles(root, stickers.flatMap { listOfNotNull(it.original.file, it.look?.file) }, out)
        return stickers.size
    }

    fun export(context: Context, uri: Uri): Int? =
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { export(it) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    internal fun restore(zip: InputStream): RestoreResult {
        val header = ByteArray(4)
        val (input, filled) = peek(zip, header) ?: return RestoreResult.Unreadable
        // ZipInputStream treats a non-zip as an empty archive instead of throwing.
        if (filled < 4 || !isZipMagic(header)) return RestoreResult.NotAZip

        val staging = File(scratch, "restore-${System.currentTimeMillis()}")
        return try {
            unzipInto(input, staging)
            RestoreResult.Done(absorb(StickerStore(staging, scratch)))
        } catch (_: IOException) {
            RestoreResult.Unreadable
        } finally {
            staging.deleteRecursively()
        }
    }

    fun restore(context: Context, uri: Uri): RestoreResult {
        val raw = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: SecurityException) {
            return RestoreResult.Unreadable
        } catch (_: IOException) {
            return RestoreResult.Unreadable
        } ?: return RestoreResult.Unreadable
        return try {
            raw.use { restore(it) }
        } catch (_: IOException) {
            RestoreResult.Unreadable
        }
    }

    internal fun absorb(other: StickerStore): List<ImportResult> {
        val taken = stemsOnDisk()
        return other.list().asReversed().map { s ->
            val stem = if (s.stem in taken || !free(s.stem)) freshStem(taken) else s.stem
            taken += stem
            adopt(s, stem)
        }
    }

    private fun adopt(source: Sticker, stem: String): ImportResult {
        val r = source.original.file.inputStream().use { writeOriginal(it, stem) }
        if (r !is ImportResult.Saved) return r
        r.sticker.original.file.setLastModified(source.original.file.lastModified())
        val look = source.look?.let { l ->
            l.file.inputStream().use { saveLook(r.sticker, it) }?.also { saved ->
                saved.look?.file?.setLastModified(l.file.lastModified())
            }
        }
        return ImportResult.Saved(look ?: r.sticker)
    }

    private fun stemsOnDisk(): MutableSet<String> {
        val stems = mutableSetOf<String>()
        dir.listFiles()?.forEach { if (it.isFile) stems += it.nameWithoutExtension }
        looksDir.listFiles()?.forEach { if (it.isFile) stems += it.nameWithoutExtension }
        return stems
    }

    private fun freshStem(taken: Set<String>): String {
        var stem: String
        do {
            stem = "${System.currentTimeMillis()}-$n"
        } while (stem in taken || !free(stem))
        return stem
    }

    private fun writeOriginal(input: InputStream, stem: String?): ImportResult {
        val header = ByteArray(12)
        val (peeked, filled) = peek(input, header) ?: return ImportResult.Unreadable
        if (filled <= 0) return ImportResult.NotAnImage
        val format = ImageFormat.sniff(header.copyOf(filled))
            ?: return ImportResult.NotAnImage
        val chosen = stem ?: run {
            var s: String
            do {
                s = "${System.currentTimeMillis()}-$n"
            } while (!free(s))
            s
        }
        val dest = File(dir, "$chosen.${format.ext}")
        val tmp = File(dir, "${dest.name}.tmp")
        return try {
            tmp.outputStream().use { peeked.copyTo(it) }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                dest.delete()
                return ImportResult.Unreadable
            }
            ImportResult.Saved(Sticker(ImageFile(dest, format), look = null))
        } catch (_: IOException) {
            tmp.delete()
            dest.delete()
            ImportResult.Unreadable
        }
    }

    private fun writeLook(sticker: Sticker, write: (OutputStream) -> Unit): Sticker? {
        if (!sticker.original.file.isFile) return null
        val dest = File(looksDir, "${sticker.stem}.png")
        val tmp = File(looksDir, "${dest.name}.tmp")
        return try {
            tmp.outputStream().use { write(it) }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return null
            }
            sweepTmp(sticker.stem)
            Sticker(sticker.original, ImageFile(dest, ImageFormat.PNG))
        } catch (_: IOException) {
            tmp.delete()
            null
        }
    }

    private fun free(stem: String): Boolean =
        !File(dir, "$stem.png").exists() &&
            !File(dir, "$stem.webp").exists() &&
            !File(dir, "$stem.gif").exists() &&
            !File(dir, "$stem.jpg").exists() &&
            !File(looksDir, "$stem.png").exists()

    private fun sweepTmp(stem: String) {
        sweepTmpIn(dir, stem)
        sweepTmpIn(looksDir, stem)
    }

    private fun sweepTmpIn(folder: File, stem: String) {
        folder.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(stem) && file.name.endsWith(".tmp")) {
                file.delete()
            }
        }
    }

    companion object {
        /** How many stems [recordSend] keeps. */
        const val RECENT_LIMIT = 8
        private const val RECENTS_FILE = "recents.txt"

        private var n = 0
            get() = field++

        fun decode(image: ImageFile, maxPx: Int = 256): Bitmap? {
            val path = image.file.absolutePath
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val w = bounds.outWidth.coerceAtLeast(1)
            val h = bounds.outHeight.coerceAtLeast(1)
            var sample = 1
            while (w / sample > maxPx || h / sample > maxPx) sample *= 2
            return BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }
    }
}

@Composable
fun StickerThumb(sticker: Sticker, modifier: Modifier = Modifier) {
    val bitmap = remember(sticker.sent.file.path, sticker.sent.file.lastModified()) {
        StickerStore.decode(sticker.sent)
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = sticker.stem,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    }
}
