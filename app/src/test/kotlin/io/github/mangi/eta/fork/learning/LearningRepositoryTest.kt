package io.github.mangi.eta.fork.learning

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LearningRepositoryTest {
    private lateinit var database: LearningDatabase
    private lateinit var repository: LearningRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            org.robolectric.RuntimeEnvironment.getApplication(),
            LearningDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = LearningRepository(database, clock = { 100L })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `concurrent confirmations produce one transition and one feedback row`() = runBlocking {
        val candidate = (repository.propose(
            LearningProposalInput(
                kind = LearningKind.PREFERENCE,
                title = "回答风格",
                content = "用户偏好简短回答",
                evidence = "用户明确要求简短",
            ),
        ) as LearningProposalResult.Created).candidate
        val start = CompletableDeferred<Unit>()

        val results = coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    start.await()
                    repository.confirm(candidate.id)
                },
                async(Dispatchers.IO) {
                    start.await()
                    repository.confirm(candidate.id)
                },
            ).also { start.complete(Unit) }.awaitAll()
        }

        assertEquals(1, results.count { it is LearningMutationResult.Success })
        assertEquals(1, results.count { it is LearningMutationResult.Rejected })
        assertEquals(
            1,
            database.learningDao().feedback(candidate.id).count { it.action == "confirm" },
        )
        assertEquals(
            LearningStatus.CONFIRMED.wireValue,
            database.learningDao().candidate(candidate.id)?.status,
        )
    }

    @Test
    fun `concurrent promotion creates one rule version and one success`() = runBlocking {
        val candidate = (repository.propose(
            LearningProposalInput(
                kind = LearningKind.PROCEDURE,
                title = "部署前检查",
                content = "部署前先运行测试",
                evidence = "两次部署失败均缺少测试",
            ),
        ) as LearningProposalResult.Created).candidate
        assertTrue(repository.confirm(candidate.id) is LearningMutationResult.Success)
        val start = CompletableDeferred<Unit>()

        val results = coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    start.await()
                    repository.promote(candidate.id)
                },
                async(Dispatchers.IO) {
                    start.await()
                    repository.promote(candidate.id)
                },
            ).also { start.complete(Unit) }.awaitAll()
        }

        assertEquals(1, results.count { it is LearningMutationResult.Success })
        assertEquals(1, results.count { it is LearningMutationResult.Rejected })
        val promoted = requireNotNull(database.learningDao().candidate(candidate.id))
        assertEquals(LearningStatus.PROMOTED.wireValue, promoted.status)
        val versions = database.learningDao().ruleVersions(requireNotNull(promoted.promotedRuleId))
        assertEquals(1, versions.count { it.candidateId == candidate.id })
    }

    @Test
    fun `same evidence from different sources is preserved while exact duplicates collapse`() = runBlocking {
        val input = LearningProposalInput(
            kind = LearningKind.FACT,
            title = "项目语言",
            content = "项目使用 Kotlin",
            evidence = "源码文件扩展名是 kt",
            sourceRef = "conversation:a",
        )
        repository.propose(input)
        repository.propose(input.copy(sourceRef = "conversation:b"))
        repository.propose(input.copy(sourceRef = "conversation:a"))

        val candidate = repository.snapshot().candidates.single()
        assertEquals(2, candidate.evidence.size)
        assertEquals(
            setOf("conversation:a", "conversation:b"),
            candidate.evidence.map { it.sourceRef }.toSet(),
        )
    }
}
