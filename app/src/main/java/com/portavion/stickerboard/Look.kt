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

    data object PixelArt : Look {
        internal const val LONG_CELLS = 32
        internal const val PALETTE_SIZE = 16
        internal const val ALPHA_OPAQUE = 128

        internal fun cellSize(width: Int, height: Int): Int =
            maxOf(1, (maxOf(width, height) + LONG_CELLS - 1) / LONG_CELLS)

        override fun render(cut: Raster): Raster {
            val source = cut.cropToAlpha()
            if (source.width == 1 && source.height == 1 && source.argb[0] == 0) return source
            val sprite = indexThenMajority(source) ?: return Raster.filled(1, 1, 0)
            return sprite.upsample().cropToAlpha()
        }
    }

    companion object {
        val all: List<Look> = listOf(Cutout, DieCut, PixelArt)
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

private class Sprite(
    val cols: Int,
    val rows: Int,
    val cellSize: Int,
    val index: ByteArray,
    val palette: IntArray,
) {
    init {
        require(cols > 0 && rows > 0 && cellSize > 0)
        require(index.size == cols * rows)
        require(palette.size in 1..Look.PixelArt.PALETTE_SIZE)
        require(palette.all { it ushr 24 == 255 })
        require(index.all { it.toInt() in 0..palette.size })
    }

    fun upsample(): Raster {
        val stride = cols * cellSize
        val out = IntArray(stride * rows * cellSize)
        val scanline = IntArray(stride)
        for (cy in 0 until rows) {
            for (cx in 0 until cols) {
                val idx = index[cy * cols + cx].toInt()
                val color = if (idx == 0) 0 else palette[idx - 1]
                scanline.fill(color, cx * cellSize, cx * cellSize + cellSize)
            }
            val row0 = cy * cellSize
            for (k in 0 until cellSize) {
                scanline.copyInto(out, (row0 + k) * stride)
            }
        }
        return Raster(stride, rows * cellSize, out)
    }
}

private fun indexThenMajority(source: Raster): Sprite? {
    val cell = Look.PixelArt.cellSize(source.width, source.height)
    val cols = ceilDiv(source.width, cell)
    val rows = ceilDiv(source.height, cell)
    val hist = LongArray(32768)
    fillRgb555Histogram(source, hist)
    if (hist.none { it != 0L }) return null
    val boxes = medianCut(hist, Look.PixelArt.PALETTE_SIZE)
    val lut = binToPaletteIndex(hist, boxes)
    val palette = paletteFromSource(source, lut, boxes.size)
    val index = ByteArray(cols * rows)
    var anyOpaque = false
    for (cy in 0 until rows) {
        val row = cy * cols
        for (cx in 0 until cols) {
            val winner = majorityIndex(source, cx, cy, cell, lut)
            index[row + cx] = winner
            if (winner != 0.toByte()) anyOpaque = true
        }
    }
    if (!anyOpaque) return null
    return Sprite(cols, rows, cell, index, palette)
}

private fun fillRgb555Histogram(source: Raster, hist: LongArray) {
    for (p in source.argb) {
        if ((p ushr 24) < Look.PixelArt.ALPHA_OPAQUE) continue
        hist[rgb555(p)]++
    }
}

private class ColorBox(
    val r0: Int,
    val r1: Int,
    val g0: Int,
    val g1: Int,
    val b0: Int,
    val b1: Int,
)

private fun medianCut(hist: LongArray, maxColors: Int): List<ColorBox> {
    val origin = occupiedBounds(hist) ?: return emptyList()
    val boxes = ArrayList<ColorBox>(maxColors)
    boxes.add(origin)
    while (boxes.size < maxColors) {
        var best = -1
        var bestRange = 0
        var bestChannel = 3
        for (i in boxes.indices) {
            val (channel, range) = widestChannel(boxes[i], hist)
            if (range == 0) continue
            if (best < 0 || range > bestRange || (range == bestRange && channel < bestChannel)) {
                best = i
                bestRange = range
                bestChannel = channel
            }
        }
        if (best < 0) break
        val (low, high) = splitBox(boxes[best], hist, bestChannel)
        boxes[best] = low
        boxes.add(high)
    }
    return boxes
}

private fun occupiedBounds(hist: LongArray): ColorBox? {
    var r0 = 32
    var r1 = -1
    var g0 = 32
    var g1 = -1
    var b0 = 32
    var b1 = -1
    for (r in 0..31) {
        val rsh = r shl 10
        for (g in 0..31) {
            val gsh = rsh or (g shl 5)
            for (b in 0..31) {
                if (hist[gsh or b] == 0L) continue
                if (r < r0) r0 = r
                if (r > r1) r1 = r
                if (g < g0) g0 = g
                if (g > g1) g1 = g
                if (b < b0) b0 = b
                if (b > b1) b1 = b
            }
        }
    }
    if (r1 < 0) return null
    return ColorBox(r0, r1, g0, g1, b0, b1)
}

private fun widestChannel(box: ColorBox, hist: LongArray): Pair<Int, Int> {
    var rMin = 32
    var rMax = -1
    var gMin = 32
    var gMax = -1
    var bMin = 32
    var bMax = -1
    for (r in box.r0..box.r1) {
        val rsh = r shl 10
        for (g in box.g0..box.g1) {
            val gsh = rsh or (g shl 5)
            for (b in box.b0..box.b1) {
                if (hist[gsh or b] == 0L) continue
                if (r < rMin) rMin = r
                if (r > rMax) rMax = r
                if (g < gMin) gMin = g
                if (g > gMax) gMax = g
                if (b < bMin) bMin = b
                if (b > bMax) bMax = b
            }
        }
    }
    if (rMax < 0) return 0 to 0
    val rr = rMax - rMin
    val gr = gMax - gMin
    val br = bMax - bMin
    return when {
        rr >= gr && rr >= br -> 0 to rr
        gr >= br -> 1 to gr
        else -> 2 to br
    }
}

private fun splitBox(box: ColorBox, hist: LongArray, channel: Int): Pair<ColorBox, ColorBox> {
    val plane = LongArray(32)
    var total = 0L
    for (r in box.r0..box.r1) {
        val rsh = r shl 10
        for (g in box.g0..box.g1) {
            val gsh = rsh or (g shl 5)
            for (b in box.b0..box.b1) {
                val w = hist[gsh or b]
                if (w == 0L) continue
                val p = when (channel) {
                    0 -> r
                    1 -> g
                    else -> b
                }
                plane[p] += w
                total += w
            }
        }
    }
    val lo = when (channel) {
        0 -> box.r0
        1 -> box.g0
        else -> box.b0
    }
    val hi = when (channel) {
        0 -> box.r1
        1 -> box.g1
        else -> box.b1
    }
    val half = total / 2
    var cum = 0L
    var cut = lo
    for (p in lo until hi) {
        cum += plane[p]
        cut = p
        if (cum >= half) break
    }
    val low = tighten(
        hist,
        when (channel) {
            0 -> ColorBox(box.r0, cut, box.g0, box.g1, box.b0, box.b1)
            1 -> ColorBox(box.r0, box.r1, box.g0, cut, box.b0, box.b1)
            else -> ColorBox(box.r0, box.r1, box.g0, box.g1, box.b0, cut)
        },
    )
    val high = tighten(
        hist,
        when (channel) {
            0 -> ColorBox(cut + 1, box.r1, box.g0, box.g1, box.b0, box.b1)
            1 -> ColorBox(box.r0, box.r1, cut + 1, box.g1, box.b0, box.b1)
            else -> ColorBox(box.r0, box.r1, box.g0, box.g1, cut + 1, box.b1)
        },
    )
    return low to high
}

private fun tighten(hist: LongArray, box: ColorBox): ColorBox {
    var r0 = 32
    var r1 = -1
    var g0 = 32
    var g1 = -1
    var b0 = 32
    var b1 = -1
    for (r in box.r0..box.r1) {
        val rsh = r shl 10
        for (g in box.g0..box.g1) {
            val gsh = rsh or (g shl 5)
            for (b in box.b0..box.b1) {
                if (hist[gsh or b] == 0L) continue
                if (r < r0) r0 = r
                if (r > r1) r1 = r
                if (g < g0) g0 = g
                if (g > g1) g1 = g
                if (b < b0) b0 = b
                if (b > b1) b1 = b
            }
        }
    }
    return ColorBox(r0, r1, g0, g1, b0, b1)
}

private fun binToPaletteIndex(hist: LongArray, boxes: List<ColorBox>): IntArray {
    val lut = IntArray(32768)
    for (i in boxes.indices) {
        val box = boxes[i]
        val pal = i + 1
        for (r in box.r0..box.r1) {
            val rsh = r shl 10
            for (g in box.g0..box.g1) {
                val gsh = rsh or (g shl 5)
                for (b in box.b0..box.b1) {
                    val bin = gsh or b
                    if (hist[bin] != 0L) lut[bin] = pal
                }
            }
        }
    }
    return lut
}

private fun paletteFromSource(source: Raster, lut: IntArray, count: Int): IntArray {
    val sr = LongArray(count)
    val sg = LongArray(count)
    val sb = LongArray(count)
    val n = LongArray(count)
    for (p in source.argb) {
        if ((p ushr 24) < Look.PixelArt.ALPHA_OPAQUE) continue
        val i = lut[rgb555(p)] - 1
        if (i < 0) continue
        sr[i] += ((p ushr 16) and 0xFF).toLong()
        sg[i] += ((p ushr 8) and 0xFF).toLong()
        sb[i] += (p and 0xFF).toLong()
        n[i]++
    }
    return IntArray(count) { i ->
        val d = n[i]
        val r = ((sr[i] + d / 2) / d).toInt()
        val g = ((sg[i] + d / 2) / d).toInt()
        val b = ((sb[i] + d / 2) / d).toInt()
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

private fun majorityIndex(
    source: Raster,
    cellX: Int,
    cellY: Int,
    cellSize: Int,
    lut: IntArray,
): Byte {
    val x0 = cellX * cellSize
    val y0 = cellY * cellSize
    val x1 = minOf(x0 + cellSize, source.width)
    val y1 = minOf(y0 + cellSize, source.height)
    var transparent = 0
    var sampled = 0
    val votes = IntArray(Look.PixelArt.PALETTE_SIZE + 1)
    for (y in y0 until y1) {
        val row = y * source.width
        for (x in x0 until x1) {
            sampled++
            val p = source.argb[row + x]
            if ((p ushr 24) < Look.PixelArt.ALPHA_OPAQUE) {
                transparent++
            } else {
                votes[lut[rgb555(p)]]++
            }
        }
    }
    if (transparent * 2 > sampled) return 0
    var best = 0
    var bestCount = 0
    for (i in 1 until votes.size) {
        if (votes[i] > bestCount) {
            bestCount = votes[i]
            best = i
        }
    }
    return best.toByte()
}

private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

private fun rgb555(argb: Int): Int {
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    return ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
}
