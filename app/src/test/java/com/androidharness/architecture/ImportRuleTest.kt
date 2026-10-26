package com.androidharness.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the whole project rests on, enforced rather than hoped for.
 *
 * Android Harness exists to stop being a Termux program. The way that
 * fails is not a decision, it is drift: Termux is connected and working,
 * so one more feature reaches for it, then another, and the dependency is
 * load-bearing again.
 *
 * So only files that have a stated reason may import the Termux package,
 * and adding a new one has to be a deliberate edit to this list.
 */
class ImportRuleTest {

    private val termuxPackage = "com.androidharness.termux"

    /** Each entry needs a reason, not just a name. */
    private val permitted = mapOf(
        "TermuxSandbox.kt" to
            "the CodeSandbox implementation backed by Termux; the whole point",
        "DiagnosticsActivity.kt" to
            "Phase 0: its purpose is exercising the raw bridge before any " +
            "sandbox exists. Delete this entry once Phase 4 replaces it.",
    )

    @Test
    fun `only permitted files reach into the Termux package`() {
        val offenders = sourceFiles()
            .filter { it.path.contains("/termux/").not() }
            .filter { file ->
                file.readLines().any { line ->
                    line.trimStart().startsWith("import $termuxPackage")
                }
            }
            .map { it.name }
            .sorted()

        assertEquals(
            "Only these files may depend on Termux. Adding one is a " +
                "deliberate architectural decision, so add it to " +
                "ImportRuleTest with a reason.",
            permitted.keys.sorted(),
            offenders,
        )
    }

    @Test
    fun `the sandbox interface itself knows nothing about Termux`() {
        val source = sourceFiles().single { it.name == "CodeSandbox.kt" }
        val importsTermux = source.readLines().any {
            it.trimStart().startsWith("import $termuxPackage")
        }
        assertTrue(
            "CodeSandbox must stay implementation-free, or Termux stops " +
                "being replaceable",
            !importsTermux,
        )
    }

    @Test
    fun `every permitted file still exists`() {
        // Otherwise the allowlist quietly rots into permission for
        // something that was renamed.
        val names = sourceFiles().map { it.name }.toSet()
        permitted.keys.forEach { permittedName ->
            assertTrue("$permittedName is in the allowlist but not in the source",
                permittedName in names)
        }
    }

    private fun sourceFiles(): List<File> =
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
}
