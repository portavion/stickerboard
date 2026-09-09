package com.portavion.stickerboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StickerStoreZipTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun exportDeleteImportKeepsPairs() {
        val store = store()
        val a = (store.import(PNG.inputStream()) as ImportResult.Saved).sticker
        val b = (store.import(GIF.inputStream()) as ImportResult.Saved).sticker
        assertNotNull(store.saveLook(a, LOOK.inputStream()))

        val zip = tmp.newFile("backup.zip")
        zip.outputStream().use { assertEquals(2, store.export(it)) }
        store.list().forEach(store::delete)
        assertTrue(store.list().isEmpty())

        val result = zip.inputStream().use { store.restore(it) } as RestoreResult.Done
        assertEquals(2, result.written)

        val byStem = store.list().associateBy { it.stem }
        assertEquals(setOf(a.stem, b.stem), byStem.keys)
        assertArrayEquals(PNG, byStem.getValue(a.stem).original.file.readBytes())
        assertArrayEquals(GIF, byStem.getValue(b.stem).original.file.readBytes())
        assertArrayEquals(LOOK, byStem.getValue(a.stem).look!!.file.readBytes())
        assertNull(byStem.getValue(b.stem).look)
    }

    @Test
    fun importIntoPopulatedLibraryNeverOverwritesAndMovesLookWithOriginal() {
        val store = store()
        val a = (store.import(PNG.inputStream()) as ImportResult.Saved).sticker
        val b = (store.import(GIF.inputStream()) as ImportResult.Saved).sticker
        assertNotNull(store.saveLook(a, LOOK.inputStream()))
        val pngBytes = a.original.file.readBytes()
        val gifBytes = b.original.file.readBytes()

        val zip = tmp.newFile("backup.zip")
        zip.outputStream().use { assertEquals(2, store.export(it)) }

        val result = zip.inputStream().use { store.restore(it) } as RestoreResult.Done
        assertEquals(2, result.written)
        assertEquals(4, store.list().size)

        val still = store.list().associateBy { it.stem }
        assertArrayEquals(pngBytes, still.getValue(a.stem).original.file.readBytes())
        assertArrayEquals(gifBytes, still.getValue(b.stem).original.file.readBytes())
        assertNotNull(still.getValue(a.stem).look)
        assertNull(still.getValue(b.stem).look)

        val added = store.list().filter { it.stem != a.stem && it.stem != b.stem }
        assertEquals(2, added.size)
        val addedPng = added.single { it.original.format == ImageFormat.PNG }
        val addedGif = added.single { it.original.format == ImageFormat.GIF }
        assertNotNull(addedPng.look)
        assertArrayEquals(LOOK, addedPng.look!!.file.readBytes())
        assertNull(addedGif.look)
        assertArrayEquals(PNG, addedPng.original.file.readBytes())
        assertArrayEquals(GIF, addedGif.original.file.readBytes())
    }

    @Test
    fun anotherExtensionCannotStealAnExistingStemsLook() {
        val store = store()
        val paired = zipOf("paired.zip", "s.png" to PNG, "looks/s.png" to LOOK)
        assertEquals(1, restore(store, paired).written)

        val rival = zipOf("rival.zip", "s.jpg" to JPEG)
        assertEquals(1, restore(store, rival).written)

        val stickers = store.list()
        assertEquals(2, stickers.size)
        val kept = stickers.single { it.stem == "s" }
        assertArrayEquals(PNG, kept.original.file.readBytes())
        assertArrayEquals(LOOK, kept.look!!.file.readBytes())
        assertNull(stickers.single { it.original.format == ImageFormat.JPEG }.look)
    }

    @Test
    fun hostileEntriesAreSkipped() {
        val stickers = tmp.newFolder("stickers")
        val scratch = tmp.newFolder("cache")
        val store = StickerStore(root = stickers, scratch = scratch)
        val zip = zipOf(
            "hostile.zip",
            "../escape.png" to PNG,
            "looks/orphan.png" to LOOK,
            "notes.txt" to "hi".toByteArray(),
            "sub/deep.png" to PNG,
        )

        val result = restore(store, zip)
        assertEquals(0, result.written)
        assertTrue(store.list().isEmpty())
        assertEquals(listOf(zip), tmp.root.walkTopDown().filter { it.isFile }.toList())
        assertFalse(File(stickers.parentFile, "escape.png").exists())
    }

    @Test
    fun nonZipBytesAreRejectedWithoutTouchingTheLibrary() {
        val store = store()
        val png = (store.import(PNG.inputStream()) as ImportResult.Saved).sticker
        assertEquals(RestoreResult.NotAZip, PNG.inputStream().use { store.restore(it) })
        assertEquals(listOf(png.stem), store.list().map { it.stem })
    }

    private fun store() =
        StickerStore(root = tmp.newFolder("stickers"), scratch = tmp.newFolder("cache"))

    private fun restore(store: StickerStore, zip: File): RestoreResult.Done =
        zip.inputStream().use { store.restore(it) } as RestoreResult.Done

    private fun zipOf(name: String, vararg entries: Pair<String, ByteArray>): File =
        tmp.newFile(name).also { zip ->
            ZipOutputStream(zip.outputStream()).use { out ->
                entries.forEach { (entry, bytes) ->
                    out.putNextEntry(ZipEntry(entry))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }

    companion object {
        private val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            1, 2, 3, 4, 5, 6, 7, 8,
        )
        private val LOOK = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            9, 10, 11, 12, 13, 14, 15, 16,
        )
        private val GIF = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
            9, 10, 11, 12, 13, 14, 15, 16,
        )
        private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    }
}
