package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.fork.a2a.A2ADatabase
import io.github.mangi.eta.fork.conversations.ConversationExtensionDatabase
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.fork.meeting.MeetingDatabase
import io.github.mangi.eta.fork.tasks.AgentTaskDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaFullBackupArchiveTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        closeDatabases()
        context.deleteDatabase("eta.db")
        context.deleteDatabase("eta_meeting.db")
        context.deleteDatabase("eta_fork.db")
        context.deleteDatabase("eta_learning.db")
        context.deleteDatabase("eta_task.db")
        context.deleteDatabase("eta_a2a.db")
        File(context.dataDir, "eta_restore_control").deleteRecursively()
        resetPortableSecretFixtures()
        SettingsDataStore.init(context)
        runBlocking { SettingsDataStore.setSelection(null, null) }
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
    }

    @After
    fun tearDown() {
        File(context.dataDir, "eta_restore_control").deleteRecursively()
        resetPortableSecretFixtures()
        closeDatabases()
    }

    @Test
    fun encryptedContainerRoundTripsFramesAndRejectsWrongPassword() {
        val original = ByteArray(2 * 1024 * 1024 + 17) { index -> (index * 31).toByte() }
        val encrypted = ByteArrayOutputStream()
        EtaBackupEncryptedOutputStream(encrypted, "correct horse battery staple").use { output ->
            output.write(original)
        }

        val restored = EtaBackupEncryptedInputStream(
            ByteArrayInputStream(encrypted.toByteArray()),
            "correct horse battery staple",
        ).use(InputStreamCopy::copy)
        assertTrue(original.contentEquals(restored))

        val failure = runCatching {
            EtaBackupEncryptedInputStream(
                ByteArrayInputStream(encrypted.toByteArray()),
                "wrong password",
            ).use(InputStreamCopy::copy)
        }.exceptionOrNull()
        assertTrue(failure is EtaBackupException)
    }

    @Test
    fun fullArchiveContainsExplicitDatabaseSettingAndArtifactSections() = runBlocking {
        // Opening each store creates the exact files whose consistency the archive is responsible for.
        EtaDatabase.get(context)
        MeetingDatabase.get(context)
        ConversationExtensionDatabase.get(context)
        LearningDatabase.get(context)
        AgentTaskDatabase.get(context)
        A2ADatabase.get(context)
        val artifact = File(context.filesDir, "meeting-artifacts/proof.txt").apply {
            parentFile?.mkdirs()
            writeText("meeting artifact")
        }
        context.getSharedPreferences("eta_a2a_inbound", Context.MODE_PRIVATE).edit()
            .putBoolean("fixture", true).commit()

        val output = ByteArrayOutputStream()
        val summary = EtaFullBackupArchive.export(context, output, password = null)
        assertTrue(summary.conversationCount >= 0)
        assertTrue(summary.privateFileCount > 0)

        var manifest: EtaFullBackupManifest? = null
        val entries = buildSet {
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    add(entry.name)
                    if (entry.name == "manifest.json") {
                        manifest = EtaBackupJsonStreams.json.decodeFromString<EtaFullBackupManifest>(
                            zip.readBytes().toString(Charsets.UTF_8),
                        )
                    }
                }
            }
        }
        assertTrue("data/databases/eta.db" in entries)
        assertTrue("data/databases/eta_meeting.db" in entries)
        assertTrue("data/databases/eta_fork.db" in entries)
        assertTrue("data/databases/eta_learning.db" in entries)
        assertTrue("data/databases/eta_task.db" in entries)
        assertTrue("data/databases/eta_a2a.db" in entries)
        assertTrue("data/shared_prefs/eta_a2a_inbound.xml" in entries)
        assertTrue("data/files/meeting-artifacts/proof.txt" in entries)
        assertTrue("data/no_backup/eta-portable-secrets.json" in entries)
        assertFalse("no_backup/eta-portable-secrets.json" in entries)
        assertTrue("manifest.json" in entries)
        val parsed = requireNotNull(manifest)
        assertEquals(
            setOf(
                "core", "runtime", "meeting", "meeting_artifacts", "conversation_extensions",
                "learning", "tasks", "a2a", "notifications", "mcp_skills", "settings",
                "secrets", "workspace",
            ),
            parsed.sections.mapTo(mutableSetOf()) { it.name },
        )
        assertTrue(parsed.sections.single { it.name == "core" }.present)
        assertTrue(parsed.sections.single { it.name == "meeting" }.present)
        assertTrue(parsed.sections.single { it.name == "secrets" }.present)
        assertTrue(artifact.isFile)
    }

    @Test
    fun encryptedArchiveCanBeInspectedWithoutChangingLiveData() = runBlocking {
        val encrypted = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, encrypted, password = "passphrase")
        val summary = EtaFullBackupArchive.inspect(
            context,
            ByteArrayInputStream(encrypted.toByteArray()),
            password = "passphrase",
        )
        assertTrue(summary.encrypted)
        assertFalse(summary.restartRequired)
    }

    @Test
    fun importEntryPointStillAcceptsLegacyJsonBackups() = runBlocking {
        val legacy = EtaBackupDocument(exportedAt = 0)
        val summary = EtaFullBackupArchive.import(
            context,
            ByteArrayInputStream(
                EtaBackupJsonStreams.json.encodeToString(legacy).toByteArray(Charsets.UTF_8),
            ),
        )
        assertFalse(summary.restartRequired)
        assertEquals(0, summary.conversationCount)
    }

    @Test
    fun fullArchiveImportStagesCompleteDataWithoutChangingLiveFiles() = runBlocking {
        val artifact = File(context.filesDir, "chat-artifacts/import-proof.txt").apply {
            parentFile?.mkdirs()
            writeText("backup-version")
        }
        val archive = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, archive, password = "portable-password")
        artifact.writeText("live-version-after-export")

        val summary = EtaFullBackupArchive.import(
            context,
            ByteArrayInputStream(archive.toByteArray()),
            password = "portable-password",
        )

        assertTrue(summary.restartRequired)
        assertEquals("live-version-after-export", artifact.readText())
        val control = File(context.dataDir, "eta_restore_control")
        assertEquals("pending", File(control, "state").readText())
        assertEquals(
            "backup-version",
            File(control, "staged/data/files/chat-artifacts/import-proof.txt").readText(),
        )
        assertTrue(File(control, "staged/manifest.json").isFile)
        assertTrue(File(control, "pre-restore.eta-backup").isFile)
    }

    @Test
    fun portableCredentialMigrationFileIsParsedAndRemoved() = runBlocking {
        val migration = File(context.noBackupFilesDir, "eta-portable-secrets.json")
        migration.writeText(
            EtaBackupJsonStreams.json.encodeToString(EtaPortableSecrets()),
        )

        EtaFullBackupArchive.restorePortableSecretsAfterStartup(context)

        assertFalse(migration.exists())
    }

    private fun closeDatabases() {
        runCatching { EtaDatabase.closeForTests() }
        runCatching { A2ADatabase.closeForTests() }
        runCatching { ConversationExtensionDatabase.closeForTests() }
        runCatching { AgentTaskDatabase.closeForTests() }
        runCatching { MeetingDatabase.closeForTests() }
        runCatching { LearningDatabase.closeForTests() }
    }

    private fun resetPortableSecretFixtures() {
        File(context.noBackupFilesDir, "eta-portable-secrets.json").delete()
        File(context.noBackupFilesDir, "provider_credentials").deleteRecursively()
        listOf("eta_mcp_secrets", "eta_a2a_secrets", "eta_speech_secrets").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}

private object InputStreamCopy {
    fun copy(input: java.io.InputStream): ByteArray = input.readBytes()
}
