package io.github.mangi.eta.ui.app

import android.app.Application
import android.net.Uri
import io.github.mangi.eta.agent.terminal.FileToolBackend
import io.github.mangi.eta.agent.terminal.FileToolStat
import io.github.mangi.eta.agent.terminal.FileToolKind
import io.github.mangi.eta.agent.terminal.LocalFileToolBackend
import org.junit.Assert.assertArrayEquals
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WorkspaceFileStoreTest {
    @Test
    fun exportUsesThePrivilegedBackendForAnAppUnreadableArtifact() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val bytes = ByteArray(70_000) { (it % 256).toByte() }
        var reads = 0
        val store = WorkspaceFileStore(context) { root ->
            object : FileToolBackend by LocalFileToolBackend(root) {
                override val identity = "root"
                override fun stat(path: String) = FileToolStat(path, "root.bin", FileToolKind.FILE, bytes.size.toLong(), "revision")
                override fun readBytes(path: String, offset: Long, limit: Int): ByteArray {
                    reads++
                    return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + limit))
                }
            }
        }
        val destination = File(context.cacheDir, "export.bin")
        store.exportFile("root.bin", Uri.fromFile(destination))
        assertArrayEquals(bytes, destination.readBytes())
        assertTrue(reads > 1)
    }

    @Test
    fun freshWorkspaceCanBeListedWithoutRuntimeInitialization() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = WorkspaceFileStore(context)

        assertTrue(store.list("").isEmpty())
        assertTrue(TerminalPrivateStorage.workspace(context.filesDir).isDirectory)
    }

    @Test
    fun existingWorkspaceFilesRemainVisible() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val legacy = File(context.filesDir, "terminal/workspace").apply { mkdirs() }
        File(legacy, "saved.txt").writeText("saved")

        val entries = WorkspaceFileStore(context).list("")

        assertEquals(listOf("saved.txt"), entries.map { it.path })
        assertEquals("saved", File(legacy, "saved.txt").readText())
    }

    @Test
    fun fileManagementOperationsStayWithinTheWorkspace() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = WorkspaceFileStore(context)
        val root = TerminalPrivateStorage.workspace(context.filesDir)
        val folder = "op-${UUID.randomUUID()}"
        val destinationFolder = "dest-${UUID.randomUUID()}"

        store.createDirectory("", folder)
        store.createDirectory("", destinationFolder)
        File(root, "$folder/source.txt").writeText("hello workspace", Charsets.UTF_8)
        store.rename("$folder/source.txt", "renamed.txt")

        val preview = store.preview("$folder/renamed.txt")
        assertEquals("hello workspace", preview.text)
        assertFalse(preview.binary)

        store.copyInto("$folder/renamed.txt", destinationFolder)
        assertTrue(File(root, "$destinationFolder/renamed.txt").isFile)

        store.delete("$folder/renamed.txt")
        store.moveInto("$destinationFolder/renamed.txt", folder)
        assertFalse(File(root, "$destinationFolder/renamed.txt").exists())
        assertTrue(File(root, "$folder/renamed.txt").isFile)

        store.delete("$folder/renamed.txt")
        store.delete(folder)
        store.delete(destinationFolder)
        assertFalse(File(root, folder).exists())
        assertFalse(File(root, destinationFolder).exists())
    }

    @Test
    fun pathTraversalIsRejectedBeforeTheBackendRuns() = runBlocking {
        val store = WorkspaceFileStore(RuntimeEnvironment.getApplication())
        val rejected = runCatching { store.list("../outside") }.exceptionOrNull()

        assertTrue(rejected is IllegalArgumentException)
        assertEquals("WORKSPACE_PATH_OUTSIDE_ROOT", rejected?.message)
    }

    @Test
    fun copyReportsAnExistingDestinationWithoutOverwritingIt() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = WorkspaceFileStore(context)
        val root = TerminalPrivateStorage.workspace(context.filesDir)
        val sourceFolder = "source-${UUID.randomUUID()}"
        val destinationFolder = "target-${UUID.randomUUID()}"
        store.createDirectory("", sourceFolder)
        store.createDirectory("", destinationFolder)
        File(root, "$sourceFolder/report.txt").writeText("source")
        File(root, "$destinationFolder/report.txt").writeText("existing")

        val error = runCatching { store.copyInto("$sourceFolder/report.txt", destinationFolder) }.exceptionOrNull()

        assertTrue(error is io.github.mangi.eta.agent.terminal.FileToolException)
        assertEquals("existing", File(root, "$destinationFolder/report.txt").readText())
        store.delete(sourceFolder)
        store.delete(destinationFolder)
    }
}
