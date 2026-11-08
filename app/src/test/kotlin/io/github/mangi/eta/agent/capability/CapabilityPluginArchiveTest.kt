package io.github.mangi.eta.agent.capability

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CapabilityPluginArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rejectsParentTraversalWithoutWritingOutsideTheWorkspace() {
        val workspace = File(temporaryFolder.root, "plugin").also(File::mkdirs)
        val outside = File(temporaryFolder.root, "escaped.txt")
        val archive = zip("../escaped.txt" to "escape")

        assertThrows(CapabilityPluginException::class.java) {
            CapabilityPluginArchive.extract({ ByteArrayInputStream(archive) }, workspace)
        }
        assertFalse(outside.exists())
    }

    @Test
    fun rejectsCaseAndUnicodeEquivalentDuplicatePaths() {
        val workspace = File(temporaryFolder.root, "duplicate").also(File::mkdirs)
        val archive = zip(
            "Skills/Éta/SKILL.md" to "one",
            "skills/éta/skill.md" to "two",
        )

        assertThrows(CapabilityPluginException::class.java) {
            CapabilityPluginArchive.extract({ ByteArrayInputStream(archive) }, workspace)
        }
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
