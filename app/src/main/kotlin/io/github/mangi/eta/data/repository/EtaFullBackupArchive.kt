package io.github.mangi.eta.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import io.github.mangi.eta.agent.auth.EncryptedProviderCredentialStorage
import io.github.mangi.eta.agent.auth.ProviderCredential
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.fork.a2a.A2ADatabase
import io.github.mangi.eta.fork.a2a.A2AInboundConfigStore
import io.github.mangi.eta.fork.a2a.A2ASecretStore
import io.github.mangi.eta.fork.conversations.ConversationExtensionDatabase
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.fork.meeting.MeetingDatabase
import io.github.mangi.eta.fork.tasks.AgentTaskDatabase
import io.github.mangi.eta.data.model.SpeechCredentials
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Versioned full backup archive.
 *
 * The logical JSON remains the compatibility contract. Complete app data is stored beside it under
 * `data/`, allowing databases, DataStore, SharedPreferences, Keystore ciphertext and workspace
 * artifacts to travel together. Import is staged and applied before Room/DataStore are opened.
 */
internal object EtaFullBackupArchive {
    const val SUFFIX = ".eta-backup"
    const val FORMAT = "eta-full-backup"
    const val SCHEMA_VERSION = 2

    private const val JSON_ENTRY = "eta-backup.json"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val DATA_PREFIX = "data/"
    private const val CONTROL_DIR = "eta_restore_control"
    private const val MAX_ENTRIES = 200_000
    private const val MAX_ARCHIVE_BYTES = 8L * 1024L * 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 16L * 1024L * 1024L
    private val roots = listOf("databases", "shared_prefs", "files", "no_backup")
    private val excludedPaths = setOf(
        CONTROL_DIR,
        "files/eta-restore-staging",
        "files/eta-restore-rollback",
        "no_backup/eta-portable-secrets.json",
    )
    private const val PORTABLE_SECRETS_ENTRY = "data/no_backup/eta-portable-secrets.json"

    private val json = EtaBackupJsonStreams.json

    /** Export a full archive. A non-empty password encrypts the entire ZIP container. */
    suspend fun export(
        context: Context,
        output: OutputStream,
        password: String? = null,
        onPhase: (String) -> Unit = {},
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val secret = password?.takeIf(String::isNotEmpty)
        var phase = "checkpoint_databases"
        try {
            onPhase(phase)
            checkpointDatabases(appContext)
            val recordCounts = collectRecordCounts(appContext)
            val jsonFile = File(appContext.cacheDir, "eta-backup-${UUID.randomUUID()}.json")
            val secretsFile = File(appContext.cacheDir, "eta-secrets-${UUID.randomUUID()}.json")
            val entries = mutableListOf<EtaBackupEntryManifest>()
            var privateBytes = 0L
            var privateFiles = 0
            try {
                phase = "logical_snapshot"
                onPhase(phase)
                val logical = jsonFile.outputStream().buffered().use {
                    EtaBackupRepository.export(appContext, it)
                }
                val sink = if (secret == null) output else EtaBackupEncryptedOutputStream(output, secret)
                val zip = ZipOutputStream(sink, Charsets.UTF_8)
                try {
                    phase = "logical_snapshot"
                    entries += writeFileEntry(zip, JSON_ENTRY, jsonFile, "core", logical.conversationCount)

                    roots.map { File(appContext.dataDir, it) }
                        .filter(File::isDirectory)
                        .forEach { root ->
                            zip.putNextEntry(ZipEntry("$DATA_PREFIX${root.name}/"))
                            zip.closeEntry()
                        }

                    phase = "private_data"
                    onPhase(phase)
                    collectBackupFiles(appContext).forEach { file ->
                        currentCoroutineContext().ensureActive()
                        val relative = file.relativeTo(appContext.dataDir).invariantSeparatorsPath
                        val entry = writeFileEntry(
                            zip = zip,
                            archivePath = DATA_PREFIX + relative,
                            file = file,
                            section = sectionFor(relative),
                        )
                        entries += entry
                        privateFiles++
                        privateBytes += entry.size
                    }

                    phase = "portable_secrets"
                    onPhase(phase)
                    capturePortableSecrets(appContext).let { secrets ->
                        secretsFile.outputStream().buffered().use { output ->
                            output.write(json.encodeToString(secrets).toByteArray(Charsets.UTF_8))
                        }
                        val entry = writeFileEntry(
                            zip = zip,
                            archivePath = PORTABLE_SECRETS_ENTRY,
                            file = secretsFile,
                            section = "secrets",
                        )
                        entries += entry
                        privateFiles++
                        privateBytes += entry.size
                    }

                    phase = "manifest"
                    onPhase(phase)
                    val manifest = createManifest(
                        context = appContext,
                        logical = logical,
                        entries = entries,
                        recordCounts = recordCounts,
                        encrypted = secret != null,
                    )
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    zip.finish()
                    zip.flush()
                    if (sink is EtaBackupEncryptedOutputStream) sink.finish()
                    output.flush()
                } finally {
                    // Closing ZipOutputStream would close a caller-owned SAF stream.
                    runCatching { zip.finish() }
                }
                logical.copy(
                    privateFileCount = privateFiles,
                    privateBytes = privateBytes,
                    encrypted = secret != null,
                )
            } finally {
                jsonFile.delete()
                secretsFile.delete()
            }
        } catch (failure: Throwable) {
            logFailure("export", phase, failure)
            throw failure
        }
    }

