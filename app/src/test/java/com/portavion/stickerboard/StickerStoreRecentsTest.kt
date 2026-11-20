package com.portavion.stickerboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class StickerStoreRecentsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun newestSendComesFirstAndRepeatsAreNotDuplicated() {
        val store = store()
        val (a, b, c) = List(3) { store.add() }

        store.recordSend(a)
        store.recordSend(b)
        store.recordSend(c)
        store.recordSend(a)

        assertEquals(listOf(a.stem, c.stem, b.stem), store.recents().map { it.stem })
    }

    @Test
    fun keepsOnlyTheLastSends() {
        val store = store()
        val sent = List(StickerStore.RECENT_LIMIT + 3) { store.add() }

        sent.forEach(store::recordSend)

        assertEquals(
            sent.takeLast(StickerStore.RECENT_LIMIT).reversed().map { it.stem },
            store.recents().map { it.stem },
        )
    }

    @Test
    fun deletedStickerDropsOut() {
        val store = store()
        val a = store.add()
        val b = store.add()
        store.recordSend(a)
        store.recordSend(b)

        store.delete(b)

        assertEquals(listOf(a.stem), store.recents().map { it.stem })
    }

    @Test
    fun recentsStayOutOfListExportAndRestore() {
        val store = store()
        val a = store.add()
        val b = store.add()
        store.recordSend(a)

        assertEquals(setOf(a.stem, b.stem), store.list().map { it.stem }.toSet())

        val zip = tmp.newFile("backup.zip")
        zip.outputStream().use { assertEquals(2, store.export(it)) }
        val entries = ZipInputStream(zip.inputStream()).use { input ->
            generateSequence { input.nextEntry }.map { it.name }.toList()
        }
        assertEquals(2, entries.size)
        assertTrue(entries.none { it.startsWith("recents") })

        // A backup carrying someone else's recents must not replace ours.
        val foreign = tmp.newFile("foreign.zip")
        ZipOutputStream(foreign.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("recents.txt"))
            out.write(b.stem.toByteArray())
            out.closeEntry()
        }
        foreign.inputStream().use { store.restore(it) }
        assertEquals(listOf(a.stem), store.recents().map { it.stem })
        assertEquals(2, store.list().size)
    }

    private fun store() =
        StickerStore(root = tmp.newFolder("stickers"), scratch = tmp.newFolder("cache"))

    private fun StickerStore.add(): Sticker =
        (import(PNG.inputStream()) as ImportResult.Saved).sticker

    companion object {
        private val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            1, 2, 3, 4, 5, 6, 7, 8,
        )
    }
}
