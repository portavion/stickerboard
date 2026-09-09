package com.portavion.stickerboard

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
        val store = StickerStore(root = tmp.newFolder("stickers"), scratch = tmp.newFolder("cache"))
        val a = (store.import(PNG.inputStream()) as ImportResult.Saved).sticker
        val b = (store.import(GIF.inputStream()) as ImportResult.Saved).sticker
        assertNotNull(store.saveLook(a, PNG.inputStream()))

        val zip = tmp.newFile("backup.zip")
        zip.outputStream().use { assertEquals(2, store.export(it)) }
        store.list().forEach(store::delete)
        assertTrue(store.list().isEmpty())

        val result = zip.inputStream().use { store.restore(it) } as RestoreResult.Done
        assertEquals(2, result.written)

        val byStem = store.list().associateBy { it.stem }
        assertEquals(setOf(a.stem, b.stem), byStem.keys)
        assertTrue(byStem.getValue(a.stem).original.file.readBytes().contentEquals(PNG))
        assertTrue(byStem.getValue(b.stem).original.file.readBytes().contentEquals(GIF))
        assertNotNull(byStem.getValue(a.stem).look)
        assertTrue(byStem.getValue(a.stem).look!!.file.readBytes().contentEquals(PNG))
        assertNull(byStem.getValue(b.stem).look)
    }

    @Test
    fun importIntoPopulatedLibraryNeverOverwritesAndMovesLookWithOriginal() {
        val store = StickerStore(root = tmp.newFolder("stickers"), scratch = tmp.newFolder("cache"))
        val a = (store.import(PNG.inputStream()) as ImportResult.Saved).sticker
        val b = (store.import(GIF.inputStream()) as ImportResult.Saved).sticker
        assertNotNull(store.saveLook(a, PNG.inputStream()))
        val pngBytes = a.original.file.readBytes()
        val gifBytes = b.original.file.readBytes()

        val zip = tmp.newFile("backup.zip")
        zip.outputStream().use { assertEquals(2, store.export(it)) }

        val result = zip.inputStream().use { store.restore(it) } as RestoreResult.Done
        assertEquals(2, result.written)
        assertEquals(4, store.list().size)

        val still = store.list().associateBy { it.stem }
        assertTrue(still.getValue(a.stem).original.file.readBytes().contentEquals(pngBytes))
        assertTrue(still.getValue(b.stem).original.file.readBytes().contentEquals(gifBytes))
        assertNotNull(still.getValue(a.stem).look)
        assertNull(still.getValue(b.stem).look)

        val added = store.list().filter { it.stem != a.stem && it.stem != b.stem }
        assertEquals(2, added.size)
        val addedPng = added.single { it.original.format == ImageFormat.PNG }
        val addedGif = added.single { it.original.format == ImageFormat.GIF }
        assertNotNull(addedPng.look)
        assertTrue(addedPng.look!!.file.readBytes().contentEquals(PNG))
        assertNull(addedGif.look)
        assertTrue(addedPng.original.file.readBytes().contentEquals(PNG))
        assertTrue(addedGif.original.file.readBytes().contentEquals(GIF))
    }

    @Test
    fun hostileEntriesAreSkipped() {
        val stickers = tmp.newFolder("stickers")
        val scratch = tmp.newFolder("cache")
        val store = StickerStore(root = stickers, scratch = scratch)
        val zip = tmp.newFile("hostile.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            fun put(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
            put("../escape.png", PNG)
            put("looks/orphan.png", PNG)
            put("notes.txt", "hi".toByteArray())
            put("sub/deep.png", PNG)
        }

        val result = zip.inputStream().use { store.restore(it) } as RestoreResult.Done
        assertEquals(0, result.written)
        assertTrue(store.list().isEmpty())
        assertFalse(File(tmp.root, "escape.png").exists())
        assertFalse(File(scratch, "escape.png").exists())
        assertFalse(File(stickers.parentFile, "escape.png").exists())
        assertEquals(0, stickers.walkTopDown().count { it.isFile })
    }

    companion object {
        private val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            1, 2, 3, 4, 5, 6, 7, 8
        )
        private val GIF = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
            9, 10, 11, 12, 13, 14, 15, 16
        )
    }
}
