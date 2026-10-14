package com.shotgun84.meowdokuoverlay

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.sqrt

/** Screenshot -> board regions. Direct port of the verified Python vision.py,
 * calibrated on 1080x2340 S24 Ultra screenshots (board was 15,697,1050x1050).
 */
object Vision {

    data class BoardResult(
        val bbox: Rect,
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
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()

        val bbox = findBoardBBox(rgb)
        val board = Mat(rgb, bbox)
        val gray = Mat()
        Imgproc.cvtColor(board, gray, Imgproc.COLOR_RGB2GRAY)

        val (n, cellsX, cellsY) = detectGrid(gray)
        gray.release()

        // Edge-mid samples per cell (robust to X / cats covering the center).
        val edge = Array(n * n) { DoubleArray(3) }
        for (r in 0 until n) {
            val (y1, y2) = cellsY[r]
            val h = y2 - y1 + 1
            for (c in 0 until n) {
                val (x1, x2) = cellsX[c]
                val w = x2 - x1 + 1
                val pts = arrayOf(
                    intArrayOf(y1 + (h * 0.12).toInt(), (x1 + x2) / 2),
                    intArrayOf(y2 - (h * 0.12).toInt(), (x1 + x2) / 2),
                    intArrayOf((y1 + y2) / 2, x1 + (w * 0.12).toInt()),
                    intArrayOf((y1 + y2) / 2, x2 - (w * 0.12).toInt()),
                )
                var sr = 0.0; var sg = 0.0; var sb = 0.0
                for ((yy, xx) in pts) {
                    val px = board.get(yy, xx)
                    sr += px[0]; sg += px[1]; sb += px[2]
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
                val mx1 = x1 + ((x2 - x1) * 0.30).toInt()
                val mx2 = x1 + ((x2 - x1) * 0.70).toInt()
                val my1 = y1 + ((y2 - y1) * 0.30).toInt()
                val my2 = y1 + ((y2 - y1) * 0.70).toInt()
                var sr = 0.0; var sg = 0.0; var sb = 0.0
                var white = 0; var dark = 0; var total = 0
                for (yy in my1..my2) for (xx in mx1..mx2) {
                    val px = board.get(yy, xx)
                    sr += px[0]; sg += px[1]; sb += px[2]
                    total++
                    if (px[0] > 200 && px[1] > 200 && px[2] > 200) white++
                    if (px[0] < 80 && px[1] < 80 && px[2] < 80) dark++
                }
                if (total == 0) continue
                val e = edge[r * n + c]
                val dist = sqrt(
                    (sr / total - e[0]) * (sr / total - e[0]) +
                        (sg / total - e[1]) * (sg / total - e[1]) +
                        (sb / total - e[2]) * (sb / total - e[2]),
                )
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

        board.release()
        rgb.release()
        return BoardResult(bbox, n, cellsX, cellsY, regions, cats, xs)
    }

    internal fun findBoardBBox(rgb: Mat): Rect {
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val bw = Mat()
        Imgproc.threshold(gray, bw, 250.0, 255.0, Imgproc.THRESH_BINARY)
        gray.release()
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            bw, contours, Mat(),
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
        )
        bw.release()
        val w = rgb.cols().toDouble()
        val h = rgb.rows().toDouble()
        var best: Rect? = null
        var bestArea = 0.0
        for (cnt in contours) {
            val r = Imgproc.boundingRect(cnt)
            val area = Imgproc.contourArea(cnt)
            if (r.width < w * 0.7 || r.height < w * 0.7) continue
            if (abs(r.width - r.height) > maxOf(r.width, r.height) * 0.08) continue
            if (r.y < h * 0.15 || r.y > h * 0.6) continue
            if (area > bestArea) {
                bestArea = area
                best = r
            }
            cnt.release()
        }
        best?.let { return it }
        // Fallback: calibrated fractions from the S24 Ultra samples.
        return Rect(
            (w * 15 / 1080).toInt(), (h * 697 / 2340).toInt(),
            (w * 1050 / 1080).toInt(), (w * 1050 / 1080).toInt(),
        )
    }

    internal fun detectGrid(boardGray: Mat): Triple<Int, List<Pair<Int, Int>>, List<Pair<Int, Int>>> {
        val bw = Mat()
        Imgproc.threshold(boardGray, bw, 250.0, 255.0, Imgproc.THRESH_BINARY)
        val rows = bw.rows()
        val cols = bw.cols()
        val vFrac = DoubleArray(cols) { x ->
            var white = 0
            for (y in 0 until rows) if (bw.get(y, x)[0] > 0) white++
            white.toDouble() / rows
        }
        val hFrac = DoubleArray(rows) { y ->
            var white = 0
            for (x in 0 until cols) if (bw.get(y, x)[0] > 0) white++
            white.toDouble() / cols
        }
        bw.release()
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

    private fun kmeans(samples: Array<DoubleArray>, k: Int): IntArray {
        val data = Mat(samples.size, 3, CvType.CV_32F)
        for (i in samples.indices) {
            data.put(i, 0, samples[i][0], samples[i][1], samples[i][2])
        }
        val labels = Mat()
        val centers = Mat()
        Core.kmeans(
            data, k, labels,
            TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 20, 1.0),
            10, Core.KMEANS_PP_CENTERS, centers,
        )
        val out = IntArray(samples.size) { labels.get(it, 0)[0].toInt() }
        data.release(); labels.release(); centers.release()
        return out
    }
}
