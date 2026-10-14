package com.shotgun84.meowdokuoverlay

import android.graphics.Bitmap
import kotlin.math.sqrt
import kotlin.random.Random

/** Screenshot -> board regions, zero native dependencies.
 *
 * Direct port of the verified Python vision.py, calibrated on 1080x2340
 * S24 Ultra screenshots (board was 15,697,1050x1050). Pure Bitmap pixel ops:
 * bright-pixel threshold, white-fraction projections, tiny KMeans.
 */
object Vision {

    data class BoardResult(
        val left: Int,
        val top: Int,
        /** board width/height in screenshot px (kept separately; box is square-ish) */
        val width: Int,
        val height: Int,
        val n: Int,
        /** per-cell x ranges in board-local px: list of (x1, x2) */
        val cellsX: List<Pair<Int, Int>>,
        /** per-cell y ranges in board-local px: list of (y1, y2) */
        val cellsY: List<Pair<Int, Int>>,
        val regions: List<List<Int>>,
        val detectedCats: List<Solver.Cell>,
        val detectedX: List<Solver.Cell>,
    )

    fun extractBoard(bitmap: Bitmap): BoardResult {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)

        val (left, top, right, bottom) = findBoardBox(px, w, h)
        val (n, cellsX, cellsY) = detectGrid(px, w, left, top, right, bottom)

        // Edge-mid samples per cell (robust to X / cats covering the center).
        val edge = Array(n * n) { DoubleArray(3) }
        for (r in 0 until n) {
            val (y1, y2) = cellsY[r]
            val hh = y2 - y1 + 1
            for (c in 0 until n) {
                val (x1, x2) = cellsX[c]
                val ww = x2 - x1 + 1
                val pts = arrayOf(
                    intArrayOf(top + y1 + (hh * 0.12).toInt(), left + (x1 + x2) / 2),
                    intArrayOf(top + y2 - (hh * 0.12).toInt(), left + (x1 + x2) / 2),
                    intArrayOf(top + (y1 + y2) / 2, left + x1 + (ww * 0.12).toInt()),
                    intArrayOf(top + (y1 + y2) / 2, left + x2 - (ww * 0.12).toInt()),
                )
                var sr = 0.0; var sg = 0.0; var sb = 0.0
                for ((yy, xx) in pts) {
                    val p = px[yy * w + xx]
                    sr += (p shr 16) and 255
                    sg += (p shr 8) and 255
                    sb += p and 255
                }
                edge[r * n + c] = doubleArrayOf(sr / 4, sg / 4, sb / 4)
            }
        }

        val labels = kmeans(edge, n)
        val regions = List(n) { r -> List(n) { c -> labels[r * n + c] } }

