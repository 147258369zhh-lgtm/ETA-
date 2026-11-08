package io.github.mangi.eta.agent.capability

import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipInputStream

/** A read-only extraction pass used only for plugin inspection. */
internal object CapabilityPluginArchive {
    private const val MAX_ARCHIVE_BYTES = 32L * 1024L * 1024L
    private const val MAX_EXTRACTED_BYTES = 128L * 1024L * 1024L
    private const val MAX_ENTRIES = 2_048
    private const val MAX_PATH_DEPTH = 16

    fun extract(openStream: () -> InputStream, directory: File): String {
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw CapabilityPluginException("无法创建插件临时目录")
        }
        var archiveBytes = 0L
        var extractedBytes = 0L
        var count = 0
        val paths = linkedMapOf<String, Boolean>()
        val digest = MessageDigest.getInstance("SHA-256")
        openStream().use { raw ->
            ZipInputStream(DigestInputStream(raw, digest).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count += 1
                    if (count > MAX_ENTRIES) throw CapabilityPluginException("插件包文件数量超过 $MAX_ENTRIES 个")
                    val relative = normalizePath(entry.name)
                    val collision = Normalizer.normalize(relative, Normalizer.Form.NFC).lowercase(Locale.ROOT)
                    if (paths.containsKey(collision)) throw CapabilityPluginException("插件包包含重复路径：$relative")
                    val ancestors = relative.split('/').indices.drop(1)
                        .map { index -> relative.split('/').take(index).joinToString("/") }
                        .map { Normalizer.normalize(it, Normalizer.Form.NFC).lowercase(Locale.ROOT) }
                    if (ancestors.any { paths[it] == false }) {
                        throw CapabilityPluginException("插件包存在文件与目录冲突：$relative")
                    }
                    paths[collision] = entry.isDirectory
                    val target = File(directory, relative)
                    ensureWithin(directory, target)
                    if (entry.isDirectory) {
                        if (!target.mkdirs() && !target.isDirectory) throw CapabilityPluginException("无法创建插件目录")
                        if (zip.read() != -1) throw CapabilityPluginException("插件目录条目包含数据")
                    } else {
                        target.parentFile?.let { parent ->
                            if (!parent.mkdirs() && !parent.isDirectory) throw CapabilityPluginException("无法创建插件目录")
                        }
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var fileBytes = 0L
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                fileBytes += read
                                archiveBytes += read
                                extractedBytes += read
                                if (fileBytes > MAX_ARCHIVE_BYTES) throw CapabilityPluginException("插件包单个文件过大")
                                if (extractedBytes > MAX_EXTRACTED_BYTES) throw CapabilityPluginException("插件解压内容超过 128 MiB")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        if (count == 0) throw CapabilityPluginException("插件包为空")
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    fun contentRoot(extracted: File): File {
        val children = extracted.listFiles().orEmpty()
        return children.singleOrNull()?.takeIf { it.isDirectory } ?: extracted
    }

    fun deleteTree(target: File) {
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        val root = target.canonicalFile.toPath()
        target.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
            .sortedByDescending { it.path.length }
            .forEach { file ->
                val path = file.canonicalFile.toPath()
                if (path.startsWith(root)) runCatching { Files.deleteIfExists(path) }
            }
    }

    private fun normalizePath(raw: String): String {
        val value = raw.removeSuffix("/")
        if (value.isBlank() || value.startsWith('/') || value.startsWith('\\') ||
            WINDOWS_DRIVE.containsMatchIn(value) || '\\' in value || value.any(Char::isISOControl)
        ) throw CapabilityPluginException("插件包包含不安全路径")
        val segments = value.split('/')
        if (segments.size > MAX_PATH_DEPTH || segments.any { it.isBlank() || it == "." || it == ".." || it.length > 255 }) {
            throw CapabilityPluginException("插件包路径层级无效")
        }
        return segments.joinToString("/")
    }

    private fun ensureWithin(root: File, target: File) {
        val rootPath = root.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        if (targetPath == rootPath || !targetPath.startsWith(rootPath)) {
            throw CapabilityPluginException("插件包试图写出临时目录")
        }
    }

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")
}
