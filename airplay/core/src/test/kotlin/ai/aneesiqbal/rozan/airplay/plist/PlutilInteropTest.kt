package ai.aneesiqbal.rozan.airplay.plist

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Checks our encoders against Apple's own plist code through macOS `plutil`. Skipped where `plutil` is
 * missing (Linux CI); the committed fixtures cover the reading side there.
 */
class PlutilInteropTest {
    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun requirePlutil() {
        assumeTrue(File(PLUTIL).canExecute(), "plutil is only available on macOS")
    }

    private fun plutil(vararg args: Any): String {
        val process =
            ProcessBuilder(listOf(PLUTIL) + args.map { it.toString() })
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.use { it.readBytes() }.decodeToString()
        assertEquals(0, process.waitFor(), "plutil ${args.joinToString(" ")}: $output")
        return output
    }

    @Test
    fun `plutil accepts our output and reads the same values back`() {
        val generator = RandomPlist(Random(99))
        val values =
            List(30) { generator.value() } +
                listOf(
                    // Not -0.0: plutil's XML writer prints it as 0.0.
                    PlistReal(Double.NaN),
                    PlistInteger.ofUnsigned(ULong.MAX_VALUE),
                    PlistInteger(Long.MIN_VALUE),
                    PlistString("a".repeat(70_000)),
                    PlistArray(List(300) { PlistString("s$it") }),
                )
        for ((i, value) in values.withIndex()) {
            val binary = dir.resolve("v$i.bplist")
            val xml = dir.resolve("v$i.xml")
            Files.write(binary, BinaryPlist.encode(value))
            Files.write(xml, XmlPlist.encode(value))
            plutil("-lint", binary)
            plutil("-lint", xml)

            val fromBinary = dir.resolve("v$i.from-binary.xml")
            plutil("-convert", "xml1", "-o", fromBinary, binary)
            assertEquals(value, XmlPlist.decode(Files.readAllBytes(fromBinary)), "plutil's reading of our binary #$i")

            val fromXml = dir.resolve("v$i.from-xml.bplist")
            plutil("-convert", "binary1", "-o", fromXml, xml)
            assertEquals(value, BinaryPlist.decode(Files.readAllBytes(fromXml)), "plutil's reading of our XML #$i")
        }
    }

    private companion object {
        const val PLUTIL = "/usr/bin/plutil"
    }
}