        // Best-effort cat / X detection from the center patch.
        val cats = mutableListOf<Solver.Cell>()
        val xs = mutableListOf<Solver.Cell>()
        for (r in 0 until n) {
            val (y1, y2) = cellsY[r]
            for (c in 0 until n) {
                val (x1, x2) = cellsX[c]
                val mx1 = left + x1 + ((x2 - x1) * 0.30).toInt()
                val mx2 = left + x1 + ((x2 - x1) * 0.70).toInt()
                val my1 = top + y1 + ((y2 - y1) * 0.30).toInt()
                val my2 = top + y1 + ((y2 - y1) * 0.70).toInt()
                var sr = 0.0; var sg = 0.0; var sb = 0.0
                var white = 0; var dark = 0; var total = 0
                for (yy in my1..my2) for (xx in mx1..mx2) {
                    val p = px[yy * w + xx]
                    val pr = (p shr 16) and 255
                    val pg = (p shr 8) and 255
                    val pb = p and 255
                    sr += pr; sg += pg; sb += pb
                    total++
                    if (pr > 200 && pg > 200 && pb > 200) white++
                    if (pr < 80 && pg < 80 && pb < 80) dark++
                }
                if (total == 0) continue
                val e = edge[r * n + c]
                val dr = sr / total - e[0]
                val dg = sg / total - e[1]
                val db = sb / total - e[2]
                val dist = sqrt(dr * dr + dg * dg + db * db)
                val whiteFrac = white.toDouble() / total
                val darkFrac = dark.toDouble() / total
                val cell = Solver.Cell(r, c)
                if (dist < 35 && whiteFrac < 0.3 && darkFrac < 0.10) continue // empty
                when {
                    darkFrac > 0.12 -> cats.add(cell)
                    whiteFrac > 0.40 -> xs.add(cell)
                    dist > 35 -> if (darkFrac > 0.06) cats.add(cell) else xs.add(cell)
                }
            }
        }
        return BoardResult(left, top, right - left + 1, bottom - top + 1, n, cellsX, cellsY, regions, cats, xs)
    }

    private fun isBright(p: Int): Boolean =
        ((p shr 16) and 255) > 250 && ((p shr 8) and 255) > 250 && (p and 255) > 250

    /** Largest run of rows/cols with noticeable white = the board.
     * Grid gaps + outer border keep every board row/col above ~3% white;
     * beige background and UI sit at ~0%. Ad banner is white but shorter.
     */
    internal fun findBoardBox(px: IntArray, w: Int, h: Int): IntArray {
        val rowFrac = DoubleArray(h)
        for (y in 0 until h) {
            var c = 0
            var i = y * w
            val end = i + w
            while (i < end) { if (isBright(px[i])) c++; i++ }
            rowFrac[y] = c.toDouble() / w
        }
        val colFrac = DoubleArray(w)
        for (x in 0 until w) {
            var c = 0
            var i = x
            while (i < px.size) { if (isBright(px[i])) c++; i += w }
            colFrac[x] = c.toDouble() / h
        }
        fun longestRun(frac: DoubleArray, thr: Double): Pair<Int, Int>? {
            var bs = -1; var be = -1; var s = -1
            for (i in frac.indices) {
                if (frac[i] > thr && s == -1) s = i
                else if (frac[i] <= thr && s != -1) {
                    if (bs == -1 || i - s > be - bs) { bs = s; be = i - 1 }
                    s = -1
                }
            }
            if (s != -1 && (bs == -1 || frac.size - s > be - bs)) { bs = s; be = frac.size - 1 }
            return if (bs == -1) null else bs to be
        }
        val rows = longestRun(rowFrac, 0.02)
        val cols = longestRun(colFrac, 0.02)
        if (rows != null && cols != null) {
            val bw = cols.second - cols.first + 1
            val bh = rows.second - rows.first + 1
            // Board is big and roughly square; otherwise fall back to calibration.
            if (bw > w * 0.5 && bh > h * 0.3 && bw.toDouble() / bh in 0.85..1.15) {
                return intArrayOf(cols.first, rows.first, cols.second, rows.second)
            }
        }
        val side = (w * 1050 / 1080)
        val left = w * 15 / 1080
        val top = h * 697 / 2340
        return intArrayOf(left, top, left + side - 1, top + side - 1)
    }

    internal fun detectGrid(
        px: IntArray, w: Int, left: Int, top: Int, right: Int, bottom: Int,
    ): Triple<Int, List<Pair<Int, Int>>, List<Pair<Int, Int>>> {
        val bw = right - left + 1
        val bh = bottom - top + 1
        val vFrac = DoubleArray(bw) { x ->
            var c = 0
            for (y in top..bottom) if (isBright(px[y * w + left + x])) c++
            c.toDouble() / bh
        }
        val hFrac = DoubleArray(bh) { y ->
            var c = 0
            val row = (top + y) * w + left
            for (x in 0 until bw) if (isBright(px[row + x])) c++
            c.toDouble() / bw
        }
        val vGaps = gapRuns(vFrac)
        val hGaps = gapRuns(hFrac)
        val n = minOf(vGaps.size, hGaps.size) - 1
        require(n in 4..12) { "grid detect failed: ${vGaps.size}x${hGaps.size}" }
        val cellsX = (0 until n).map { i -> (vGaps[i].second + 1) to (vGaps[i + 1].first - 1) }
        val cellsY = (0 until n).map { i -> (hGaps[i].second + 1) to (hGaps[i + 1].first - 1) }
        return Triple(n, cellsX, cellsY)
    }

    private fun gapRuns(frac: DoubleArray, thr: Double = 0.7): List<Pair<Int, Int>> {
        val gaps = mutableListOf<Pair<Int, Int>>()
        var start: Int? = null
        for (i in frac.indices) {
            if (frac[i] > thr && start == null) start = i
            else if (frac[i] <= thr && start != null) {
                gaps.add(start to (i - 1))
                start = null
            }
        }
        if (start != null) gaps.add(start to (frac.size - 1))
        return gaps
    }

    /** Minimal KMeans (kmeans++ init, fixed seed): N<=12 so samples<=144x3. */
    internal fun kmeans(samples: Array<DoubleArray>, k: Int): IntArray {
        val rnd = Random(0)
        val centers = Array(k) { DoubleArray(3) }
        centers[0] = samples[rnd.nextInt(samples.size)].copyOf()
        val minD2 = DoubleArray(samples.size) { Double.MAX_VALUE }
        for (ci in 1 until k) {
            var sum = 0.0
            for (i in samples.indices) {
                val d2 = dist2(samples[i], centers[ci - 1])
                if (d2 < minD2[i]) minD2[i] = d2
                sum += minD2[i]
            }
            var pick = rnd.nextDouble() * sum
            var idx = samples.size - 1
            for (i in samples.indices) {
                pick -= minD2[i]
                if (pick <= 0) { idx = i; break }
            }
            centers[ci] = samples[idx].copyOf()
        }
        val assign = IntArray(samples.size)
        repeat(25) {
            var changed = false
            for (i in samples.indices) {
                var best = 0; var bd = Double.MAX_VALUE
                for (ci in 0 until k) {
                    val d = dist2(samples[i], centers[ci])
                    if (d < bd) { bd = d; best = ci }
                }
                if (assign[i] != best) { assign[i] = best; changed = true }
            }
            if (!changed) return assign
            val sums = Array(k) { DoubleArray(3) }
            val counts = IntArray(k)
            for (i in samples.indices) {
                val a = assign[i]
                sums[a][0] += samples[i][0]; sums[a][1] += samples[i][1]; sums[a][2] += samples[i][2]
                counts[a]++
            }
            for (ci in 0 until k) {
                if (counts[ci] > 0) {
                    centers[ci][0] = sums[ci][0] / counts[ci]
                    centers[ci][1] = sums[ci][1] / counts[ci]
                    centers[ci][2] = sums[ci][2] / counts[ci]
                } else {
                    centers[ci] = samples[rnd.nextInt(samples.size)].copyOf()
                }
            }
        }
        return assign
    }

    private fun dist2(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return dx * dx + dy * dy + dz * dz
    }
}
