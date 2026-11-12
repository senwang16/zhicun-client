package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeMultiAssetRerunSettings
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.aggregateJobsFrom
import com.zhicun.app.fake.testAsset
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v8 升级后的一次性补跑（D1）。
 *
 * 关注三件事：只挑多附件记录、只重排 OCR / 转写、标记在跑完之后才落。
 */
class RerunMultiAssetProcessingUseCaseTest {

    private val jobRepository = FakeProcessingJobRepository()
    private val scheduler = FakeProcessingScheduler()
    private val aiSettings = FakeAiSettings()
    private val recordRepository = FakeRecordRepository(
        listOf(
            testRecord(id = "r-multi", type = RecordType.IMAGE, rawText = null),
            testRecord(id = "r-single", type = RecordType.IMAGE, rawText = null),
        ),
    ).aggregateJobsFrom(jobRepository)
    private val assetRepository = FakeAssetRepository(
        listOf(
            testAsset(id = "a1", recordId = "r-multi"),
            testAsset(id = "a2", recordId = "r-multi"),
            testAsset(id = "a3", recordId = "r-single"),
            // 记录已被删除、只剩附件孤儿行：不该让补跑把整批任务卡住
            testAsset(id = "a4", recordId = "r-gone"),
            testAsset(id = "a5", recordId = "r-gone"),
        ),
    )
    private val settings = FakeMultiAssetRerunSettings()

    private fun buildUseCase(
        cloudEnabled: Boolean = true,
    ): RerunMultiAssetProcessingUseCase {
        aiSettings.cloudEnabled = cloudEnabled
        val retry = RetryProcessingUseCase(
            recordRepository = recordRepository,
            jobRepository = jobRepository,
            scheduler = scheduler,
            aiSettings = aiSettings,
            gatewaySettings = FakeAiGatewaySettings(),
        )
        return RerunMultiAssetProcessingUseCase(assetRepository, retry, settings)
    }

    @Test
    fun Invoke_enqueuesOnlyMediaJobsForMultiAssetRecords() = runTest {
        val enqueued = buildUseCase()()

        assertEquals("r-single 只有一个附件、r-gone 已不存在，都该跳过", 1, enqueued)
        assertEquals(listOf("r-multi" to JobType.OCR), scheduler.enqueued)
        assertTrue("跑完才写标记，中途被杀下次还能再试", settings.completed)
    }

    @Test
    fun Invoke_alreadyCompleted_doesNothing() = runTest {
        settings.completed = true

        val enqueued = buildUseCase()()

        assertEquals(0, enqueued)
        assertTrue(scheduler.enqueued.isEmpty())
        assertTrue(jobRepository.jobs.isEmpty())
    }

    @Test
    fun Invoke_cloudDisabled_skipsAudioRecordsWithoutFailing() = runTest {
        recordRepository.byId.clear()
        recordRepository.stored.value =
            listOf(testRecord(id = "r-audio", type = RecordType.VOICE_NOTE, rawText = null))
        assetRepository.stored.clear()
        assetRepository.stored += testAsset(id = "v1", recordId = "r-audio", mimeType = "audio/mp4")
        assetRepository.stored += testAsset(id = "v2", recordId = "r-audio", mimeType = "audio/mp4")

        val enqueued = buildUseCase(cloudEnabled = false)()

        assertEquals("云端关闭时转写排不进去，补跑应静默跳过", 0, enqueued)
        assertTrue(scheduler.enqueued.isEmpty())
        assertTrue("跳过不等于失败，标记仍然要落", settings.completed)
    }
}
