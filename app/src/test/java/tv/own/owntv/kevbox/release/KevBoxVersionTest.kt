package tv.own.owntv.kevbox.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class KevBoxVersionTest {
    @Test
    fun `code follows A*1000000 + B*10000 + C*100 + K`() {
        assertEquals(5010001, KevBoxVersion.code("5.1.0.1"))
        assertEquals(5010002, KevBoxVersion.code("5.1.0.2"))
        assertEquals(5020001, KevBoxVersion.code("5.2.0.1"))
        assertEquals(99999999, KevBoxVersion.code("99.99.99.99"))
    }

    @Test
    fun `parse splits a valid name into its four parts`() {
        assertEquals(KevBoxVersion.Parts(5, 1, 0, 1), KevBoxVersion.parse("5.1.0.1"))
    }

    @Test
    fun `parse rejects K of 0, a segment over 99, three numbers, a v prefix and a suffix`() {
        assertNull(KevBoxVersion.parse("5.1.0.0"))
        assertNull(KevBoxVersion.parse("5.100.0.1"))
        assertNull(KevBoxVersion.parse("5.1.0"))
        assertNull(KevBoxVersion.parse("v5.1.0.1"))
        assertNull(KevBoxVersion.parse("5.1.0.1-beta"))
        assertNull(KevBoxVersion.code("5.1.0.0"))
    }

    @Test
    fun `parse rejects signs, blanks, extra segments and leading zeros`() {
        listOf("+5.1.0.1", "-5.1.0.1", "5..0.1", "", "5.1.0.1.1", "05.1.0.1", " 5.1.0.1", "5.1.0.1 ")
            .forEach { assertNull(it, KevBoxVersion.parse(it)) }
    }

    @Test
    fun `version sh agrees with KevBoxVersion`() {
        val script = File("../tools/kevbox/version.sh")
        assertNotNull("run from app/", script.takeIf { it.isFile })
        val inputs = listOf(
            "5.1.0.1", "5.1.0.2", "5.2.0.1", "99.99.99.99", "0.0.0.1", "10.20.30.40",
            "5.1.0.0", "5.100.0.1", "5.1.0", "v5.1.0.1", "5.1.0.1-beta", "05.1.0.1", "5.1.0.1.1",
        )
        val stderr = File.createTempFile("version-sh", ".err").apply { deleteOnExit() }
        for (input in inputs) {
            val process = ProcessBuilder("bash", script.path, "code", input)
                .redirectError(stderr)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor(10, TimeUnit.SECONDS)) { "version.sh timed out on $input" }
            val expected = KevBoxVersion.code(input)
            if (expected == null) {
                assertEquals("exit code for $input", 2, process.exitValue())
                assertEquals("stdout for $input", "", output)
            } else {
                assertEquals("exit code for $input", 0, process.exitValue())
                assertEquals("code for $input", expected.toString(), output)
            }
        }
    }
}
