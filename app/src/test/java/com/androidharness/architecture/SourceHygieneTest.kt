package com.androidharness.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catches a mistake that a compiler cannot: `${'$'}` written inside an
 * ordinary string literal.
 *
 * In a raw (triple-quoted) string that escape is the only way to write a
 * literal dollar, and the shell wrapper needs it everywhere. In a normal
 * string it is not an escape at all - it compiles happily and prints the
 * characters `${'$'}name` to the user instead of the value. That shipped
 * once, into the one line meant to show a file's name and size.
 *
 * So: the sequence is allowed only in the file whose job is a shell
 * script, and refused everywhere else.
 */
class SourceHygieneTest {

    private val allowed = setOf("SandboxRunner.kt")

    @Test
    fun `the dollar escape appears only where raw strings need it`() {
        val offenders = sourceFiles()
            .filter { it.name !in allowed }
            .filter { it.readText().contains("\${'$'}") }
            .map { it.name }

        assertTrue(
            "\${'$'} is only an escape inside a raw string. In these files it " +
                "would be printed literally: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the file that is allowed to use it still does`() {
        // Keeps the allowlist honest: if the wrapper stops needing the
        // escape, the entry should go rather than linger.
        val runner = sourceFiles().single { it.name == "SandboxRunner.kt" }
        assertTrue(runner.readText().contains("\${'$'}"))
    }

    private fun sourceFiles(): List<File> {
        val root = File("src/main/java").takeIf { it.isDirectory }
            ?: File("app/src/main/java")
        assertTrue("cannot find the sources from ${File("").absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
