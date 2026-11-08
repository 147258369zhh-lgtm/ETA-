package io.github.mangi.eta.agent.capability

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

/**
 * Installs and manages Operit-compatible plugin containers and plain JS sandbox packages.
 * Package discovery and execution remain separate from installation so a broken package cannot
 * remove its tools from an already running Agent turn.
 */
internal class ManagedToolPackageService(context: Context) {
    private val appContext = context.applicationContext
    private val store = ManagedToolPackageStore(appContext)
    private val root = File(appContext.filesDir, "capability-center/managed-toolpackages")
    private val builtInRoot = "operit-sandbox-packages"
    private val builtInSource = "Operit@16cffb98f52a11dc77bd0a789ffa0b01ed398ee4"

    @Volatile
    private var builtInCache: List<ManagedToolPackage>? = null

    fun list(): List<ManagedToolPackage> = (builtIns() + store.installed()).distinctBy { it.id }
        .map(::applyPreference)
        .sortedWith(compareBy<ManagedToolPackage> { it.removed }.thenBy { it.kind }.thenBy { it.name })

    fun active(): List<ManagedToolPackage> = list().filter { it.enabled && !it.removed && it.loadError == null }

    fun inspect(openStream: () -> InputStream, fileName: String): ManagedToolPackageInspection {
        val safeName = fileName.trim().take(180)
        if (safeName.lowercase(Locale.ROOT).endsWith(".js")) {
            val temporary = File.createTempFile("eta-sandbox-inspect-", ".js", appContext.cacheDir)
            try {
                copyLimited(openStream, temporary, MAX_SCRIPT_BYTES)
                val candidate = ManagedToolPackageParser.parseFlatSandbox(
                    scriptFile = temporary,
                    fallbackName = safeName,
                    source = "local:$safeName",
                    sha256 = ManagedToolPackageParser.sha256(temporary),
                    builtIn = false,
                    storagePath = "",
                )
                return inspection(candidate)
            } finally {
                temporary.delete()
            }
        }
        val temporary = File.createTempFile("eta-toolpkg-inspect-", "", appContext.cacheDir)
        temporary.delete()
        try {
            val sha256 = CapabilityPluginArchive.extract(openStream, temporary)
            val contentRoot = CapabilityPluginArchive.contentRoot(temporary)
            val candidate = ManagedToolPackageParser.parsePlugin(
                root = contentRoot,
                source = "local:$safeName",
                sha256 = sha256,
                storagePath = "",
            )
            return inspection(candidate)
        } finally {
            CapabilityPluginArchive.deleteTree(temporary)
        }
    }

