package com.portavion.stickerboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    @Test
    fun dieCutAddsWhiteBorderAroundOpaqueSquare() {
        val cut = Raster.filled(64, 64, 0x00000000).withRect(16, 16, 48, 48, 0xFF2020FF.toInt())
        val out = Look.DieCut.render(cut)
        assertTrue(out.width > 32 && out.width < 64)
        val r = Look.DieCut.borderRadius(cut.width, cut.height)
        val y = 1 + Look.DieCut.shadowDy(r)
        assertEquals(0xFFFFFFFF.toInt(), out.argb[out.index(out.width / 2, y)])
    }

    @Test
    fun cutoutIsIdempotent() {
        val cut = Raster.filled(64, 64, 0x00000000).withRect(16, 16, 48, 48, 0xFF2020FF.toInt())
        val once = Look.Cutout.render(cut)
        assertEquals(once, Look.Cutout.render(once))
        assertEquals(32, once.width)
        assertEquals(32, once.height)
    }

    @Test
    fun emptyRasterDoesNotCrash() {
        val empty = Raster.filled(8, 8, 0)
        val cutout = Look.Cutout.render(empty)
        val die = Look.DieCut.render(empty)
        val pixel = Look.PixelArt.render(empty)
        assertEquals(0, cutout.alpha(0))
        assertEquals(0, die.alpha(0))
        assertEquals(1, pixel.width)
        assertEquals(0, pixel.alpha(0))
    }

    @Test
    fun pixelArtIgnoresHostileTransparentRgb() {
        val cut = Raster.filled(64, 64, 0xFF2040C0.toInt())
            .withRect(16, 16, 48, 48, 0x00FF0000)
        val out = Look.PixelArt.render(cut)
        val cell = Look.PixelArt.cellSize(64, 64)
        val opaque = 0xFF2040C0.toInt()
        assertEquals(0, out.width % cell)
        for (p in out.argb) {
            val a = p ushr 24
            assertTrue(a == 0 || a == 255)
            if (a == 255) assertEquals(opaque, p)
            assertTrue(a == 0 || (p and 0x00FFFFFF) != 0x00FF0000)
        }
    }

    @Test
    fun pixelArtKeepsFlatRed() {
        val cut = Raster.filled(64, 64, 0xFFFF0000.toInt())
        val out = Look.PixelArt.render(cut)
        val red = 0xFFFF0000.toInt()
        for (p in out.argb) assertEquals(red, p)
    }

    @Test
    fun pixelArtQuantizesNoisyInputToAtMost16Colors() {
        val argb = IntArray(64 * 64) { i ->
            val r = (i * 17) and 0xFF
            val g = (i * 31) and 0xFF
            val b = (i * 47) and 0xFF
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        val out = Look.PixelArt.render(Raster(64, 64, argb))
        val colors = out.argb.filter { it ushr 24 == 255 }.toSet()
        assertTrue(colors.isNotEmpty())
        assertTrue(colors.size <= 16)
    }

    @Test
    fun pixelArtCellSizeFollowsCroppedSubject() {
        val cut = Raster.filled(256, 256, 0).withRect(20, 20, 30, 30, 0xFFFF0000.toInt())
        val cropped = cut.cropToAlpha()
        val fromCrop = Look.PixelArt.cellSize(cropped.width, cropped.height)
        val fromFrame = Look.PixelArt.cellSize(cut.width, cut.height)
        assertTrue(fromCrop < fromFrame)
        val out = Look.PixelArt.render(cut)
        assertEquals(0, out.width % fromCrop)
        assertTrue(out.width % fromFrame != 0)
    }

    @Test
    fun pixelArtOutputCellsAreUniformBlocks() {
        val cut = Raster.filled(64, 64, 0xFFFF0000.toInt())
            .withRect(0, 0, 32, 32, 0xFF00FF00.toInt())
        val source = cut.cropToAlpha()
        val cell = Look.PixelArt.cellSize(source.width, source.height)
        val out = Look.PixelArt.render(cut)
        var y = 0
        while (y + cell <= out.height) {
            var x = 0
            while (x + cell <= out.width) {
                val color = out.argb[out.index(x, y)]
                for (dy in 0 until cell) {
                    val row = (y + dy) * out.width
                    for (dx in 0 until cell) {
                        assertEquals(color, out.argb[row + x + dx])
                    }
                }
                x += cell
            }
            y += cell
        }
    }
}
