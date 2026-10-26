package com.androidharness.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the two languages honest with each other.
 *
 * A native tool declares its risk as a string, because a string is what
 * survives the crossing into Python. That is the one place the type
 * system stops helping: `"read_onyl"` compiles, and the mistake only
 * appears when the core refuses to register the tool - on the device,
 * at startup, in front of the owner.
 *
 * So the allowed values are read out of the Python source that defines
 * them. If someone adds a risk level on one side, this fails until both
 * sides agree.
 *
 * Counted per tool rather than per file. The first version of this test
 * looked at one declaration per file and silently ignored the second
 * tool in `ClipboardTools.kt` - a check that quietly inspects less than
 * it claims is worse than no check, because it is believed.
 */
class NativeToolContractTest {

    @Test
    fun `every declared risk is one the core recognises`() {
        val known = risksDeclaredInPython()
        assertTrue("could not read the Risk enum from the core", known.size >= 5)

        val declared = declarations(RISK)
        assertTrue("no native tools found to check", declared.isNotEmpty())

        val unknown = declared.filterNot { (_, risk) -> risk in known }
        assertEquals(
            "these risk names do not exist in harness_core.tools.Risk",
            emptyList<Pair<String, String>>(),
            unknown,
        )
    }

    @Test
    fun `every tool declares both a risk and a description`() {
        // Counted against the number of implementations, so a tool
        // added to an existing file cannot inherit its neighbour's
        // declarations by being overlooked.
        toolSources().forEach { file ->
            val source = file.readText()
            val tools = IMPLEMENTS.findAll(source).count()
            assertEquals("${file.name}: a tool without a risk", tools, RISK.findAll(source).count())
            assertEquals(
                "${file.name}: a tool without a description",
                tools,
                DESCRIPTION.findAll(source).count(),
            )
        }
    }

    @Test
    fun `every native tool says what it does`() {
        // The description is the only thing the model sees. A tool with
        // a vague one is a tool that gets called at the wrong moment.
        val vague = declarations(DESCRIPTION).filter { (_, text) -> text.length < 30 }
        assertEquals(
            "these descriptions are too short to guide a model",
            emptyList<Pair<String, String>>(),
            vague,
        )
    }

    @Test
    fun `no tool reaches into Termux except through the sandbox interface`() {
        // The project's founding rule, restated where tools are written,
        // because this is the package that will be tempted.
        val offenders = toolSources()
            .filter { it.readText().contains("import com.androidharness.termux") }
            .map { it.name }
        assertEquals("tools speak to CodeSandbox, never to Termux", emptyList<String>(), offenders)
    }

    @Test
    fun `a tool that checks its arguments says so`() {
        // check() is only called when checksArguments is true, so
        // overriding one without the other is a silent no-op - the
        // owner would go back to being asked to approve calls that
        // cannot work.
        val mismatched = toolSources().filter { file ->
            val source = file.readText()
            CHECKS_OVERRIDE.containsMatchIn(source) != CHECKS_FLAG.containsMatchIn(source)
        }.map { it.name }
        assertEquals(
            "override check() and set checksArguments together, or neither",
            emptyList<String>(),
            mismatched,
        )
    }

    private fun declarations(pattern: Regex): List<Pair<String, String>> =
        toolSources().flatMap { file ->
            pattern.findAll(file.readText()).map { file.name to it.groupValues[1] }
        }

    private fun risksDeclaredInPython(): Set<String> {
        val source = resolve("app/src/main/python/harness_core/tools.py")
        val body = source.readText().substringAfter("class Risk(").substringBefore("\n\n\n")
        return Regex("""=\s*"([a-z_]+)"""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    /** Found by what they are, not by an allowlist of what they are not. */
    private fun toolSources(): List<File> =
        resolve("app/src/main/java/com/androidharness/tools")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { IMPLEMENTS.containsMatchIn(it.readText()) }
            .toList()

    /** Gradle runs tests from the module directory; the IDE may not. */
    private fun resolve(path: String): File {
        val fromModule = File(path.removePrefix("app/"))
        val file = if (fromModule.exists()) fromModule else File(path)
        assertTrue("cannot find $path from ${File("").absolutePath}", file.exists())
        return file
    }

    private companion object {
        val IMPLEMENTS = Regex("""\)\s*:\s*NativeTool\b""")
        val RISK = Regex("""override val risk\s*=\s*"([a-z_]+)"""")
        val DESCRIPTION = Regex("""override val description\s*=\s*\n?\s*"([^"]+)""")
        val CHECKS_OVERRIDE = Regex("""override fun check\(""")
        val CHECKS_FLAG = Regex("""override val checksArguments\s*=\s*true""")
    }
}