    suspend fun install(
        openStream: () -> InputStream,
        fileName: String,
        replaceExisting: Boolean = false,
    ): ManagedToolPackageInstallOutcome = runCatching {
        val inspection = inspect(openStream, fileName)
        val candidate = inspection.candidate
        if (candidate.toolCount == 0 || (candidate.tools.isEmpty() && candidate.subpackages.all { it.tools.isEmpty() })) {
            throw ManagedToolPackageException("包内没有可注册的工具，已拒绝伪包")
        }
        val currentPackages = builtIns() + store.installed()
        val existing = currentPackages.firstOrNull { it.id.equals(candidate.id, ignoreCase = true) }
        if (existing != null && (!replaceExisting || existing.builtIn)) {
            throw ManagedToolPackageException(
                if (existing.builtIn) "内置沙盒包不能导入覆盖；可使用禁用或恢复内置包"
                else "同一包 ID 已安装，请确认替换旧版本",
            )
        }
        validateRequirements(candidate, currentPackages.filter { it.id != existing?.id })

        val packageId = safeDirectoryName(candidate.id)
        val stage = File(root, ".stage-$packageId-${UUID.randomUUID()}")
        val target = File(root, packageId)
        val backup = File(root, ".backup-$packageId-${UUID.randomUUID()}")
        CapabilityPluginArchive.deleteTree(stage)
        check(stage.mkdirs() || stage.isDirectory) { "无法创建插件安装暂存目录" }
        var movedOld = false
        try {
            if (candidate.kind == ManagedPackageKind.SANDBOX) {
                val script = File(stage, "main.js")
                copyLimited(openStream, script, MAX_SCRIPT_BYTES)
                val staged = ManagedToolPackageParser.parseFlatSandbox(
                    scriptFile = script,
                    fallbackName = fileName,
                    source = "local:${fileName.take(180)}",
                    sha256 = ManagedToolPackageParser.sha256(script),
                    builtIn = false,
                    storagePath = target.absolutePath,
                )
                if (staged.id != candidate.id) throw ManagedToolPackageException("沙盒包在安装时发生变化")
            } else {
                val extracted = File(stage, "content")
                CapabilityPluginArchive.extract(openStream, extracted)
                val contentRoot = CapabilityPluginArchive.contentRoot(extracted)
                copyTree(contentRoot, stage)
                if (extracted.exists()) CapabilityPluginArchive.deleteTree(extracted)
                val staged = ManagedToolPackageParser.parsePlugin(
                    root = stage,
                    source = "local:${fileName.take(180)}",
                    sha256 = candidate.sha256,
                    storagePath = target.absolutePath,
                )
                if (staged.id != candidate.id) throw ManagedToolPackageException("插件在安装时发生变化")
            }

            if (target.exists()) {
                Files.move(target.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
                movedOld = true
            }
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val record = candidate.copy(
                storagePath = target.absolutePath,
                installedAt = System.currentTimeMillis(),
                enabled = candidate.enabledByDefault,
                removed = false,
                disabledTools = emptySet(),
                disabledSubpackages = emptySet(),
            )
            store.upsert(record)
            if (movedOld) CapabilityPluginArchive.deleteTree(backup)
            ManagedToolPackageInstallOutcome.Success(
                packageId = record.id,
                packageName = record.name,
                kind = record.kind,
                toolCount = record.toolCount,
                replaced = existing != null,
            )
        } catch (failure: Throwable) {
            CapabilityPluginArchive.deleteTree(stage)
            if (movedOld && !target.exists() && backup.exists()) {
                runCatching { Files.move(backup.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            }
            throw failure
        }
    }.getOrElse { failure ->
        ManagedToolPackageInstallOutcome.Failure(failure.message ?: "插件安装失败")
    }

    fun setEnabled(packageId: String, enabled: Boolean) {
        val pkg = list().firstOrNull { it.id == packageId }
            ?: throw ManagedToolPackageException("插件或沙盒包不存在")
        if (pkg.removed && enabled) throw ManagedToolPackageException("已删除的包需要先恢复或重新安装")
        if (enabled) validateRequirements(pkg, list().filter { it.id != pkg.id && it.enabled && !it.removed })
        if (pkg.builtIn) {
            val current = store.preference(pkg.id)
            store.savePreference(pkg.id, current.copy(enabled = enabled, removed = false))
        } else {
            store.upsert(pkg.copy(enabled = enabled))
        }
    }

    fun setToolEnabled(packageId: String, toolName: String, enabled: Boolean) {
        val pkg = list().firstOrNull { it.id == packageId }
            ?: throw ManagedToolPackageException("插件或沙盒包不存在")
        val tools = pkg.tools.map { it.name } + pkg.subpackages.flatMap { subpackage -> subpackage.tools.map { it.name } }
        if (toolName !in tools) throw ManagedToolPackageException("工具不属于该包")
        if (pkg.builtIn) {
            val current = store.preference(pkg.id)
            store.savePreference(pkg.id, current.copy(
                disabledTools = if (enabled) current.disabledTools - toolName else current.disabledTools + toolName,
            ))
        } else {
            store.upsert(pkg.copy(
                disabledTools = if (enabled) pkg.disabledTools - toolName else pkg.disabledTools + toolName,
            ))
        }
    }

    fun setSubpackageEnabled(packageId: String, subpackageId: String, enabled: Boolean) {
        val pkg = list().firstOrNull { it.id == packageId }
            ?: throw ManagedToolPackageException("插件或沙盒包不存在")
        if (pkg.subpackages.none { it.id == subpackageId }) {
            throw ManagedToolPackageException("子包不存在")
        }
        if (pkg.builtIn) {
            val current = store.preference(pkg.id)
            store.savePreference(pkg.id, current.copy(
                disabledSubpackages = if (enabled) current.disabledSubpackages - subpackageId
                else current.disabledSubpackages + subpackageId,
            ))
        } else {
            store.upsert(pkg.copy(
                disabledSubpackages = if (enabled) pkg.disabledSubpackages - subpackageId
                else pkg.disabledSubpackages + subpackageId,
            ))
        }
    }

    fun delete(packageId: String) {
        val packages = list()
        val pkg = packages.firstOrNull { it.id == packageId }
            ?: throw ManagedToolPackageException("插件或沙盒包不存在")
        val dependents = packages.filter { candidate ->
            candidate.id != pkg.id && candidate.enabled && !candidate.removed &&
                candidate.requirements.any { it.id.equals(pkg.id, ignoreCase = true) }
        }
        if (dependents.isNotEmpty()) {
            throw ManagedToolPackageException(
                "仍被以下已启用包依赖：${dependents.take(4).joinToString("、") { it.name }}",
            )
        }
        if (pkg.builtIn) {
            val current = store.preference(pkg.id)
            store.savePreference(pkg.id, current.copy(enabled = false, removed = true))
            return
        }
        val directory = pkg.storagePath.takeIf(String::isNotBlank)?.let(::File)
        if (directory != null && directory.exists()) {
            val canonical = directory.canonicalFile.toPath()
            val rootPath = root.canonicalFile.toPath()
            if (!canonical.startsWith(rootPath)) throw ManagedToolPackageException("插件存储路径越界")
        }
        store.delete(pkg.id)
        directory?.let(CapabilityPluginArchive::deleteTree)
    }

    fun restoreBuiltIns() {
        builtIns().forEach { pkg ->
            store.savePreference(pkg.id, ManagedToolPackagePreference())
        }
        builtInCache = null
    }

    fun externalDirectory(): String {
        root.mkdirs()
        return root.absolutePath
    }

    @Synchronized
    private fun builtIns(): List<ManagedToolPackage> {
        builtInCache?.let { return it }
        val result = mutableListOf<ManagedToolPackage>()
        val names = appContext.assets.list(builtInRoot).orEmpty()
            .filter { it.endsWith(".js", ignoreCase = true) }
            .sorted()
        names.forEach { name ->
            val temporary = File.createTempFile("eta-builtin-sandbox-", ".js", appContext.cacheDir)
            try {
                appContext.assets.open("$builtInRoot/$name").use { input ->
                    copyLimited({ input }, temporary, MAX_SCRIPT_BYTES)
                }
                result += ManagedToolPackageParser.parseFlatSandbox(
                    scriptFile = temporary,
                    fallbackName = name,
                    source = builtInSource,
                    sha256 = ManagedToolPackageParser.sha256(temporary),
                    builtIn = true,
                    storagePath = "asset://$builtInRoot/$name",
                )
            } catch (failure: Throwable) {
                result += ManagedToolPackage(
                    id = "operit.sandbox." + name.removeSuffix(".js").lowercase(Locale.ROOT),
                    kind = ManagedPackageKind.SANDBOX,
                    name = name.removeSuffix(".js"),
                    version = builtInSource,
                    builtIn = true,
                    source = builtInSource,
                    storagePath = "asset://$builtInRoot/$name",
                    enabled = false,
                    loadError = failure.message?.take(300) ?: "内置沙盒包解析失败",
                )
            } finally {
                temporary.delete()
            }
        }
        return result.distinctBy { it.id }.also { builtInCache = it }
    }

    private fun inspection(candidate: ManagedToolPackage): ManagedToolPackageInspection {
        val packages = builtIns() + store.installed()
        val existing = packages.firstOrNull { it.id.equals(candidate.id, ignoreCase = true) }
        val warnings = buildList {
            if (existing != null && !existing.builtIn) add("将替换已安装的 v${existing.version}")
            candidate.requirements.forEach { requirement ->
                val dependency = packages.firstOrNull { it.id.equals(requirement.id, ignoreCase = true) }
                if (dependency == null) add("缺少依赖：${requirement.id}")
                else if (!dependency.enabled || dependency.removed) add("依赖尚未启用：${dependency.name}")
            }
            if (candidate.subpackages.isEmpty() && candidate.kind == ManagedPackageKind.PLUGIN) {
                add("容器仅声明运行时 Hook 或主入口工具")
            }
            if (candidate.toolCount == 0) add("未发现模型可调用工具")
            if (candidate.permissions.isNotEmpty()) {
                add("将访问：${candidate.permissions.sorted().joinToString(" · ")}")
            }
        }
        return ManagedToolPackageInspection(
            candidate = candidate,
            warnings = warnings,
            replacesPackageId = existing?.id.takeIf { existing != null && !existing.builtIn },
        )
    }

    private fun applyPreference(pkg: ManagedToolPackage): ManagedToolPackage {
        if (!pkg.builtIn) return pkg
        val preference = store.preference(pkg.id)
        return pkg.copy(
            enabled = preference.enabled ?: pkg.enabledByDefault,
            removed = preference.removed ?: false,
            disabledTools = preference.disabledTools,
            disabledSubpackages = preference.disabledSubpackages,
        )
    }

    private fun validateRequirements(candidate: ManagedToolPackage, packages: List<ManagedToolPackage>) {
        candidate.requirements.forEach { requirement ->
            val dependency = packages.firstOrNull { it.id.equals(requirement.id, ignoreCase = true) }
                ?: throw ManagedToolPackageException("缺少依赖：${requirement.id}")
            if (!dependency.enabled || dependency.removed || dependency.loadError != null) {
                throw ManagedToolPackageException("依赖未启用：${dependency.name}")
            }
            requirement.minVersion?.let { minimum ->
                if (compareVersions(dependency.version, minimum) < 0) {
                    throw ManagedToolPackageException("${dependency.name} 版本过低，至少需要 $minimum")
                }
            }
            requirement.maxVersion?.let { maximum ->
                if (compareVersions(dependency.version, maximum) > 0) {
                    throw ManagedToolPackageException("${dependency.name} 版本过高，最多支持 $maximum")
                }
            }
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.', '-', '+').mapNotNull(String::toIntOrNull)
        val b = right.split('.', '-', '+').mapNotNull(String::toIntOrNull)
        val size = maxOf(a.size, b.size, 3)
        for (index in 0 until size) {
            val comparison = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
            if (comparison != 0) return comparison
        }
        return left.compareTo(right, ignoreCase = true)
    }

    private fun safeDirectoryName(id: String): String = id.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('.', '-')
        .take(100)
        .ifBlank { "package-${UUID.randomUUID()}" }

    private fun copyLimited(openStream: () -> InputStream, target: File, maxBytes: Long) {
        target.parentFile?.let { parent -> check(parent.mkdirs() || parent.isDirectory) { "无法创建插件目录" } }
        var total = 0L
        openStream().use { input ->
            FileOutputStream(target).buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw ManagedToolPackageException("包文件超过 ${maxBytes / 1024 / 1024} MiB")
                    output.write(buffer, 0, count)
                }
            }
        }
        if (total <= 0L) throw ManagedToolPackageException("包文件为空")
    }

    private fun copyTree(source: File, destination: File) {
        source.walkTopDown().forEach { item ->
            val relative = item.relativeTo(source).path
            val target = if (relative == ".") destination else File(destination, relative)
            if (item.isDirectory) {
                check(target.mkdirs() || target.isDirectory) { "无法复制插件目录" }
            } else if (item.isFile) {
                target.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "无法创建插件目录" } }
                Files.copy(item.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                throw ManagedToolPackageException("插件包包含不支持的文件类型")
            }
        }
    }

    private companion object {
        const val MAX_SCRIPT_BYTES = 8L * 1024L * 1024L
    }
}
