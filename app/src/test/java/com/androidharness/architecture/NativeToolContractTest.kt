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
 */
class NativeToolContractTest {

    @Test
    fun `every declared risk is one the core recognises`() {
        val known = risksDeclaredInPython()
        assertTrue("could not read the Risk enum from the core", known.size >= 5)

        val declared = toolSources().mapNotNull { file ->
            RISK.find(file.readText())?.groupValues?.get(1)?.let { file.name to it }
        }
        assertTrue("no native tools found to check", declared.isNotEmpty())

        val unknown = declared.filterNot { (_, risk) -> risk in known }
        assertEquals("these risk names do not exist in harness_core.tools.Risk", emptyList(), unknown)
    }

    @Test
    fun `every native tool says what it does`() {
        // The description is the only thing the model sees. A tool with
        // a vague one is a tool that gets called at the wrong moment.
        val vague = toolSources()
            .mapNotNull { file ->
                DESCRIPTION.find(file.readText())?.groupValues?.get(1)?.let { file.name to it }
            }
            .filter { (_, text) -> text.length < 30 }
        assertEquals("these descriptions are too short to guide a model", emptyList(), vague)
    }

    private fun risksDeclaredInPython(): Set<String> {
        val source = resolve("app/src/main/python/harness_core/tools.py")
        val body = source.readText().substringAfter("class Risk(").substringBefore("\n\n\n")
        return Regex("""=\s*"([a-z_]+)"""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    private fun toolSources(): List<File> =
        resolve("app/src/main/java/com/androidharness/tools")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "NativeTool.kt" }
            .toList()

    /** Gradle runs tests from the module directory; the IDE may not. */
    private fun resolve(path: String): File {
        val fromModule = File(path.removePrefix("app/"))
        val file = if (fromModule.exists()) fromModule else File(path)
        assertTrue("cannot find $path from ${File("").absolutePath}", file.exists())
        return file
    }

    private companion object {
        val RISK = Regex("""override val risk\s*=\s*"([a-z_]+)"""")
        val DESCRIPTION = Regex("""override val description\s*=\s*\n?\s*"([^"]+)""")
    }
}
