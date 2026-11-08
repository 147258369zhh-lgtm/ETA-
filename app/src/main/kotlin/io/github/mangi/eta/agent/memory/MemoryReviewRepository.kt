package io.github.mangi.eta.agent.memory

import androidx.room.withTransaction
import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.fork.learning.LearningPolicy
import java.util.UUID

/** Candidates become retrievable only after review. Markdown remains an independent editable source. */
internal class MemoryReviewRepository(private val db: LearningDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.memoryDao()
    suspend fun propose(scope: AgentMemoryScope, layer: String, topic: String, content: String,
        evidence: String, source: String, expiresAt: Long? = null): MemoryEntry? = db.withTransaction {
        val combined = "$topic\n$content\n$evidence"
        if (!MemoryAutomationPolicy.allowed(combined) || MemoryAutomationPolicy.sensitive(combined)) return@withTransaction null
        val safeTopic = MemoryAutomationPolicy.safe(topic, 100)
        val safeContent = MemoryAutomationPolicy.safe(content, 1200)
        val safeEvidence = MemoryAutomationPolicy.safe(evidence, 600)
        if (safeTopic.isBlank() || safeContent.length < 4) return@withTransaction null
        val now = clock()
        val entries = dao.entries().filter { it.scope == scope.key }
        val duplicate = entries.firstOrNull {
            MemoryAutomationPolicy.equivalent(it.content, safeContent) &&
                (it.expiresAt == null || it.expiresAt > now || it.status in setOf("rejected", "archived", "superseded"))
        }
        if (duplicate != null) return@withTransaction duplicate
        val conflicts = conflicts(entries, safeTopic, safeContent, now)
        val entry = MemoryEntry(UUID.randomUUID().toString(), scope.key,
            layer.takeIf { it in setOf("core", "project", "task", "long_term", "working", "other") } ?: "long_term",
            safeTopic, safeContent, safeEvidence, MemoryAutomationPolicy.safe(source, 256),
            LearningPolicy.evidenceHash(scope.key + MemoryAutomationPolicy.dedupKey(safeContent)),
            if (conflicts.isEmpty()) "pending" else "conflict", 0.7, now, now, expiresAt,
            signature(conflicts))
        dao.insert(entry)
        entry
    }

    suspend fun decide(id: String, expectedUpdatedAt: Long, action: String, expectedConflicts: String = ""): String? = db.withTransaction {
        val entry = dao.entry(id) ?: return@withTransaction "条目已不存在，请刷新"
        if (entry.updatedAt != expectedUpdatedAt) return@withTransaction "条目已更新，请重新审阅"
        val now = clock().coerceAtLeast(entry.updatedAt + 1)
        if (action == "archive" && entry.status == "active" || action == "reject" && entry.status in setOf("pending", "conflict")) {
            dao.update(entry.copy(status = if (action == "archive") "archived" else "rejected", updatedAt = now))
            return@withTransaction null
        }
        if (action !in setOf("confirm", "replace", "keep_both") || entry.status !in setOf("pending", "conflict"))
            return@withTransaction "该条目当前不能执行此操作"
        if (entry.expiresAt != null && entry.expiresAt <= now) return@withTransaction "条目已过期，请重新提取"
        val conflicts = conflicts(dao.entries().filter { it.scope == entry.scope && it.id != entry.id }, entry.topic, entry.content, now)
        val actual = signature(conflicts)
        if (actual.isNotEmpty() && (action == "confirm" || expectedConflicts != actual)) {
            dao.update(entry.copy(status = "conflict", conflictIds = actual, updatedAt = now))
            return@withTransaction "发现新增或变化的冲突，请对照后重新选择"
        }
        if (action == "replace") conflicts.forEach { dao.update(it.copy(status = "superseded", updatedAt = now)) }
        dao.update(entry.copy(status = "active", updatedAt = now, conflictIds = "", supersedes = if (action == "replace") conflicts.joinToString(",") { it.id } else ""))
        null
    }

    suspend fun edit(id: String, content: String): String? = db.withTransaction {
        val entry = dao.entry(id) ?: return@withTransaction "条目已不存在"
        if (entry.status !in setOf("pending", "conflict")) return@withTransaction "只有待审阅条目可以修改"
        if (content.trim().length !in 4..1200 || MemoryAutomationPolicy.sensitive(content) || !MemoryAutomationPolicy.allowed(content)) return@withTransaction "内容无效或包含敏感字段"
        val safe = MemoryAutomationPolicy.safe(content, 1200)
        val peers = dao.entries().filter { it.scope == entry.scope && it.id != id }
        if (peers.any { MemoryAutomationPolicy.equivalent(it.content, safe) }) return@withTransaction "已存在相同记忆，请处理已有条目"
        val conflicts = conflicts(peers, entry.topic, safe, clock())
        dao.update(entry.copy(content = safe, status = if (conflicts.isEmpty()) "pending" else "conflict",
            fingerprint = LearningPolicy.evidenceHash(entry.scope + MemoryAutomationPolicy.dedupKey(safe)),
            conflictIds = signature(conflicts), updatedAt = clock().coerceAtLeast(entry.updatedAt + 1)))
        null
    }

    suspend fun active(scopes: List<AgentMemoryScope>): List<MemoryEntry> {
        val keys = scopes.map { it.key }.toSet(); val now = clock()
        return dao.entries().filter { it.scope in keys && it.status == "active" && (it.expiresAt == null || it.expiresAt > now) }
    }

    suspend fun context(scopes: List<AgentMemoryScope>, maxChars: Int = 4000): String = active(scopes)
        .sortedBy { if (it.layer == "core") 0 else 1 }.joinToString("\n") { "[${it.scope}] ${it.topic}: ${it.content}" }.take(maxChars)

    suspend fun search(scopes: List<AgentMemoryScope>, query: String, limit: Int = 12): List<MemoryEntry> {
        val needle = MemoryAutomationPolicy.normalize(query)
        if (needle.isEmpty()) return emptyList()
        return active(scopes).map { it to (if (MemoryAutomationPolicy.normalize(it.topic + it.content).contains(needle)) 2.0 else MemoryAutomationPolicy.similarity(it.topic + it.content, query)) }
            .filter { it.second >= 0.08 }.sortedByDescending { it.second }.take(limit.coerceIn(1, 30)).map { it.first }
    }

    fun conflictEntries(entry: MemoryEntry, entries: List<MemoryEntry>): List<MemoryEntry> =
        conflicts(entries.filter { it.scope == entry.scope && it.id != entry.id }, entry.topic, entry.content, clock())

    private fun conflicts(entries: List<MemoryEntry>, topic: String, content: String, now: Long) = entries.filter {
        it.status == "active" && (it.expiresAt == null || it.expiresAt > now) &&
            (MemoryAutomationPolicy.normalize(it.topic) == MemoryAutomationPolicy.normalize(topic) || MemoryAutomationPolicy.similarity(it.content, content) >= 0.56) &&
            !MemoryAutomationPolicy.equivalent(it.content, content)
    }
    private fun signature(entries: List<MemoryEntry>) = entries.sortedBy { it.id }.joinToString(",") { "${it.id}@${it.updatedAt}" }
}
