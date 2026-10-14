package com.shotgun84.meowdokuoverlay

/** Pure Meowdoku constraint solver. Direct port of the verified Python solver.
 *
 * Rules: N x N board, N regions, exactly one cat per row / column / region,
 * no two cats touching, even diagonally.
 */
object Solver {

    data class Cell(val r: Int, val c: Int)

    fun solve(
        regions: List<List<Int>>,
        fixedCats: Set<Cell> = emptySet(),
        forbidden: Set<Cell> = emptySet(),
    ): List<Cell>? {
        val n = regions.size
        require(regions.all { it.size == n }) { "regions must be N x N" }

        // Normalize region ids to 0..k-1
        val uniq = regions.flatten().toSortedSet().toList()
        val mp = uniq.mapIndexed { i, v -> v to i }.toMap()
        val reg = regions.map { row -> row.map { mp.getValue(it) } }
        val nReg = uniq.size

        val regionCells = Array(nReg) { mutableListOf<Cell>() }
        for (r in 0 until n) for (c in 0 until n) {
            val cell = Cell(r, c)
            if (cell in forbidden) continue
            regionCells[reg[r][c]].add(cell)
        }
        if (fixedCats.any { it in forbidden }) return null

        val rowCol = mutableMapOf<Int, Int>()
        val colUsed = BooleanArray(n)
        val regUsed = BooleanArray(nReg)
        val placed = mutableSetOf<Cell>()

        fun touches(r: Int, c: Int): Boolean {
            for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                if (Cell(r + dr, c + dc) in placed) return true
            }
            return false
        }

        for ((r, c) in fixedCats) {
            if (rowCol.containsKey(r) || colUsed[c] || regUsed[reg[r][c]]) return null
            if (touches(r, c)) return null
            rowCol[r] = c
            colUsed[c] = true
            regUsed[reg[r][c]] = true
            placed.add(Cell(r, c))
        }

        fun rowCandidates(r: Int): List<Int> {
            val out = mutableListOf<Int>()
            for (c in 0 until n) {
                if (colUsed[c] || regUsed[reg[r][c]]) continue
                if (Cell(r, c) in forbidden) continue
                if (touches(r, c)) continue
                out.add(c)
            }
            return out
        }

        val unassigned = (0 until n).filter { it !in rowCol }.toMutableSet()

        fun regionsFeasible(): Boolean {
            for (rid in 0 until nReg) {
                if (regUsed[rid]) continue
                var ok = false
                for ((r, c) in regionCells[rid]) {
                    if (r !in unassigned || colUsed[c]) continue
                    if (Cell(r, c) in forbidden || touches(r, c)) continue
                    ok = true
                    break
                }
                if (!ok) return false
            }
            return true
        }

        fun backtrack(): Boolean {
            if (unassigned.isEmpty()) return regUsed.all { it }
            // MRV: row with fewest candidates
            var bestR = -1
            var best: List<Int> = emptyList()
            for (r in unassigned) {
                val cands = rowCandidates(r)
                if (cands.isEmpty()) return false
                if (bestR == -1 || cands.size < best.size) {
                    bestR = r
                    best = cands
                    if (best.size == 1) break
                }
            }
            unassigned.remove(bestR)
            for (c in best) {
                val rid = reg[bestR][c]
                if (colUsed[c] || regUsed[rid] || touches(bestR, c)) continue
                rowCol[bestR] = c
                colUsed[c] = true
                regUsed[rid] = true
                placed.add(Cell(bestR, c))
                if (regionsFeasible() && backtrack()) return true
                placed.remove(Cell(bestR, c))
                regUsed[rid] = false
                colUsed[c] = false
                rowCol.remove(bestR)
            }
            unassigned.add(bestR)
            return false
        }

        if (!regionsFeasible()) return null
        return if (backtrack()) placed.sortedWith(compareBy({ it.r }, { it.c })) else null
    }

    fun verify(regions: List<List<Int>>, cats: List<Cell>): Boolean {
        val n = regions.size
        if (cats.size != n) return false
        if (cats.map { it.r }.toSet().size != n) return false
        if (cats.map { it.c }.toSet().size != n) return false
        if (cats.map { regions[it.r][it.c] }.toSet().size != n) return false
        val s = cats.toSet()
        for ((r, c) in cats) {
            for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                if (Cell(r + dr, c + dc) in s) return false
            }
        }
        return true
    }
}
