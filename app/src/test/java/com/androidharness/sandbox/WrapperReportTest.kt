package com.androidharness.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Separating what the wrapper said from what the script said.
 *
 * Both share one stdout stream, and the script is model-written code that
 * may have been influenced by hostile input. So the test that matters is
 * not "does it parse" but "can a script lie about it".
 */
class WrapperReportTest {

    private val exit = SandboxRunner.EXIT_MARKER
    private val output = SandboxRunner.OUTPUT_MARKER
    private val dir = SandboxRunner.OUTPUT_DIR_MARKER
    private val root = SandboxRunner.SHARED_ROOT

    @Test
    fun `reads the files the wrapper copied and where it put them`() {
        val stdout = "script said hi\n$exit 0\n$output report.pdf\n$dir $root/run-1\n"
        val report = WrapperReport.from(stdout)
        assertEquals(listOf("report.pdf"), report.copiedOutputs)
        assertEquals("$root/run-1", report.outputDirectory)
        assertFalse(report.storageUnavailable)
    }

    @Test
    fun `a script cannot claim a file was copied`() {
        // The script prints the metadata itself, before the wrapper's
        // exit marker. Only the tail after the last exit marker counts.
        val stdout = "$output /etc/passwd\n$dir /somewhere/else\n$exit 0\n$dir $root/run-2\n"
        val report = WrapperReport.from(stdout)
        assertEquals(emptyList<String>(), report.copiedOutputs)
        assertEquals("$root/run-2", report.outputDirectory)
    }

    @Test
    fun `a script cannot fake the storage failure either`() {
        val stdout = "${SandboxRunner.NO_STORAGE_MARKER}\n$exit 0\n$dir $root/run-3\n"
        assertFalse(WrapperReport.from(stdout).storageUnavailable)
    }

    @Test
    fun `a genuine storage failure is reported`() {
        val stdout = "$exit 0\n${SandboxRunner.NO_STORAGE_MARKER}\n"
        val report = WrapperReport.from(stdout)
        assertTrue(report.storageUnavailable)
        assertNull(report.outputDirectory)
    }

    @Test
    fun `no metadata at all is not an error`() {
        val report = WrapperReport.from("just output\n$exit 0\n")
        assertEquals(emptyList<String>(), report.copiedOutputs)
        assertNull(report.outputDirectory)
    }
}

/**
 * Output filenames come from the model and become paths, so they are
 * validated rather than cleaned up.
 */
class OutputNamesTest {

    @Test
    fun `ordinary filenames are allowed`() {
        listOf("report.pdf", "a.txt", "sheet-1.xlsx", "x_y.z", "A1").forEach {
            assertTrue(it, OutputNames.isSafe(it))
        }
    }

    @Test
    fun `anything that could traverse is refused`() {
        listOf("../escape", "..", "a/b", "a\\b", "/abs", "./x").forEach {
            assertFalse(it, OutputNames.isSafe(it))
        }
    }

    @Test
    fun `names that would break the shell loop are refused`() {
        // The wrapper iterates names with a space-separated `for`, so a
        // space or a shell metacharacter would split or expand.
        listOf("two words", "a;rm -rf /", "a\$b", "a*b", "a|b", "a\nb", "").forEach {
            assertFalse(it.ifEmpty { "<empty>" }, OutputNames.isSafe(it))
        }
    }

    @Test
    fun `a hidden file is refused`() {
        assertFalse(OutputNames.isSafe(".bashrc"))
    }

    @Test
    fun `an absurdly long name is refused`() {
        assertFalse(OutputNames.isSafe("a".repeat(200) + ".pdf"))
    }
}
