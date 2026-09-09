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

/**
 * Outcome of reading one zip into the library.
 *
 * Done means the zip was read to its end and every root entry with an image extension
 * was attempted. results has one ImportResult per such entry, in the order they were
 * written. Saved carries the sticker exactly as it now exists on disk: under its original
 * stem when that was free, otherwise under a fresh {timestamp}-{n} stem, with look
 * attached when one was restored.
 */
sealed interface RestoreResult {
    data class Done(val results: List<ImportResult>) : RestoreResult {
        val written: Int get() = results.count { it is ImportResult.Saved }
    }
    /** First bytes are not a zip local-file or end-of-central-directory signature. */
    data object NotAZip : RestoreResult
    /** The stream could not be opened or failed mid-read. Library untouched. */
    data object Unreadable : RestoreResult
}

/**
 * A sticker library rooted at one directory.
 *
 * Layout (the only place that knows it is this class):
 *   root/{stem}.{png|webp|gif|jpg}   original
 *   root/looks/{stem}.png            optional look, paired by stem
 *
 * scratch is where restore stages an unzipped copy before merging. It is never read
 * by list() and may be wiped by the OS at any time.
 */
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

    // ---- single-sticker writes ----------------------------------------------------

    /** SAF/content boundary: opens uri, maps SecurityException/IOException to Unreadable. */
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

    /**
     * Body of today's import(uri) after the stream is open: sniff, fresh {ts}-{n} name,
     * .tmp then rename. Shared by import(context, uri), adopt(), and tests.
     */
    internal fun import(input: InputStream): ImportResult = writeOriginal(input, stem = null)

    fun saveLook(sticker: Sticker, pixels: Bitmap): Sticker? =
        writeLook(sticker) { out ->
            if (!pixels.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("compress")
        }

    /** Look from bytes. Sniffs for PNG; anything else returns null and writes nothing. */
    internal fun saveLook(sticker: Sticker, png: InputStream): Sticker? {
        val input = if (png.markSupported()) png else BufferedInputStream(png)
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
            return null
        }
        if (ImageFormat.sniff(header.copyOf(filled.coerceAtLeast(0))) != ImageFormat.PNG) return null
        try {
            input.reset()
        } catch (_: IOException) {
            return null
        }
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

    fun contentUri(context: Context, sticker: Sticker): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sticker.sent.file)

    // ---- whole-library transfer -----------------------------------------------------

    /**
     * Full snapshot of list(): each original at the zip root under its filename, each
     * look at looks/{stem}.png. Entry names are the files' paths relative to root, so
     * the zip layout cannot drift from the disk layout. Entry times are lastModified.
     * Returns the number of stickers written. Throws IOException from the sink.
     */
    internal fun export(out: OutputStream): Int {
        val stickers = list()
        zipFiles(root, stickers.flatMap { listOfNotNull(it.original.file, it.look?.file) }, out)
        return stickers.size
    }

    /** SAF boundary. null when the document could not be opened or written. */
    fun export(context: Context, uri: Uri): Int? =
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { export(it) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    /**
     * Restore a zip made by export (or by hand in the same layout).
     *
     * 1. Sniff 4 bytes: PK\3\4 or PK\5\6 else NotAZip.
     * 2. unzipInto(scratch/restore-{ts}-{n}) — path-escaping entries are dropped there.
     * 3. absorb(StickerStore(staging, scratch)) — pairing, skipping, collision, ordering.
     * 4. Delete staging (also on failure).
     * The library is not touched until step 3, so a crash while reading leaves it as it was.
     */
    internal fun restore(zip: InputStream): RestoreResult {
        val input = if (zip.markSupported()) zip else BufferedInputStream(zip)
        input.mark(4)
        val header = ByteArray(4)
        var filled = 0
        try {
            while (filled < header.size) {
                val nread = input.read(header, filled, header.size - filled)
                if (nread <= 0) break
                filled += nread
            }
        } catch (_: IOException) {
            return RestoreResult.Unreadable
        }
        // ZipInputStream treats a non-zip as an empty archive instead of throwing.
        if (filled < 4 || !isZipMagic(header)) return RestoreResult.NotAZip
        try {
            input.reset()
        } catch (_: IOException) {
            return RestoreResult.Unreadable
        }

        val staging = File(scratch, "restore-${System.currentTimeMillis()}-$n")
        return try {
            unzipInto(input, staging)
            RestoreResult.Done(absorb(StickerStore(staging, scratch)))
        } catch (_: IOException) {
            RestoreResult.Unreadable
        } finally {
            staging.deleteRecursively()
        }
    }

    /** SAF boundary: opens uri, maps open/read failures to Unreadable. */
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

    /**
     * Adopt every sticker of another library into this one. Never overwrites.
     *
     * Invariants:
     *  - other.list() has already resolved pairing: a look without an original is invisible,
     *    a root file without an image extension is invisible. Nothing here re-derives that.
     *  - A stem is "taken" if any file in root or root/looks has that nameWithoutExtension,
     *    so 123-0.jpg cannot land next to an existing 123-0.png and steal looks/123-0.png.
     *  - The taken set is built once and grows as stickers are adopted; no per-sticker scan.
     *  - Oldest first (other.list() is newest-first, so iterate reversed) so that relative
     *    order survives the round trip; adopted files also take the source lastModified.
     */
    internal fun absorb(other: StickerStore): List<ImportResult> {
        val taken = stemsOnDisk()
        return other.list().asReversed().map { s ->
            val stem = if (s.stem in taken) freshStem(taken) else s.stem
            taken += stem
            adopt(s, stem)
        }
    }

    /** One pair. Original first (sniffed, .tmp+rename); look second, or dropped if it fails. */
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

    /** nameWithoutExtension of every regular file in root and root/looks. */
    private fun stemsOnDisk(): MutableSet<String> {
        val stems = mutableSetOf<String>()
        dir.listFiles()?.forEach { if (it.isFile) stems += it.nameWithoutExtension }
        looksDir.listFiles()?.forEach { if (it.isFile) stems += it.nameWithoutExtension }
        return stems
    }

    /** {currentTimeMillis}-{n}, re-rolled while it is in taken. Same allocator as today. */
    private fun freshStem(taken: Set<String>): String {
        var stem: String
        do {
            stem = "${System.currentTimeMillis()}-$n"
        } while (stem in taken || !free(stem))
        return stem
    }

    /**
     * The one writer of originals. stem == null means allocate. Sniffs the header for
     * format (that decides the extension, not the source name), writes root/{stem}.{ext}
     * via .tmp then rename. This is today's import(uri) body with the name lifted out.
     */
    private fun writeOriginal(input: InputStream, stem: String?): ImportResult {
        val peek = if (input.markSupported()) input else BufferedInputStream(input)
        peek.mark(12)
        val header = ByteArray(12)
        var filled = 0
        try {
            while (filled < header.size) {
                val nread = peek.read(header, filled, header.size - filled)
                if (nread <= 0) break
                filled += nread
            }
        } catch (_: IOException) {
            return ImportResult.Unreadable
        }
        if (filled <= 0) return ImportResult.NotAnImage
        val format = ImageFormat.sniff(header.copyOf(filled))
            ?: return ImportResult.NotAnImage
        try {
            peek.reset()
        } catch (_: IOException) {
            return ImportResult.Unreadable
        }
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
            tmp.outputStream().use { peek.copyTo(it) }
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

    /** The one writer of looks: root/looks/{stem}.png via .tmp then rename, then sweepTmp. */
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

        private fun isZipMagic(header: ByteArray): Boolean {
            if (header.size < 4) return false
            if (header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) return false
            val third = header[2]
            val fourth = header[3]
            return (third == 3.toByte() && fourth == 4.toByte()) ||
                (third == 5.toByte() && fourth == 6.toByte())
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