    /** Re-encrypt portable credentials with this device's Android Keystore after a swap restore. */
    suspend fun restorePortableSecretsAfterStartup(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val file = File(appContext.noBackupFilesDir, "eta-portable-secrets.json")
        if (!file.isFile) return@withContext
        if (file.length() > 4L * 1024L * 1024L) {
            throw EtaBackupException("迁移凭据条目超过大小限制")
        }
        val secrets = json.decodeFromString<EtaPortableSecrets>(file.readText(Charsets.UTF_8))
        val providerStorage = EncryptedProviderCredentialStorage(appContext)
        secrets.providerCredentials.forEach(providerStorage::write)
        val mcpStorage = McpSecretStore(appContext)
        secrets.mcpBearerTokens.forEach(mcpStorage::setBearerToken)
        val a2aStorage = A2ASecretStore(appContext)
        secrets.a2aBearerTokens.forEach { (alias, token) ->
            if (token.isNotBlank()) a2aStorage.save(alias, token)
        }
        secrets.a2aInboundToken?.takeIf(String::isNotBlank)?.let { token ->
            a2aStorage.save("a2a-inbound-v1", token)
        }
        secrets.speechCredentials?.let { SpeechSettingsRepository.saveCredentials(appContext, it) }
        if (!file.delete()) throw IOException("迁移凭据已恢复，但临时文件清理失败")
        AndroidAgentLogger.info("Eta portable backup credentials restored")
    }

    /**
     * Import either a legacy JSON document or a new full archive. Full archives are validated and
     * staged; they are applied on the next process start before Room and DataStore are initialized.
     */
    suspend fun import(
        context: Context,
        input: InputStream,
        password: String? = null,
        onCommitted: () -> Unit = {},
        forgetCredentials: (String) -> Unit = ProviderCredentialRepository::forget,
        onPhase: (String) -> Unit = {},
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val probe = PushbackInputStream(BufferedInputStream(input), 64)
        val prefix = readPrefix(probe, 64)
        probe.unread(prefix)
        when {
            isJson(prefix) -> EtaBackupRepository.import(
                context = appContext,
                input = probe,
                onCommitted = onCommitted,
                forgetCredentials = forgetCredentials,
            )
            EtaBackupEncryptedInputStream.hasMagic(prefix) -> {
                val secret = password?.takeIf(String::isNotEmpty)
                    ?: throw EtaBackupException("此备份已加密，请输入备份密码")
                EtaBackupEncryptedInputStream(probe, secret).use { decrypted ->
                    stage(appContext, decrypted, encrypted = true, onPhase = onPhase)
                }
            }
            isZip(prefix) -> stage(appContext, probe, encrypted = false, onPhase = onPhase)
            else -> throw EtaBackupException("无法识别备份格式")
        }
    }

    suspend fun inspect(
        context: Context,
        input: InputStream,
        password: String? = null,
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val probe = PushbackInputStream(BufferedInputStream(input), 64)
        val prefix = readPrefix(probe, 64)
        probe.unread(prefix)
        when {
            isJson(prefix) -> EtaBackupRepository.inspect(appContext, probe)
            EtaBackupEncryptedInputStream.hasMagic(prefix) -> {
                val secret = password?.takeIf(String::isNotEmpty)
                    ?: throw EtaBackupException("此备份已加密，请输入备份密码")
                EtaBackupEncryptedInputStream(probe, secret).use { decrypted ->
                    inspectArchive(appContext, decrypted, encrypted = true)
                }
            }
            isZip(prefix) -> inspectArchive(appContext, probe, encrypted = false)
            else -> throw EtaBackupException("无法识别备份格式")
        }
    }

