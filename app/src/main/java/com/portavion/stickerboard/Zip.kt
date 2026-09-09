package com.portavion.stickerboard

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Write files into a zip. Entry name is each file's path relative to root with '/'
 * separators; entry time is lastModified. Files that vanish between listing and
 * writing are skipped. Returns entries written. Throws IOException from out.
 */
internal fun zipFiles(root: File, files: List<File>, out: OutputStream): Int {
    var written = 0
    ZipOutputStream(out).apply { setLevel(Deflater.NO_COMPRESSION) }.use { zip ->
        for (file in files) {
            if (!file.isFile) continue
            val entry = ZipEntry(file.relativeTo(root).invariantSeparatorsPath)
            val modified = file.lastModified()
            if (modified >= 0) entry.time = modified
            zip.putNextEntry(entry)
            try {
                file.inputStream().use { it.copyTo(zip) }
            } catch (_: FileNotFoundException) {
                zip.closeEntry()
                continue
            }
            zip.closeEntry()
            written++
        }
    }
    return written
}

/**
 * Extract a zip stream into dir. Directory entries are skipped. An entry whose
 * canonical destination is not inside dir is skipped (path traversal). Written files
 * take the entry time as lastModified when it is set. Returns files written.
 * Throws IOException / ZipException from the stream.
 */
internal fun unzipInto(zip: InputStream, dir: File): Int {
    dir.mkdirs()
    val canonicalDir = dir.canonicalFile
    var written = 0
    ZipInputStream(zip).use { zin ->
        while (true) {
            val entry = zin.nextEntry ?: break
            if (entry.isDirectory) continue
            val dest = File(dir, entry.name).canonicalFile
            if (!dest.path.startsWith(canonicalDir.path + File.separator)) continue
            dest.parentFile?.mkdirs()
            dest.outputStream().use { zin.copyTo(it) }
            if (entry.time >= 0) dest.setLastModified(entry.time)
            written++
            zin.closeEntry()
        }
    }
    return written
}
