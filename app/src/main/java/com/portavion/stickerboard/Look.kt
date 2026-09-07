package com.portavion.stickerboard

import kotlin.math.sqrt

class Raster(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size == width * height)
    }

    fun index(x: Int, y: Int): Int = y * width + x

    fun alpha(i: Int): Int = argb[i] ushr 24

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Raster) return false
        return width == other.width && height == other.height && argb.contentEquals(other.argb)
    }

    override fun hashCode(): Int = 31 * (31 * width + height) + argb.contentHashCode()

    companion object {
        fun filled(width: Int, height: Int, color: Int): Raster =
            Raster(width, height, IntArray(width * height) { color })
    }

    fun withRect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int): Raster {
        val next = argb.copyOf()
        for (y in y0 until y1) {
            val row = y * width
            for (x in x0 until x1) next[row + x] = color
        }
        return Raster(width, height, next)
    }

    fun cropToAlpha(): Raster {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (argb[row + x] ushr 24 != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return Raster(1, 1, intArrayOf(0))
        val w = maxX - minX + 1
        val h = maxY - minY + 1
        if (w == width && h == height) return this
        val next = IntArray(w * h)
        for (y in 0 until h) {
            argb.copyInto(next, y * w, (y + minY) * width + minX, (y + minY) * width + minX + w)
        }
        return Raster(w, h, next)
    }
}

sealed interface Look {
    fun render(cut: Raster): Raster

    data object Cutout : Look {
        override fun render(cut: Raster): Raster = cut.cropToAlpha()
    }

    data object DieCut : Look {
        internal const val BORDER_FRACTION = 0.03f
        internal const val RADIUS_MIN = 6
        internal const val RADIUS_MAX = 24
        internal const val SHADOW_ALPHA = 0.30f

        internal fun borderRadius(width: Int, height: Int): Int =
            kotlin.math.round(BORDER_FRACTION * maxOf(width, height)).toInt()
                .coerceIn(RADIUS_MIN, RADIUS_MAX)

        internal fun shadowDy(radius: Int): Int = radius / 2

        internal fun shadowSoft(radius: Int): Int = radius / 2

        override fun render(cut: Raster): Raster {
            val r = borderRadius(cut.width, cut.height)
            val shadowDy = shadowDy(r)
            val shadowSoft = shadowSoft(r)
            val pad = r + shadowDy + shadowSoft
            val w = cut.width + 2 * pad
            val h = cut.height + 2 * pad
            val canvas = IntArray(w * h)
            for (y in 0 until cut.height) {
                val srcRow = y * cut.width
                val dstRow = (y + pad) * w + pad
                cut.argb.copyInto(canvas, dstRow, srcRow, srcRow + cut.width)
            }
            val mask = BooleanArray(w * h) { i -> (canvas[i] ushr 24) > 127 }
            val dist = euclideanDistanceTransform(mask, w, h)
            val out = IntArray(w * h)
            val rPlus = r + 0.5f
            val shadowReach = (r + shadowSoft).toFloat()
            val soft = shadowSoft.toFloat().coerceAtLeast(1f)
            for (i in out.indices) {
                val y = i / w
                val x = i - y * w
                val sy = y - shadowDy
                val shadowDist = if (sy in 0 until h) dist[sy * w + x] else Float.POSITIVE_INFINITY
                val borderA = (rPlus - dist[i]).coerceIn(0f, 1f)
                val shadowA = SHADOW_ALPHA *
                    ((shadowReach - shadowDist).coerceIn(0f, soft) / soft)
                val shadow = alphaColor(0x000000, shadowA)
                val border = alphaColor(0xFFFFFF, borderA)
                out[i] = srcOver(canvas[i], srcOver(border, shadow))
            }
            return Raster(w, h, out).cropToAlpha()
        }
    }

    companion object {
        val all: List<Look> = listOf(Cutout, DieCut)
    }
}

private fun alphaColor(rgb: Int, alpha: Float): Int {
    val a = (alpha * 255f).toInt().coerceIn(0, 255)
    if (a == 0) return 0
    return (a shl 24) or (rgb and 0x00FFFFFF)
}

private fun srcOver(src: Int, dst: Int): Int {
    val sa = src ushr 24
    if (sa == 0) return dst
    if (sa == 255) return src
    val da = dst ushr 24
    if (da == 0) return src
    val inv = 255 - sa
    val outA = sa + (da * inv + 127) / 255
    if (outA == 0) return 0
    fun ch(shift: Int): Int {
        val sc = (src ushr shift) and 0xFF
        val dc = (dst ushr shift) and 0xFF
        return (sc * sa + dc * da * inv / 255 + outA / 2) / outA
    }
    return (outA shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

private fun euclideanDistanceTransform(mask: BooleanArray, width: Int, height: Int): FloatArray {
    val inf = 1e20
    val n = width * height
    val f = DoubleArray(n) { i -> if (mask[i]) 0.0 else inf }
    val rowOut = DoubleArray(width)
    val colIn = DoubleArray(height)
    val colOut = DoubleArray(height)
    val vRow = IntArray(width)
    val zRow = DoubleArray(width + 1)
    val vCol = IntArray(height)
    val zCol = DoubleArray(height + 1)
    for (y in 0 until height) {
        val row = y * width
        edt1d(f, row, width, rowOut, vRow, zRow, inf)
        for (x in 0 until width) f[row + x] = rowOut[x]
    }
    for (x in 0 until width) {
        for (y in 0 until height) colIn[y] = f[y * width + x]
        edt1d(colIn, 0, height, colOut, vCol, zCol, inf)
        for (y in 0 until height) f[y * width + x] = colOut[y]
    }
    return FloatArray(n) { i -> sqrt(f[i]).toFloat() }
}

private fun edt1d(
    f: DoubleArray,
    offset: Int,
    n: Int,
    out: DoubleArray,
    v: IntArray,
    z: DoubleArray,
    inf: Double,
) {
    var k = 0
    v[0] = 0
    z[0] = -inf
    z[1] = inf
    fun s(q: Int, p: Int): Double {
        val fq = f[offset + q]
        val fp = f[offset + p]
        return ((fq + q.toDouble() * q) - (fp + p.toDouble() * p)) / (2.0 * (q - p))
    }
    for (q in 1 until n) {
        var s = s(q, v[k])
        while (s <= z[k]) {
            k--
            s = s(q, v[k])
        }
        k++
        v[k] = q
        z[k] = s
        z[k + 1] = inf
    }
    k = 0
    for (q in 0 until n) {
        while (z[k + 1] < q) k++
        val dx = (q - v[k]).toDouble()
        out[q] = dx * dx + f[offset + v[k]]
    }
}