    /** Must be called from Application.onCreate before any Room/DataStore consumer is initialized. */
    fun applyPendingRestore(context: Context): Boolean {
        val appContext = context.applicationContext
        val control = File(appContext.dataDir, CONTROL_DIR)
        val stateFile = File(control, "state")
        val staged = File(control, "staged")
        val rollback = File(control, "rollback")
        val state = stateFile.takeIf(File::isFile)?.readText()?.trim()
        if (state == "applying") {
            return runCatching {
                restoreRoots(appContext, rollback)
                staged.deleteRecursively()
                rollback.deleteRecursively()
                stateFile.delete()
                false
            }.getOrElse {
                AndroidAgentLogger.error("Interrupted full restore could not roll back: type=${it.safeLogType()}\n${it.safeStackTrace()}")
                false
            }
        }
        if (state == "committed") {
            staged.deleteRecursively()
            rollback.deleteRecursively()
            stateFile.delete()
            return true
        }
        if (!staged.isDirectory) return false

        val stagedManifest = runCatching { readAndVerifyManifest(staged, expectedEncrypted = null) }
            .getOrElse { failure ->
                staged.deleteRecursively()
                stateFile.delete()
                AndroidAgentLogger.error(
                    "Pending full restore failed validation: type=${failure.safeLogType()}",
                )
                return false
            }
        if (stagedManifest.format != FORMAT) return false

        stateFile.writeText("applying")
        return try {
            rollback.deleteRecursively()
            check(rollback.mkdirs()) { "无法创建恢复快照目录" }
            roots.forEach { root ->
                val source = File(appContext.dataDir, root)
                if (source.exists()) movePath(source, File(rollback, root))
            }
            roots.forEach { root ->
                val source = File(staged, DATA_PREFIX + root)
                if (source.exists()) movePath(source, File(appContext.dataDir, root))
            }
            stateFile.writeText("committed")
            staged.deleteRecursively()
            rollback.deleteRecursively()
            stateFile.delete()
            AndroidAgentLogger.info("Eta full backup restore applied before database initialization")
            true
        } catch (failure: Throwable) {
            runCatching { restoreRoots(appContext, rollback) }
                .onFailure(failure::addSuppressed)
            staged.deleteRecursively()
            stateFile.delete()
            AndroidAgentLogger.error("Eta full backup restore failed: type=${failure.safeLogType()}\n${failure.safeStackTrace()}")
            throw failure
        }
    }

    private suspend fun stage(
        context: Context,
        input: InputStream,
        encrypted: Boolean,
        onPhase: (String) -> Unit,
    ): EtaBackupSummary {
        val temp = File(context.cacheDir, "eta-full-restore-${UUID.randomUUID()}").apply {
            if (!mkdirs()) throw IOException("无法创建备份校验目录")
        }
        try {
            onPhase("extract")
            extract(input, temp)
            onPhase("verify_manifest")
            val manifest = readAndVerifyManifest(temp, encrypted)
            onPhase("validate_logical_data")
            val jsonEntry = File(temp, JSON_ENTRY)
            if (!jsonEntry.isFile) throw EtaBackupException("完整备份缺少逻辑数据")
            val logical = EtaBackupJsonStreams.stage(context, jsonEntry.inputStream()).use {
                EtaBackupValidation.validate(it)
            }

            val control = File(context.dataDir, CONTROL_DIR)
            control.mkdirs()
            onPhase("safety_snapshot")
            val safety = File(control, "pre-restore${SUFFIX}")
            val safetyTemp = File(control, "pre-restore.tmp")
            safetyTemp.delete()
            safetyTemp.outputStream().buffered().use { export(context, it, password = null) }
            movePath(safetyTemp, safety)

            onPhase("stage_restore")
            val staged = File(control, "staged")
            val stagedNew = File(control, "staged.new")
            stagedNew.deleteRecursively()
            if (!stagedNew.mkdirs()) throw IOException("无法创建恢复暂存目录")
            File(temp, DATA_PREFIX.trimEnd('/')).takeIf(File::exists)?.let {
                movePath(it, File(stagedNew, DATA_PREFIX.trimEnd('/')))
            }
            File(temp, MANIFEST_ENTRY).copyTo(File(stagedNew, MANIFEST_ENTRY), overwrite = true)
            File(temp, JSON_ENTRY).copyTo(File(stagedNew, JSON_ENTRY), overwrite = true)
            staged.deleteRecursively()
            movePath(stagedNew, staged)
            File(control, "state").writeText("pending")
            AndroidAgentLogger.info(
                "Eta full backup staged: sections=${manifest.sections.size} entries=${manifest.entries.size}",
            )
            return logical.copy(
                privateFileCount = manifest.entries.count { it.path.startsWith(DATA_PREFIX) },
                privateBytes = manifest.entries.sumOf { it.size },
                encrypted = encrypted,
                restartRequired = true,
            )
        } catch (failure: Throwable) {
            if (failure is CancellationException || failure is Error) throw failure
            if (failure is EtaBackupException) throw failure
            throw EtaBackupException("完整备份导入失败，现有数据未替换", failure)
        } finally {
            temp.deleteRecursively()
        }
    }

    private suspend fun inspectArchive(
        context: Context,
        input: InputStream,
        encrypted: Boolean,
    ): EtaBackupSummary {
        val temp = File(context.cacheDir, "eta-full-inspect-${UUID.randomUUID()}").apply {
            if (!mkdirs()) throw IOException("无法创建备份校验目录")
        }
        try {
            extract(input, temp)
            val manifest = readAndVerifyManifest(temp, encrypted)
            val jsonEntry = File(temp, JSON_ENTRY)
            val summary = EtaBackupJsonStreams.stage(context, jsonEntry.inputStream()).use {
                EtaBackupValidation.validate(it)
            }
            return summary.copy(
                privateFileCount = manifest.entries.count { it.path != JSON_ENTRY },
                privateBytes = manifest.entries.sumOf { it.size },
                encrypted = encrypted,
            )
        } finally {
            temp.deleteRecursively()
        }
    }

