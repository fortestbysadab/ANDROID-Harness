package com.androidharness.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing the script's own exit status out of the wrapper's output.
 *
 * This matters more than it looks. The wrapper always exits 0 so that a
 * broken wrapper and a failing script stay distinguishable, which means
 * this trailer is the only place the script's real status is carried.
 */
class ScriptExitTest {

    @Test
    fun `reads the status and keeps the output above it`() {
        val parsed = ScriptExit.from("hello\nworld\n${SandboxRunner.EXIT_MARKER} 0\n")
        assertEquals(0, parsed?.status)
        assertEquals("hello\nworld", parsed?.output)
    }

    @Test
    fun `a non zero status survives`() {
        assertEquals(3, ScriptExit.from("boom\n${SandboxRunner.EXIT_MARKER} 3\n")?.status)
    }

    @Test
    fun `a missing marker is not guessed at`() {
        assertNull(ScriptExit.from("output with no trailer\n"))
    }

    @Test
    fun `a script cannot fake its own exit status`() {
        // The script prints the marker itself, claiming success, then
        // really exits 1. The last marker is the wrapper's and wins.
        val stdout = "${SandboxRunner.EXIT_MARKER} 0\nstill running\n" +
            "${SandboxRunner.EXIT_MARKER} 1\n"
        val parsed = ScriptExit.from(stdout)
        assertEquals(1, parsed?.status)
    }

    @Test
    fun `a forged marker stays visible in the output rather than being hidden`() {
        val stdout = "${SandboxRunner.EXIT_MARKER} 0\nreal output\n" +
            "${SandboxRunner.EXIT_MARKER} 1\n"
        assertEquals(
            "${SandboxRunner.EXIT_MARKER} 0\nreal output",
            ScriptExit.from(stdout)?.output,
        )
    }

    @Test
    fun `a non numeric status is refused rather than defaulted`() {
        assertNull(ScriptExit.from("x\n${SandboxRunner.EXIT_MARKER} banana\n"))
    }
}
