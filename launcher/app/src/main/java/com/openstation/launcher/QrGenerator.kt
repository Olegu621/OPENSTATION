package com.openstation.launcher

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin ISO 18004 QR Code encoder (Byte mode, ECC Level L, Versions 1..3 up to 53 bytes).
 * Zero third-party dependencies so OpenStationLauncher compiles standalone offline.
 */
object QrGenerator {

    private val gfExp = IntArray(512)
    private val gfLog = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            gfExp[i] = x
            gfLog[x] = i
            x = x shl 1
            if ((x and 0x100) != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) gfExp[i] = gfExp[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return gfExp[gfLog[a] + gfLog[b]]
    }

    private fun rsGenPoly(ecCount: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until ecCount) {
            val next = IntArray(poly.size + 1)
            val factor = gfExp[i]
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor gfMul(poly[j], factor)
            }
            poly = next
        }
        return poly
    }

    private fun rsCompute(data: ByteArray, ecCount: Int): ByteArray {
        val gen = rsGenPoly(ecCount)
        val rem = IntArray(ecCount)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor rem[0]
            System.arraycopy(rem, 1, rem, 0, ecCount - 1)
            rem[ecCount - 1] = 0
            for (j in 0 until ecCount) {
                rem[j] = rem[j] xor gfMul(gen[j + 1], factor)
            }
        }
        return ByteArray(ecCount) { rem[it].toByte() }
    }

    fun generateBitmap(text: String, sizePx: Int = 220): Bitmap? {
        val raw = text.toByteArray(StandardCharsets.UTF_8)
        val version: Int
        val dataCw: Int
        val ecCw: Int
        val alignPos: Int
        when {
            raw.size <= 17 -> { version = 1; dataCw = 19; ecCw = 7; alignPos = -1 }
            raw.size <= 32 -> { version = 2; dataCw = 34; ecCw = 10; alignPos = 18 }
            raw.size <= 53 -> { version = 3; dataCw = 55; ecCw = 15; alignPos = 22 }
            else -> return null
        }

        // Encode Byte mode bit stream
        val bits = ArrayList<Int>(dataCw * 8)
        fun pushBits(v: Int, len: Int) {
            for (i in (len - 1) downTo 0) bits.add((v ushr i) and 1)
        }
        pushBits(0b0100, 4) // Byte mode
        pushBits(raw.size, 8)
        for (b in raw) pushBits(b.toInt() and 0xFF, 8)

        val capBits = dataCw * 8
        val term = minOf(4, capBits - bits.size)
        repeat(term) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)
        var padIdx = 0
        while (bits.size < capBits) {
            pushBits(if (padIdx % 2 == 0) 0xEC else 0x11, 8)
            padIdx++
        }

        val dataBytes = ByteArray(dataCw) { i ->
            var b = 0
            for (j in 0 until 8) b = (b shl 1) or bits[i * 8 + j]
            b.toByte()
        }
        val ecBytes = rsCompute(dataBytes, ecCw)
        val allBits = ArrayList<Int>((dataCw + ecCw) * 8)
        for (b in dataBytes) for (j in 7 downTo 0) allBits.add(((b.toInt() and 0xFF) ushr j) and 1)
        for (b in ecBytes) for (j in 7 downTo 0) allBits.add(((b.toInt() and 0xFF) ushr j) and 1)

        val dim = version * 4 + 17
        val base = Array(dim) { IntArray(dim) { -1 } } // -1 free, 0 white, 1 black
        val isFunc = Array(dim) { BooleanArray(dim) }

        fun setFunc(r: Int, c: Int, black: Boolean) {
            if (r in 0 until dim && c in 0 until dim) {
                base[r][c] = if (black) 1 else 0
                isFunc[r][c] = true
            }
        }

        fun drawFinder(tr: Int, tc: Int) {
            for (dr in -1..7) {
                for (dc in -1..7) {
                    val r = tr + dr
                    val c = tc + dc
                    if (r !in 0 until dim || c !in 0 until dim) continue
                    val black = (dr in 0..6 && dc in 0..6) &&
                        (dr == 0 || dr == 6 || dc == 0 || dc == 6 || (dr in 2..4 && dc in 2..4))
                    setFunc(r, c, black)
                }
            }
        }
        drawFinder(0, 0)
        drawFinder(0, dim - 7)
        drawFinder(dim - 7, 0)

        if (alignPos > 0) {
            for (dr in -2..2) {
                for (dc in -2..2) {
                    val black = (dr == -2 || dr == 2 || dc == -2 || dc == 2 || (dr == 0 && dc == 0))
                    setFunc(alignPos + dr, alignPos + dc, black)
                }
            }
        }

        for (i in 8 until dim - 8) {
            val b = (i % 2 == 0)
            setFunc(6, i, b)
            setFunc(i, 6, b)
        }
        setFunc(4 * version + 9, 8, true)

        // Reserve format areas
        for (i in 0..8) {
            if (!isFunc[8][i]) setFunc(8, i, false)
            if (!isFunc[i][8]) setFunc(i, 8, false)
        }
        for (i in 0..7) {
            setFunc(8, dim - 1 - i, false)
            if (i < 7) setFunc(dim - 1 - i, 8, false)
        }

        // Place data bits in upward/downward zigzag
        var bitPtr = 0
        var col = dim - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col--
            val rows = if (upward) (dim - 1 downTo 0) else (0 until dim)
            for (r in rows) {
                for (dc in 0..1) {
                    val c = col - dc
                    if (!isFunc[r][c]) {
                        base[r][c] = if (bitPtr < allBits.size) allBits[bitPtr++] else 0
                    }
                }
            }
            upward = !upward
            col -= 2
        }

        // Select best mask (0..7) with ISO penalty scoring
        var bestScore = Int.MAX_VALUE
        var bestGrid: Array<IntArray>? = null

        for (m in 0 until 8) {
            val g = Array(dim) { r ->
                IntArray(dim) { c ->
                    if (isFunc[r][c]) base[r][c] else {
                        val inv = when (m) {
                            0 -> (r + c) % 2 == 0
                            1 -> r % 2 == 0
                            2 -> c % 3 == 0
                            3 -> (r + c) % 3 == 0
                            4 -> (r / 2 + c / 3) % 2 == 0
                            5 -> (r * c) % 2 + (r * c) % 3 == 0
                            6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0
                            else -> ((r + c) % 2 + (r * c) % 3) % 2 == 0
                        }
                        base[r][c] xor (if (inv) 1 else 0)
                    }
                }
            }
            applyFormat(g, dim, m)
            val sc = penaltyScore(g, dim)
            if (sc < bestScore) {
                bestScore = sc
                bestGrid = g
            }
        }

        val grid = bestGrid ?: return null
        val quiet = 2
        val total = dim + quiet * 2
        val scale = maxOf(1, sizePx / total)
        val outPx = total * scale
        val bmp = Bitmap.createBitmap(outPx, outPx, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val black = 0xFF0B0F17.toInt()
        for (r in 0 until dim) {
            for (c in 0 until dim) {
                if (grid[r][c] == 1) {
                    val px = (c + quiet) * scale
                    val py = (r + quiet) * scale
                    for (dy in 0 until scale) {
                        for (dx in 0 until scale) {
                            bmp.setPixel(px + dx, py + dy, black)
                        }
                    }
                }
            }
        }
        return bmp
    }

    private fun applyFormat(g: Array<IntArray>, dim: Int, mask: Int) {
        // ECC Level L = 01
        val data = (0b01 shl 3) or mask
        var d = data shl 10
        val poly = 0b10100110111
        for (i in 14 downTo 10) {
            if (((d ushr i) and 1) != 0) d = d xor (poly shl (i - 10))
        }
        val fmt = ((data shl 10) or d) xor 0b101010000010010
        for (i in 0..14) {
            val bit = (fmt ushr i) and 1
            // Around top-left (bits 0..7 run down column 8, bits 8..14 run left along row 8)
            when {
                i < 6 -> g[i][8] = bit
                i == 6 -> g[7][8] = bit
                i == 7 -> g[8][8] = bit
                i == 8 -> g[8][7] = bit
                else -> g[8][14 - i] = bit
            }
            // Top-right & bottom-left
            if (i < 8) {
                g[8][dim - 1 - i] = bit
            } else {
                g[dim - 15 + i][8] = bit
            }
        }
    }

    private fun penaltyScore(g: Array<IntArray>, dim: Int): Int {
        var score = 0
        for (r in 0 until dim) {
            var runR = 1; var runC = 1
            for (i in 1 until dim) {
                if (g[r][i] == g[r][i - 1]) runR++ else {
                    if (runR >= 5) score += 3 + (runR - 5)
                    runR = 1
                }
                if (g[i][r] == g[i - 1][r]) runC++ else {
                    if (runC >= 5) score += 3 + (runC - 5)
                    runC = 1
                }
            }
            if (runR >= 5) score += 3 + (runR - 5)
            if (runC >= 5) score += 3 + (runC - 5)
        }
        for (r in 0 until dim - 1) {
            for (c in 0 until dim - 1) {
                val v = g[r][c]
                if (v == g[r][c + 1] && v == g[r + 1][c] && v == g[r + 1][c + 1]) score += 3
            }
        }
        var dark = 0
        for (r in 0 until dim) for (c in 0 until dim) if (g[r][c] == 1) dark++
        val pct = (dark * 100) / (dim * dim)
        score += (kotlin.math.abs(pct - 50) / 5) * 10
        return score
    }
}
