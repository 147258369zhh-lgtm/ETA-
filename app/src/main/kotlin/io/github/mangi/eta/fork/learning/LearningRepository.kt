package io.github.mangi.eta.fork.learning

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID

internal data class LearningProposalInput(
    val kind: LearningKind,
    val title: String,
    val content: String,
    val evidence: String = "",
    val sourceRef: String = "manual",
)

internal data class LearningEvidenceView(
    val id: String,
    val sourceRef: String,
    val excerpt: String,
    val observedAt: Long,
)

internal data class LearningCandidateView(
    val id: String,
    val kind: LearningKind,
    val title: String,
    val content: String,
    val status: LearningStatus,
    val confidence: Double,
    val sourceRef: String,
    val createdAt: Long,
    val updatedAt: Long,
    val promotedRuleId: String?,
    val evidence: List<LearningEvidenceView>,
)

internal data class LearningRuleVersionView(
    val id: String,
    val version: Int,
    val content: String,
    val reason: String,
    val createdAt: Long,
)

internal data class LearningRuleView(
    val id: String,
    val kind: LearningKind,
    val title: String,
    val content: String,
    val currentVersion: Int,
    val active: Boolean,
    val pinned: Boolean,
    val versions: List<LearningRuleVersionView>,
)

internal data class LearningSnapshot(
    val candidates: List<LearningCandidateView>,
    val rules: List<LearningRuleView>,
)

internal sealed interface LearningProposalResult {
    data class Created(val candidate: LearningCandidateView) : LearningProposalResult
    data class Existing(val candidate: LearningCandidateView) : LearningProposalResult
    data object BlockedByPolicy : LearningProposalResult
    data object Empty : LearningProposalResult
}

internal sealed interface LearningMutationResult {
    data class Success(val snapshot: LearningSnapshot) : LearningMutationResult
    data class Rejected(val reason: String) : LearningMutationResult
}

