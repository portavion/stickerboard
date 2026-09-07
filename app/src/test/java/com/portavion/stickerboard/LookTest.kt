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
        assertEquals(0, cutout.alpha(0))
        assertEquals(0, die.alpha(0))
    }
}