    private suspend fun checkpointDatabases(context: Context) {
        listOf(
            EtaDatabase.get(context),
            MeetingDatabase.get(context),
            ConversationExtensionDatabase.get(context),
            LearningDatabase.get(context),
            AgentTaskDatabase.get(context),
            A2ADatabase.get(context),
        ).forEach { database ->
            currentCoroutineContext().ensureActive()
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                if (!cursor.moveToFirst()) {
                    throw EtaBackupException("数据库快照未返回检查点状态")
                }
                if (cursor.getInt(0) != 0) {
                    throw EtaBackupException("数据库仍有活动写入，请稍后重试完整备份")
                }
            }
        }
    }

    private fun collectRecordCounts(context: Context): Map<String, Int> {
        val counts = SECTION_NAMES.associateWithTo(linkedMapOf()) { 0 }
        countTables(EtaDatabase.get(context)) { table ->
            when {
                table.startsWith("runtime_") -> "runtime"
                table == "mcp_servers" || table.startsWith("skill_") -> "mcp_skills"
                else -> "core"
            }
        }.forEach { (section, count) ->
            counts[section] = counts.getValue(section) + count
        }
        listOf<Pair<String, RoomDatabase>>(
            "meeting" to MeetingDatabase.get(context),
            "conversation_extensions" to ConversationExtensionDatabase.get(context),
            "learning" to LearningDatabase.get(context),
            "tasks" to AgentTaskDatabase.get(context),
            "a2a" to A2ADatabase.get(context),
        ).forEach { (section, database) ->
            countTables(database) { section }.forEach { (_, count) ->
                counts[section] = counts.getValue(section) + count
            }
        }
        val notificationPath = context.getDatabasePath("eta_notification_history.db")
        if (notificationPath.isFile) {
            counts["notifications"] = runCatching<Int> {
                SQLiteDatabase.openDatabase(
                    notificationPath.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { database ->
                    database.rawQuery("SELECT COUNT(*) FROM notification_history", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                }
            }.getOrDefault(0)
        }
        return counts
    }

    private fun countTables(
        database: RoomDatabase,
        sectionForTable: (String) -> String,
    ): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val sqlite = database.openHelper.readableDatabase
        val tables = mutableListOf<String>()
        sqlite.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table'",
        ).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        tables.forEach { table ->
            val escaped = table.replace("\"", "\"\"")
            val count = sqlite.query("SELECT COUNT(*) FROM \"$escaped\"").use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            val section = sectionForTable(table)
            counts[section] = counts.getOrDefault(section, 0) + count
        }
        return counts
    }

    private fun collectBackupFiles(context: Context): List<File> = roots.asSequence()
        .map { File(context.dataDir, it) }
        .filter(File::isDirectory)
        .flatMap { root ->
            root.walkTopDown().filter(File::isFile).filter { file ->
                val relative = file.relativeTo(context.dataDir).invariantSeparatorsPath
                excludedPaths.none { relative == it || relative.startsWith("$it/") }
            }
        }
        .sortedBy { it.relativeTo(context.dataDir).invariantSeparatorsPath }
        .toList()

    /**
     * Android Keystore keys do not travel with app data. Capture the decrypted values in a temporary
     * migration payload so [restorePortableSecretsAfterStartup] can re-encrypt them with the key of
     * the destination device. The payload is only stored inside the backup container and is never
     * scanned from the live data roots.
     */
    private suspend fun capturePortableSecrets(context: Context): EtaPortableSecrets {
        val providerCredentials = linkedMapOf<String, ProviderCredential>()
        val providerStorage = EncryptedProviderCredentialStorage(context)
        var unreadableProviderCredentials = 0
        EtaDatabase.get(context).providerDao().providers().forEach { record ->
            val providerId = record.provider.id
            runCatching { providerStorage.read(providerId) }
                .onSuccess { credential ->
                    if (credential != null) providerCredentials[providerId] = credential
                }
                .onFailure { unreadableProviderCredentials++ }
        }

        val mcpBearerTokens = linkedMapOf<String, String>()
        val mcpStorage = McpSecretStore(context)
        EtaDatabase.get(context).mcpServerDao().servers().forEach { server ->
            mcpStorage.bearerToken(server.id)?.let { token -> mcpBearerTokens[server.id] = token }
        }

        val a2aBearerTokens = linkedMapOf<String, String>()
        val a2aStorage = A2ASecretStore(context)
        var unreadableA2ATokens = 0
        A2ADatabase.get(context).openHelper.readableDatabase.query(
            "SELECT authSecretAlias FROM a2a_agents WHERE authSecretAlias IS NOT NULL",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val alias = cursor.getString(0)
                runCatching { a2aStorage.load(alias) }
                    .onSuccess { token ->
                        if (!token.isNullOrBlank()) a2aBearerTokens[alias] = token
                    }
                    .onFailure { unreadableA2ATokens++ }
            }
        }

        val speechCredentials = runCatching { SpeechSettingsRepository.credentials(context) }
            .onFailure {
                AndroidAgentLogger.warn(
                    "Eta backup skipped unreadable speech credentials: type=${it.safeLogType()}",
                )
            }
            .getOrNull()

        if (unreadableProviderCredentials > 0 || unreadableA2ATokens > 0) {
            AndroidAgentLogger.warn(
                "Eta backup skipped unreadable portable credentials: " +
                    "providers=$unreadableProviderCredentials a2a=$unreadableA2ATokens",
            )
        }
        return EtaPortableSecrets(
            providerCredentials = providerCredentials,
            mcpBearerTokens = mcpBearerTokens,
            a2aBearerTokens = a2aBearerTokens,
            a2aInboundToken = A2AInboundConfigStore(context).loadToken(),
            speechCredentials = speechCredentials,
        )
    }

    private suspend fun writeFileEntry(
        zip: ZipOutputStream,
        archivePath: String,
        file: File,
        section: String,
        recordCount: Int = 0,
    ): EtaBackupEntryManifest {
        zip.putNextEntry(ZipEntry(archivePath).apply { time = file.lastModified() })
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                zip.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                size += count
            }
        }
        zip.closeEntry()
        return EtaBackupEntryManifest(
            path = archivePath,
            section = section,
            size = size,
            sha256 = digest.digest().hex(),
            recordCount = recordCount,
        )
    }

    private fun createManifest(
        context: Context,
        logical: EtaBackupSummary,
        entries: List<EtaBackupEntryManifest>,
        recordCounts: Map<String, Int>,
        encrypted: Boolean,
    ): EtaFullBackupManifest {
        val sectionNames = SECTION_NAMES
        val sections = sectionNames.map { name ->
            val sectionEntries = entries.filter { it.section == name }
            val digest = MessageDigest.getInstance("SHA-256")
            sectionEntries.sortedBy { it.path }.forEach { entry ->
                digest.update(entry.path.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(entry.sha256.toByteArray(Charsets.US_ASCII))
                digest.update('\n'.code.toByte())
            }
            EtaBackupSectionManifest(
                name = name,
                present = sectionEntries.isNotEmpty() || (recordCounts[name] ?: 0) > 0,
                entryCount = sectionEntries.size,
                files = sectionEntries.size,
                bytes = sectionEntries.sumOf { it.size },
                sha256 = digest.digest().hex(),
                recordCount = recordCounts[name] ?: 0,
            )
        }
        return EtaFullBackupManifest(
            format = FORMAT,
            schemaVersion = SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            }.getOrElse { "" },
            encrypted = encrypted,
            roots = roots.map { EtaBackupRootManifest(it, File(context.dataDir, it).exists()) },
            sections = sections,
            entries = entries,
            totalEntries = entries.size,
            totalBytes = entries.sumOf { it.size },
            providerCount = logical.providerCount,
            modelCount = logical.modelCount,
            conversationCount = logical.conversationCount,
            messageCount = logical.messageCount,
        )
    }

    private suspend fun extract(input: InputStream, target: File) {
        val seen = mutableSetOf<String>()
        var total = 0L
        var count = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    val path = normalizeEntry(entry.name).trimEnd('/')
                    if (path.startsWith(DATA_PREFIX) &&
                        path.removePrefix(DATA_PREFIX).substringBefore('/') in roots
                    ) {
                        File(target, path).mkdirs()
                    }
                    zip.closeEntry()
                    continue
                }
                count++
                if (count > MAX_ENTRIES) throw EtaBackupException("备份条目过多")
                val path = normalizeEntry(entry.name)
                if (path != JSON_ENTRY && path != MANIFEST_ENTRY && !path.startsWith(DATA_PREFIX)) {
                    throw EtaBackupException("备份包含不支持的条目")
                }
                if (!seen.add(path)) throw EtaBackupException("备份包含重复条目")
                val output = File(target, path)
                check(output.canonicalFile.toPath().startsWith(target.canonicalFile.toPath())) {
                    "备份条目路径无效"
                }
                output.parentFile?.mkdirs()
                val limit = if (path == MANIFEST_ENTRY) MAX_MANIFEST_BYTES else MAX_ARCHIVE_BYTES
                output.outputStream().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    var entryBytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        total += read
                        if (entryBytes > limit || total > MAX_ARCHIVE_BYTES) {
                            throw EtaBackupException("备份解压后超过大小限制")
                        }
                        out.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun readAndVerifyManifest(
        directory: File,
        expectedEncrypted: Boolean?,
    ): EtaFullBackupManifest {
        val file = File(directory, MANIFEST_ENTRY)
        if (!file.isFile || file.length() > MAX_MANIFEST_BYTES) {
            throw EtaBackupException("完整备份缺少有效清单")
        }
        val manifest = runCatching {
            json.decodeFromString<EtaFullBackupManifest>(file.readText(Charsets.UTF_8))
        }.getOrElse { throw EtaBackupException("完整备份清单格式无效", it) }
        if (manifest.format != FORMAT || manifest.schemaVersion !in 1..SCHEMA_VERSION) {
            throw EtaBackupException("不支持的完整备份版本")
        }
        if (expectedEncrypted != null && manifest.encrypted != expectedEncrypted) {
            throw EtaBackupException("备份加密状态与清单不一致")
        }
        if (manifest.entries.map { normalizeEntry(it.path) }.toSet().size != manifest.entries.size) {
            throw EtaBackupException("备份清单包含重复条目")
        }
        if (manifest.sections.map { it.name }.toSet() != SECTION_NAMES.toSet() ||
            manifest.sections.size != SECTION_NAMES.size
        ) {
            throw EtaBackupException("备份清单缺少必要分区")
        }
        if (manifest.roots.map { it.name }.toSet() != roots.toSet() ||
            manifest.roots.size != roots.size
        ) {
            throw EtaBackupException("备份清单缺少必要数据根目录")
        }
        manifest.roots.forEach { root ->
            val present = File(directory, DATA_PREFIX + root.name).exists()
            if (root.present != present) throw EtaBackupException("备份数据根目录状态不一致")
        }
        if (manifest.entries.any { it.section !in SECTION_NAMES || it.recordCount < 0 }) {
            throw EtaBackupException("备份清单包含无效分区")
        }
        if (manifest.schemaVersion >= 2 &&
            (manifest.totalEntries != manifest.entries.size ||
                manifest.totalBytes != manifest.entries.sumOf { it.size })
        ) {
            throw EtaBackupException("备份清单总计数校验失败")
        }
        manifest.entries.forEach { entry ->
            val path = normalizeEntry(entry.path)
            if (path != JSON_ENTRY && !path.startsWith(DATA_PREFIX)) {
                throw EtaBackupException("备份清单包含不支持的文件")
            }
            val target = File(directory, path)
            if (!target.isFile) throw EtaBackupException("备份文件缺少 ${entry.path}")
            if (target.length() != entry.size) throw EtaBackupException("备份文件大小校验失败")
            if (sha256(target) != entry.sha256) throw EtaBackupException("备份文件哈希校验失败")
        }
        val listed = manifest.entries.mapTo(mutableSetOf()) { normalizeEntry(it.path) }
        directory.walkTopDown().filter(File::isFile).forEach { file ->
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            if (relative != MANIFEST_ENTRY && relative !in listed) {
                throw EtaBackupException("备份包含清单外文件")
            }
        }
        validateSections(directory, manifest)
        return manifest
    }

    private fun validateSections(directory: File, manifest: EtaFullBackupManifest) {
        manifest.sections.forEach { section ->
            val entries = manifest.entries.filter { it.section == section.name }
            if (section.present != (entries.isNotEmpty() || section.recordCount > 0)) {
                throw EtaBackupException("备份分区存在状态无效")
            }
            if (section.files != entries.size || section.bytes != entries.sumOf { it.size }) {
                throw EtaBackupException("备份分区计数校验失败")
            }
            if (section.recordCount < 0 || section.entryCount != entries.size) {
                throw EtaBackupException("备份分区记录计数无效")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            entries.sortedBy { it.path }.forEach { entry ->
                digest.update(entry.path.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(entry.sha256.toByteArray(Charsets.US_ASCII))
                digest.update('\n'.code.toByte())
            }
            if (digest.digest().hex() != section.sha256) throw EtaBackupException("备份分区哈希校验失败")
        }
    }

    private fun restoreRoots(context: Context, rollback: File) {
        roots.forEach { root ->
            val target = File(context.dataDir, root)
            val previous = File(rollback, root)
            // A crash may happen before a root has been moved. Leave that original root intact.
            if (previous.exists()) {
                target.deleteRecursively()
                movePath(previous, target)
            }
        }
    }

    private fun movePath(source: File, target: File) {
        target.parentFile?.mkdirs()
        target.deleteRecursively()
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sectionFor(relative: String): String = when {
        relative.startsWith("databases/eta_meeting.db") -> "meeting"
        relative.startsWith("databases/eta_fork.db") -> "conversation_extensions"
        relative.startsWith("databases/eta_learning.db") -> "learning"
        relative.startsWith("databases/eta_task.db") -> "tasks"
        relative.startsWith("databases/eta_a2a.db") -> "a2a"
        relative.startsWith("databases/eta.db") -> "core"
        relative.startsWith("databases/eta_notification_history.db") -> "notifications"
        relative.startsWith("databases/") -> "runtime"
        relative.startsWith("shared_prefs/eta_mcp") || relative.startsWith("shared_prefs/eta_a2a") ||
            relative.startsWith("shared_prefs/eta_speech_secrets") || relative.startsWith("no_backup/provider_credentials") -> "secrets"
        relative.startsWith("files/datastore/") || relative.startsWith("shared_prefs/") -> "settings"
        relative.startsWith("files/meeting") -> "meeting_artifacts"
        relative.contains("skill", ignoreCase = true) || relative.contains("mcp", ignoreCase = true) -> "mcp_skills"
        else -> "workspace"
    }

    private fun normalizeEntry(raw: String): String {
        val path = raw.replace('\\', '/').trimStart('/')
        if (path.isBlank() || path.contains('\u0000') || path.split('/').any { it == ".." || it == "." }) {
            throw EtaBackupException("备份条目路径无效")
        }
        return path
    }

    private fun readPrefix(input: InputStream, limit: Int): ByteArray {
        val bytes = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val read = input.read(bytes, count, limit - count)
            if (read < 0) break
            count += read
        }
        return bytes.copyOf(count)
    }

    private fun isJson(prefix: ByteArray): Boolean = prefix
        .dropWhile { it == ' '.code.toByte() || it == '\n'.code.toByte() || it == '\r'.code.toByte() || it == '\t'.code.toByte() }
        .firstOrNull() == '{'.code.toByte()

    private fun isZip(prefix: ByteArray): Boolean = prefix.size >= 4 &&
        prefix[0] == 'P'.code.toByte() && prefix[1] == 'K'.code.toByte()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun logFailure(operation: String, phase: String, failure: Throwable) {
        if (failure is CancellationException) return
        AndroidAgentLogger.error(
            "Eta full backup failed: operation=$operation phase=$phase type=${failure.safeLogType()}\n" +
                failure.safeStackTrace(),
        )
    }

    private val SECTION_NAMES = listOf(
        "core",
        "runtime",
        "meeting",
        "meeting_artifacts",
        "conversation_extensions",
        "learning",
        "tasks",
        "a2a",
        "notifications",
        "mcp_skills",
        "settings",
        "secrets",
        "workspace",
    )
}

@Serializable
internal data class EtaFullBackupManifest(
    val format: String,
    val schemaVersion: Int,
    val exportedAt: Long,
    val appVersion: String,
    val encrypted: Boolean,
    val roots: List<EtaBackupRootManifest>,
    val sections: List<EtaBackupSectionManifest>,
    val entries: List<EtaBackupEntryManifest>,
    val totalEntries: Int = 0,
    val totalBytes: Long = 0,
    val providerCount: Int,
    val modelCount: Int,
    val conversationCount: Int,
    val messageCount: Int,
)

@Serializable
internal data class EtaBackupRootManifest(val name: String, val present: Boolean)

@Serializable
internal data class EtaBackupSectionManifest(
    val name: String,
    val present: Boolean,
    val entryCount: Int,
    val files: Int,
    val bytes: Long,
    val sha256: String,
    val recordCount: Int = 0,
)

@Serializable
internal data class EtaBackupEntryManifest(
    val path: String,
    val section: String,
    val size: Long,
    val sha256: String,
    val recordCount: Int = 0,
)

/** Decrypted credentials are held only in memory, the temporary export file, or the backup ZIP. */
@Serializable
internal data class EtaPortableSecrets(
    val providerCredentials: Map<String, ProviderCredential> = emptyMap(),
    val mcpBearerTokens: Map<String, String> = emptyMap(),
    val a2aBearerTokens: Map<String, String> = emptyMap(),
    val a2aInboundToken: String? = null,
    val speechCredentials: SpeechCredentials? = null,
)

/** Streaming AES-256-GCM container. Each frame is independently authenticated. */
internal class EtaBackupEncryptedOutputStream(
    output: OutputStream,
    password: String,
) : FilterOutputStream(output) {
    private val random = SecureRandom()
    private val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
    private val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
    private val key: ByteArray
    private var frame = 0
    private var finished = false

    init {
        val header = ByteBuffer.allocate(MAGIC.size + 4 + 4 + 1 + salt.size + noncePrefix.size)
            .order(ByteOrder.BIG_ENDIAN)
        header.put(MAGIC)
        header.putInt(VERSION)
        header.putInt(ITERATIONS)
        header.put(salt.size.toByte())
        header.put(salt)
        header.put(noncePrefix)
        out.write(header.array())
        key = deriveKey(password, salt, ITERATIONS)
    }

    override fun write(value: Int) = write(byteArrayOf(value.toByte()))

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        check(!finished) { "Encrypted backup stream is finished" }
        var consumed = 0
        while (consumed < length) {
            val count = minOf(FRAME_BYTES, length - consumed)
            writeFrame(buffer.copyOfRange(offset + consumed, offset + consumed + count), final = false)
            consumed += count
        }
    }

    fun finish() {
        if (finished) return
        writeFrame(ByteArray(0), final = true)
        out.writeInt(END_MARKER)
        out.flush()
        key.fill(0)
        finished = true
    }

    override fun close() {
        finish()
        // Deliberately do not close the caller-owned output stream.
    }

    private fun writeFrame(plain: ByteArray, final: Boolean) {
        val iv = frameIv(frame++)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, 0, KEY_BYTES, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val framed = ByteArray(plain.size + 1).also {
            it[0] = if (final) 1 else 0
            plain.copyInto(it, destinationOffset = 1)
        }
        val encrypted = cipher.doFinal(framed)
        out.writeInt(encrypted.size)
        out.write(encrypted)
        framed.fill(0)
        plain.fill(0)
    }

    private fun frameIv(frame: Int): ByteArray = ByteBuffer.allocate(IV_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .put(noncePrefix)
        .putInt(frame)
        .array()

    private companion object {
        val MAGIC = byteArrayOf('E'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte(), 0)
        const val VERSION = 2
        const val ITERATIONS = 180_000
        const val SALT_BYTES = 16
        const val NONCE_PREFIX_BYTES = 8
        const val IV_BYTES = 12
        const val KEY_BYTES = 32
        const val TAG_BITS = 128
        const val FRAME_BYTES = 1024 * 1024
        const val END_MARKER = -1
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal class EtaBackupEncryptedInputStream(
    input: InputStream,
    password: String,
) : InputStream() {
    private val source = input
    private val key: ByteArray
    private val noncePrefix: ByteArray
    private var frame = 0
    private var current = ByteArray(0)
    private var currentOffset = 0
    private var finalFrameSeen = false
    private var ended = false

    init {
        val magic = source.readExact(MAGIC.size)
        if (!magic.contentEquals(MAGIC)) throw EtaBackupException("加密备份文件头无效")
        val version = source.readInt()
        val iterations: Int
        val salt: ByteArray
        when (version) {
            LEGACY_VERSION -> {
                // v1 wrote the low 16 bits of 180_000, then salt before its length. Its key always
                // used the real iteration count, so recover archived v1 files with that value.
                source.readUnsignedShort()
                salt = source.readExact(LEGACY_SALT_BYTES)
                if (source.readUnsignedByte() != LEGACY_SALT_BYTES) {
                    throw EtaBackupException("加密备份盐值无效")
                }
                iterations = LEGACY_ITERATIONS
            }
            VERSION -> {
                iterations = source.readInt()
                if (iterations !in 1_000..1_000_000) throw EtaBackupException("加密备份参数无效")
                val saltLength = source.readUnsignedByte()
                if (saltLength !in 8..64) throw EtaBackupException("加密备份盐值无效")
                salt = source.readExact(saltLength)
            }
            else -> throw EtaBackupException("不支持的加密备份版本")
        }
        noncePrefix = source.readExact(NONCE_PREFIX_BYTES)
        key = deriveKey(password, salt, iterations)
    }

    override fun read(): Int {
        val buffer = ByteArray(1)
        return if (read(buffer, 0, 1) > 0) buffer[0].toInt() and 0xff else -1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (currentOffset >= current.size) {
            if (ended) return -1
            loadFrame()
            if (ended) return -1
        }
        val count = minOf(length, current.size - currentOffset)
        current.copyInto(buffer, offset, currentOffset, currentOffset + count)
        currentOffset += count
        return count
    }

    private fun loadFrame() {
        val encryptedSize = try {
            source.readInt()
        } catch (failure: IOException) {
            throw EtaBackupException("加密备份不完整", failure)
        }
        if (encryptedSize == END_MARKER) {
            if (!finalFrameSeen) throw EtaBackupException("加密备份缺少结束帧")
            ended = true
            return
        }
        if (finalFrameSeen) throw EtaBackupException("加密备份结束帧后存在额外数据")
        if (encryptedSize <= TAG_BITS / 8 || encryptedSize > FRAME_BYTES + TAG_BITS / 8 + 1) {
            throw EtaBackupException("加密备份帧长度无效")
        }
        val encrypted = source.readExact(encryptedSize)
        val iv = ByteBuffer.allocate(IV_BYTES).order(ByteOrder.BIG_ENDIAN).put(noncePrefix).putInt(frame++).array()
        val final = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, 0, KEY_BYTES, "AES"), GCMParameterSpec(TAG_BITS, iv))
            val plain = cipher.doFinal(encrypted)
            val final = plain.firstOrNull() == 1.toByte()
            if (final && plain.size != 1) throw EtaBackupException("加密备份结束帧无效")
            current = if (plain.isEmpty()) ByteArray(0) else plain.copyOfRange(1, plain.size)
            currentOffset = 0
            final
        } catch (failure: Throwable) {
            throw EtaBackupException("备份密码错误或文件已损坏", failure)
        }
        finalFrameSeen = final
    }

    override fun close() {
        key.fill(0)
        source.close()
    }

    companion object {
        private val MAGIC = byteArrayOf('E'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte(), 0)
        private const val LEGACY_VERSION = 1
        private const val VERSION = 2
        private const val LEGACY_ITERATIONS = 180_000
        private const val LEGACY_SALT_BYTES = 16
        private const val NONCE_PREFIX_BYTES = 8
        private const val IV_BYTES = 12
        private const val KEY_BYTES = 32
        private const val TAG_BITS = 128
        private const val FRAME_BYTES = 1024 * 1024
        private const val END_MARKER = -1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun hasMagic(prefix: ByteArray): Boolean = prefix.size >= MAGIC.size &&
            MAGIC.indices.all { prefix[it] == MAGIC[it] }
    }
}

private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

private fun InputStream.readExact(size: Int): ByteArray {
    if (size < 0 || size > 16 * 1024 * 1024) throw EtaBackupException("加密备份长度无效")
    val output = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = read(output, offset, size - offset)
        if (count < 0) throw EtaBackupException("加密备份不完整")
        offset += count
    }
    return output
}

private fun InputStream.readInt(): Int = ByteBuffer.wrap(readExact(4)).order(ByteOrder.BIG_ENDIAN).int
private fun InputStream.readUnsignedShort(): Int = ByteBuffer.wrap(readExact(2)).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
private fun InputStream.readUnsignedByte(): Int = readExact(1)[0].toInt() and 0xff
private fun OutputStream.writeInt(value: Int) = write(
    ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array(),
)

