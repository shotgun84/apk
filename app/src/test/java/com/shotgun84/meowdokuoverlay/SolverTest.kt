package com.shotgun84.meowdokuoverlay

import org.junit.Assert.*
import org.junit.Test

class SolverTest {

    // Level 31 (8x8) region map extracted from the verified Python pipeline.
    private val level31 = listOf(
        listOf(1, 1, 1, 1, 1, 6, 6, 6),
        listOf(1, 0, 1, 0, 1, 7, 6, 5),
        listOf(1, 0, 1, 0, 1, 1, 6, 5),
        listOf(1, 0, 0, 0, 0, 0, 5, 5),
        listOf(1, 1, 1, 0, 2, 5, 5, 5),
        listOf(1, 1, 1, 1, 2, 2, 3, 5),
        listOf(1, 4, 2, 2, 2, 3, 3, 5),
        listOf(4, 4, 3, 3, 3, 3, 3, 5),
    )
    private val level31Solution = setOf(
        Solver.Cell(0, 7), Solver.Cell(1, 5), Solver.Cell(2, 0), Solver.Cell(3, 2),
        Solver.Cell(4, 6), Solver.Cell(5, 4), Solver.Cell(6, 1), Solver.Cell(7, 3),
    )

    // Level 33 (10x10) region map, identical across all 4 screenshots.
    private val level33 = listOf(
        listOf(5, 5, 8, 6, 6, 6, 6, 6, 6, 7),
        listOf(5, 9, 8, 6, 6, 6, 6, 6, 6, 7),
        listOf(5, 5, 8, 8, 6, 0, 0, 0, 0, 0),
        listOf(5, 5, 5, 8, 0, 0, 0, 4, 3, 0),
        listOf(1, 1, 5, 8, 8, 0, 0, 4, 3, 3),
        listOf(1, 1, 2, 0, 0, 0, 4, 4, 3, 4),
        listOf(1, 1, 2, 0, 0, 4, 4, 4, 3, 4),
        listOf(1, 1, 2, 2, 2, 4, 4, 4, 4, 4),
        listOf(1, 1, 1, 1, 2, 2, 4, 2, 4, 2),
        listOf(2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
    )
    private val level33Solution = setOf(
        Solver.Cell(0, 9), Solver.Cell(1, 1), Solver.Cell(2, 4), Solver.Cell(3, 0),
        Solver.Cell(4, 3), Solver.Cell(5, 5), Solver.Cell(6, 8), Solver.Cell(7, 6),
        Solver.Cell(8, 2), Solver.Cell(9, 7),
    )

    @Test
    fun level31_solvesToKnownSolution() {
        val sol = Solver.solve(level31)
        assertNotNull(sol)
        assertEquals(level31Solution, sol!!.toSet())
        assertTrue(Solver.verify(level31, sol))
    }

    @Test
    fun level33_solvesToKnownSolution() {
        val sol = Solver.solve(level33)
        assertNotNull(sol)
        assertEquals(level33Solution, sol!!.toSet())
        assertTrue(Solver.verify(level33, sol))
    }

    @Test
    fun fixedCats_mustBeSubsetOfSolution() {
        val fixed = setOf(Solver.Cell(0, 9), Solver.Cell(1, 1), Solver.Cell(2, 4))
        val sol = Solver.solve(level33, fixedCats = fixed)
        assertNotNull(sol)
        assertTrue(sol!!.toSet().containsAll(fixed))
    }
}