internal class LearningRepository(
    private val database: LearningDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.learningDao()

    suspend fun propose(input: LearningProposalInput): LearningProposalResult {
        val now = clock()
        val activeRules = dao.activeRules()
        val policy = when (
            val result = LearningPolicy.evaluate(
                kind = input.kind,
                title = input.title,
                content = input.content,
                evidence = input.evidence,
                activeRules = activeRules,
            )
        ) {
            LearningPolicyResult.Blocked -> return LearningProposalResult.BlockedByPolicy
            LearningPolicyResult.Empty -> return LearningProposalResult.Empty
            is LearningPolicyResult.Accepted -> result
        }
        val hash = LearningPolicy.fingerprint(input.kind, policy.title, policy.content)
        val sourceRef = LearningPolicy.sanitizeForStorage(input.sourceRef, 240)
        val evidenceHash = LearningPolicy.evidenceHash("$sourceRef\n${policy.evidence}")
        val evidence = LearningEvidenceEntity(
            id = "",
            candidateId = "",
            sourceRef = sourceRef,
            excerptHash = evidenceHash,
            excerpt = policy.evidence,
            observedAt = now,
        )
        dao.candidateByHash(hash)?.let { existing ->
            dao.insertEvidence(evidence.copy(id = "${existing.id}:$evidenceHash", candidateId = existing.id))
            dao.touchCandidate(existing.id, now)
            return LearningProposalResult.Existing(candidateView(checkNotNull(dao.candidate(existing.id))))
        }
        val candidateId = ids()
        val status = if (policy.conflict == null) LearningStatus.PENDING else LearningStatus.CONFLICT
        val candidate = LearningCandidateEntity(
            id = candidateId,
            kind = input.kind.wireValue,
            title = policy.title,
            content = policy.content,
            contentHash = hash,
            status = status.wireValue,
            confidence = policy.confidence,
            sourceRef = sourceRef,
            createdAt = now,
            updatedAt = now,
        )
        database.withTransaction {
            dao.insertCandidate(candidate)
            dao.insertEvidence(
                evidence.copy(
                    id = "$candidateId:$evidenceHash",
                    candidateId = candidateId,
                ),
            )
        }
        return LearningProposalResult.Created(candidateView(candidate))
    }

    suspend fun confirm(candidateId: String, overrideConflict: Boolean = false): LearningMutationResult {
        val now = clock()
        val outcome = database.withTransaction {
            val candidate = dao.candidate(candidateId)
                ?: return@withTransaction Rejected("学习候选不存在")
            val status = LearningStatus.entries.firstOrNull { it.wireValue == candidate.status }
                ?: return@withTransaction Rejected("学习候选状态无效")
            if (status in setOf(
                    LearningStatus.CONFIRMED,
                    LearningStatus.REJECTED,
                    LearningStatus.PROMOTED,
                )
            ) {
                return@withTransaction Rejected("该候选已结束，不能再次确认")
            }
            if (status == LearningStatus.CONFLICT && !overrideConflict) {
                return@withTransaction Rejected("该候选与现有规则冲突，必须由用户显式覆盖后才能确认")
            }
            val transitioned = dao.transitionCandidateState(
                id = candidateId,
                expectedStatus = candidate.status,
                newStatus = LearningStatus.CONFIRMED.wireValue,
                updatedAt = now,
                confirmedAt = now,
            )
            if (transitioned == 0) return@withTransaction Rejected("候选状态已被其他操作更新，请刷新后重试")
            dao.insertFeedback(
                LearningFeedbackEntity(
                    id = ids(),
                    candidateId = candidateId,
                    action = "confirm",
                    note = if (overrideConflict) "explicit-conflict-override" else "user-confirmed",
                    createdAt = now,
                ),
            )
            Accepted
        }
        if (outcome is Rejected) return rejected(outcome.reason)
        return success()
    }

    suspend fun reject(candidateId: String): LearningMutationResult {
        val now = clock()
        val outcome = database.withTransaction {
            val candidate = dao.candidate(candidateId)
                ?: return@withTransaction Rejected("学习候选不存在")
            if (candidate.status in setOf(
                    LearningStatus.REJECTED.wireValue,
                    LearningStatus.PROMOTED.wireValue,
                )
            ) {
                return@withTransaction Rejected("该候选已结束，不能再次拒绝")
            }
            val transitioned = dao.transitionCandidateState(
                id = candidateId,
                expectedStatus = candidate.status,
                newStatus = LearningStatus.REJECTED.wireValue,
                updatedAt = now,
            )
            if (transitioned == 0) return@withTransaction Rejected("候选状态已被其他操作更新，请刷新后重试")
            dao.insertFeedback(
                LearningFeedbackEntity(
                    id = ids(),
                    candidateId = candidateId,
                    action = "reject",
                    note = "user-rejected",
                    createdAt = now,
                ),
            )
            Accepted
        }
        if (outcome is Rejected) return rejected(outcome.reason)
        return success()
    }

    suspend fun promote(candidateId: String): LearningMutationResult {
        val now = clock()
        val outcome = database.withTransaction {
            val candidate = dao.candidate(candidateId)
                ?: return@withTransaction Rejected("学习候选不存在")
            if (candidate.status != LearningStatus.CONFIRMED.wireValue) {
                return@withTransaction Rejected("只有用户已确认的候选才能晋升")
            }
            val kind = LearningKind.fromWire(candidate.kind)
                ?: return@withTransaction Rejected("学习类型无效")
            val existing = dao.ruleByKey(kind.wireValue, candidate.title)
            val ruleId: String
            if (existing == null) {
                ruleId = ids()
                dao.insertRule(LearningRuleEntity(
                    id = ruleId,
                    kind = kind.wireValue,
                    title = candidate.title,
                    content = candidate.content,
                    currentVersion = 1,
                    status = LearningRuleStatus.ACTIVE.wireValue,
                    createdAt = now,
                    updatedAt = now,
                ))
                dao.insertRuleVersion(LearningRuleVersionEntity(
                    id = ids(), ruleId = ruleId, version = 1, content = candidate.content,
                    candidateId = candidateId, reason = "confirmed-promotion", createdAt = now,
                ))
            } else {
                ruleId = existing.id
                val version = existing.currentVersion + 1
                val updated = dao.transitionRuleVersion(
                    id = existing.id,
                    expectedVersion = existing.currentVersion,
                    newVersion = version,
                    content = candidate.content,
                    status = LearningRuleStatus.ACTIVE.wireValue,
                    updatedAt = now,
                )
                if (updated == 0) {
                    return@withTransaction Rejected("学习规则已被其他操作更新，请刷新后重试")
                }
                dao.insertRuleVersion(LearningRuleVersionEntity(
                    id = ids(), ruleId = ruleId, version = version, content = candidate.content,
                    candidateId = candidateId, reason = "confirmed-promotion", createdAt = now,
                ))
            }
            val transitioned = dao.transitionCandidateState(
                id = candidateId,
                expectedStatus = LearningStatus.CONFIRMED.wireValue,
                newStatus = LearningStatus.PROMOTED.wireValue,
                updatedAt = now,
                promotedRuleId = ruleId,
                confirmedAt = candidate.confirmedAt ?: now,
            )
            if (transitioned == 0) {
                return@withTransaction Rejected("候选已被其他操作晋升，请刷新后重试")
            }
            dao.insertFeedback(LearningFeedbackEntity(
                id = ids(), candidateId = candidateId, action = "promote", note = ruleId, createdAt = now,
            ))
            Accepted
        }
        if (outcome is Rejected) return rejected(outcome.reason)
        return success()
    }

    suspend fun rollback(ruleId: String, versionId: String): LearningMutationResult {
        val rule = dao.rule(ruleId) ?: return rejected("学习规则不存在")
        val target = dao.ruleVersion(versionId)
            ?.takeIf { it.ruleId == ruleId }
            ?: return rejected("目标版本不属于该学习规则")
        val now = clock()
        val nextVersion = rule.currentVersion + 1
        val outcome = database.withTransaction {
            val updated = dao.transitionRuleVersion(
                id = rule.id,
                expectedVersion = rule.currentVersion,
                newVersion = nextVersion,
                content = target.content,
                status = LearningRuleStatus.ACTIVE.wireValue,
                updatedAt = now,
            )
            if (updated == 0) return@withTransaction Rejected("学习规则已被其他操作更新，请刷新后重试")
            dao.insertRuleVersion(LearningRuleVersionEntity(
                id = ids(), ruleId = rule.id, version = nextVersion, content = target.content,
                candidateId = target.candidateId, reason = "rollback-to-v${target.version}", createdAt = now,
            ))
            Accepted
        }
        if (outcome is Rejected) return rejected(outcome.reason)
        return success()
    }

    suspend fun archiveRule(ruleId: String): LearningMutationResult {
        val rule = dao.rule(ruleId) ?: return rejected("学习规则不存在")
        dao.updateRule(rule.copy(status = LearningRuleStatus.ARCHIVED.wireValue, updatedAt = clock()))
        return success()
    }

    suspend fun snapshot(): LearningSnapshot {
        val candidates = dao.candidates().map { candidate ->
            candidateView(candidate, dao.evidence(candidate.id).map { evidence ->
                LearningEvidenceView(evidence.id, evidence.sourceRef, evidence.excerpt, evidence.observedAt)
            })
        }
        val rules = dao.allRules().map { rule ->
            LearningRuleView(
                id = rule.id,
                kind = LearningKind.fromWire(rule.kind) ?: LearningKind.FACT,
                title = rule.title,
                content = rule.content,
                currentVersion = rule.currentVersion,
                active = rule.status == LearningRuleStatus.ACTIVE.wireValue,
                pinned = rule.pinned,
                versions = dao.ruleVersions(rule.id).map { version ->
                    LearningRuleVersionView(version.id, version.version, version.content, version.reason, version.createdAt)
                },
            )
        }
        return LearningSnapshot(candidates, rules)
    }

    suspend fun search(query: String, limit: Int = 12): List<LearningRuleView> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        return dao.activeRules().asSequence()
            .filter { rule ->
                (rule.title + '\n' + rule.content).lowercase().contains(normalized) ||
                    normalized.split(Regex("\\s+")).all { token ->
                        token.length < 2 || (rule.title + '\n' + rule.content).lowercase().contains(token)
                    }
            }
            .take(limit.coerceIn(1, 50))
            .map { rule ->
                LearningRuleView(
                    id = rule.id,
                    kind = LearningKind.fromWire(rule.kind) ?: LearningKind.FACT,
                    title = rule.title,
                    content = rule.content,
                    currentVersion = rule.currentVersion,
                    active = true,
                    pinned = rule.pinned,
                    versions = emptyList(),
                )
            }
            .toList()
    }

    suspend fun recordFailure(
        errorCode: String,
        message: String,
        sourceRef: String,
    ): LearningProposalResult = propose(LearningProposalInput(
        kind = LearningKind.FAILURE_FIX,
        title = LearningPolicy.sanitizeForStorage("运行时失败：$errorCode", 120),
        content = LearningPolicy.sanitizeForStorage(
            "遇到 $errorCode 时先保留原始状态并检查恢复条件。观察：${message.take(500)}",
            1_200,
        ),
        evidence = LearningPolicy.sanitizeForStorage(message, 1_000),
        sourceRef = sourceRef,
    ))

    private suspend fun candidateView(
        candidate: LearningCandidateEntity,
        evidence: List<LearningEvidenceView>? = null,
    ): LearningCandidateView {
        val actualEvidence = evidence ?: dao.evidence(candidate.id).map { row ->
            LearningEvidenceView(row.id, row.sourceRef, row.excerpt, row.observedAt)
        }
        return LearningCandidateView(
        id = candidate.id,
        kind = LearningKind.fromWire(candidate.kind) ?: LearningKind.FACT,
        title = candidate.title,
        content = candidate.content,
        status = LearningStatus.entries.firstOrNull { it.wireValue == candidate.status } ?: LearningStatus.PENDING,
        confidence = candidate.confidence,
        sourceRef = candidate.sourceRef,
        createdAt = candidate.createdAt,
        updatedAt = candidate.updatedAt,
        promotedRuleId = candidate.promotedRuleId,
            evidence = actualEvidence,
        )
    }

    private suspend fun success() = LearningMutationResult.Success(snapshot())

    private fun rejected(reason: String) = LearningMutationResult.Rejected(reason)

    private data object Accepted
    private data class Rejected(val reason: String)

    companion object {
        @Volatile
        private var instance: LearningRepository? = null

        fun get(context: Context): LearningRepository =
            instance ?: synchronized(this) {
                instance ?: LearningRepository(LearningDatabase.get(context)).also { instance = it }
            }
    }
}

internal object LearningSettingsStore {
    private const val PREFS = "eta_learning_settings"
    private const val ENABLED = "enabled"
    private const val AUTO_CAPTURE = "auto_capture"
    private const val AUTO_PROMOTE = "auto_promote"

    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(ENABLED, enabled).apply()
    }

    fun automaticCaptureEnabled(context: Context): Boolean =
        isEnabled(context) && preferences(context).getBoolean(AUTO_CAPTURE, true)

    fun setAutomaticCaptureEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(AUTO_CAPTURE, enabled).apply()
    }

    /** 首版固定关闭；任何规则仍需用户确认，避免静默污染长期记忆。 */
    fun automaticPromotionEnabled(context: Context): Boolean =
        isEnabled(context) && preferences(context).getBoolean(AUTO_PROMOTE, false)

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
