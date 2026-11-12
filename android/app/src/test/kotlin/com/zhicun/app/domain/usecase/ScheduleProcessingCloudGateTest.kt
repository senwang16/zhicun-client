package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.worker.ProcessingJobTargets
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 调度侧的云端闸门（M14）。
 *
 * 验收标准里那条"云端 AI 开关关闭后，后台不再提交需要云端处理的 Job"就是这里。
 * 之所以必须钉在**调度侧**而不是 Worker 侧：任务一旦进了队列，补投（启动恢复）、
 * 手动重试等路径都可能绕过执行时的判断；不排任务，就没有任何一条路径能把它传上去。
 *
 * 同时覆盖 M14 修掉的一个老漏洞：**语音转写也是上传**（T-006 走云端 ASR），
 * M9 的 `CLOUD_JOBS` 里却没有它，导致疑似敏感的录音会被原样传上云端。
 */
class ScheduleProcessingCloudGateTest {

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var recordRepository: FakeRecordRepository

    @Before
    fun setUp() {
        jobRepository = FakeProcessingJobRepository()
        scheduler = FakeProcessingScheduler()
        recordRepository = FakeRecordRepository()
    }

    /**
     * 按给定开关状态跑一次调度。
     *
     * @param seed 是否先把记录放进仓储。必须放进去，否则断言不到 `LOCAL_ONLY` 的写入
     *   （假实现按 id 更新，记录不存在时是空操作 —— 那会让"状态没改"看起来像"改了"）。
     */
    private suspend fun schedule(record: Record, cloudAiEnabled: Boolean, seed: Boolean = true) {
        if (seed) recordRepository = FakeRecordRepository(listOf(record))
        ScheduleProcessingUseCase(
            jobRepository = jobRepository,
            scheduler = scheduler,
            recordRepository = recordRepository,
            aiSettings = FakeAiSettings(cloudAiEnabled = cloudAiEnabled),
            gatewaySettings = FakeAiGatewaySettings(),
        ).invoke(record)
    }

    // ------------------------------------------------------------ 开关关闭

    @Test
    fun CloudOff_imageRecord_onlyLocalOcrIsEnqueued() = runTest {
        val record = testRecord(id = "r-img", type = RecordType.IMAGE)

        schedule(record, cloudAiEnabled = false)

        assertEquals("云端关着时只该排本地 OCR", listOf("r-img" to JobType.OCR), scheduler.enqueued)
        assertNull(
            "不该留下摘要任务行（留行会永远挂在 QUEUED）",
            jobRepository.getByRecordAndType("r-img", JobType.SUMMARIZE),
        )
        assertNull(jobRepository.getByRecordAndType("r-img", JobType.EMBED))
    }

    @Test
    fun CloudOff_imageRecord_marksLocalOnly() = runTest {
        val record = testRecord(id = "r-img", type = RecordType.IMAGE)

        schedule(record, cloudAiEnabled = false)

        assertEquals(
            "被挡下的记录要显示'仅本地'，而不是'待处理'",
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId.getValue("r-img").processingStatus,
        )
    }

    /** 纯文本记录的任务全是云端的：闸门一关就一个都不排。 */
    @Test
    fun CloudOff_textNote_enqueuesNothing() = runTest {
        val record = testRecord(id = "r-text", type = RecordType.TEXT_NOTE)

        schedule(record, cloudAiEnabled = false)

        assertTrue("纯文本记录只有云端任务，关掉后不该有任何调度", scheduler.enqueued.isEmpty())
        assertTrue(jobRepository.jobs.isEmpty())
        assertEquals(
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId.getValue("r-text").processingStatus,
        )
    }

    @Test
    fun CloudOff_voiceNote_doesNotUploadAudio() = runTest {
        val record = testRecord(id = "r-voice", type = RecordType.VOICE_NOTE)

        schedule(record, cloudAiEnabled = false)

        assertTrue(
            "音频上传必须被挡住：转写也是把内容发到云端",
            scheduler.enqueued.none { it.second == JobType.TRANSCRIPTION },
        )
        assertEquals(
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId.getValue("r-voice").processingStatus,
        )
    }

    @Test
    fun CloudOff_webLink_stillExtractsPageLocally() = runTest {
        val record = testRecord(id = "r-web", type = RecordType.WEB_LINK)

        schedule(record, cloudAiEnabled = false)

        assertEquals(
            "网页正文提取在本机完成，不受云端开关影响",
            listOf("r-web" to JobType.WEBPAGE_EXTRACT),
            scheduler.enqueued,
        )
    }

    // ------------------------------------------------------------ 开关打开

    @Test
    fun CloudOn_textNote_enqueuesSummaryAndEmbed() = runTest {
        val record = testRecord(id = "r-text", type = RecordType.TEXT_NOTE)

        schedule(record, cloudAiEnabled = true)

        assertEquals(
            listOf("r-text" to JobType.SUMMARIZE, "r-text" to JobType.EMBED),
            scheduler.enqueued,
        )
        assertEquals(
            "开关打开、内容不敏感时不该被标成仅本地",
            ProcessingStatus.PENDING,
            recordRepository.byId.getValue("r-text").processingStatus,
        )
    }

    /** M14 修正：转写属于云端任务，开关打开时应正常排队。 */
    @Test
    fun CloudOn_voiceNote_enqueuesTranscription() = runTest {
        val record = testRecord(id = "r-voice", type = RecordType.VOICE_NOTE)

        schedule(record, cloudAiEnabled = true)

        assertTrue(
            "开关打开时转写必须排上，否则语音记录永远没有文字",
            scheduler.enqueued.contains("r-voice" to JobType.TRANSCRIPTION),
        )
    }

    /** 回归 M11 的敏感门禁：录音里的敏感内容同样不得上传。 */
    @Test
    fun Sensitive_voiceNote_neverUploadsAudio() = runTest {
        val record = testRecord(
            id = "r-voice",
            type = RecordType.VOICE_NOTE,
            sensitivityStatus = SensitivityStatus.SUSPECTED,
        )

        schedule(record, cloudAiEnabled = true)

        assertTrue("疑似敏感的录音不得上传音频（M9 漏项，M14 补上）", scheduler.enqueued.isEmpty())
        assertEquals(
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId.getValue("r-voice").processingStatus,
        )
    }

    // ------------------------------------------------------------ 重开开关

    /**
     * 用户关了又打开：重新调度时云端任务要能补上。
     * （已有记录不会自动重排 —— 这是刻意的，见 DEVELOPMENT_PLAN.md M14 已知限制。）
     */
    @Test
    fun CloudReenabled_reschedulesCloudJobs() = runTest {
        val record = testRecord(id = "r-text", type = RecordType.TEXT_NOTE)
        schedule(record, cloudAiEnabled = false)
        assertTrue(jobRepository.jobs.isEmpty())

        schedule(record, cloudAiEnabled = true, seed = false)

        assertEquals(
            listOf("r-text" to JobType.SUMMARIZE, "r-text" to JobType.EMBED),
            scheduler.enqueued,
        )
    }

    // ------------------------------------------------------------ 任务表契约

    /**
     * 锁住 `CLOUD_JOBS` 的内容。
     *
     * 这个集合的语义是"会把内容发出设备"，漏一个就等于多一条隐私逃逸路径，
     * 而且不会有任何测试变红。因此用显式枚举把当前认知钉下来：
     * 将来新增任何上传类任务，必须同时改这里和这张表。
     */
    @Test
    fun CloudJobs_areExactlyTheUploadingJobs() {
        assertEquals(
            setOf(JobType.SUMMARIZE, JobType.EMBED, JobType.TRANSCRIPTION),
            ProcessingJobTargets.CLOUD_JOBS,
        )
    }

    /** 本地任务不能被误收进云端集合，否则关掉开关会连 OCR 都停掉。 */
    @Test
    fun CloudJobs_excludeLocalJobs() {
        assertTrue(JobType.OCR !in ProcessingJobTargets.CLOUD_JOBS)
        assertTrue(JobType.WEBPAGE_EXTRACT !in ProcessingJobTargets.CLOUD_JOBS)
    }

    // ------------------------------------------------------------ 合集实时并集（M20）

    /**
     * 合集行的 `sensitivity_status` 只是列表徽标的快照；闸门必须用成员的**实时并集**。
     *
     * 这里故意让快照是 CLEAR、实时并集是 SUSPECTED：若实现读的是快照，任务就会被排上去。
     */
    @Test
    fun Sensitive_liveMemberUnion_blocksCollectionCloudJobs() = runTest {
        val collection = testRecord(
            id = "c1",
            type = RecordType.COLLECTION,
            sensitivityStatus = SensitivityStatus.CLEAR,
        )
        recordRepository = FakeRecordRepository(listOf(collection)).apply {
            collectionSensitivityProvider = { SensitivityStatus.SUSPECTED }
        }

        schedule(collection, cloudAiEnabled = true, seed = false)

        assertTrue("成员的实时并集敏感时必须拦下", scheduler.enqueued.isEmpty())
        assertEquals(
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId.getValue("c1").processingStatus,
        )
    }

    /** 反向：快照还停在 SUSPECTED（成员风险已被用户确认/清除），实时并集 CLEAR 就应放行。 */
    @Test
    fun ClearLiveMemberUnion_allowsCollectionCloudJobsDespiteStaleSnapshot() = runTest {
        val collection = testRecord(
            id = "c1",
            type = RecordType.COLLECTION,
            sensitivityStatus = SensitivityStatus.SUSPECTED,
        )
        recordRepository = FakeRecordRepository(listOf(collection)).apply {
            collectionSensitivityProvider = { SensitivityStatus.CLEAR }
        }

        schedule(collection, cloudAiEnabled = true, seed = false)

        assertEquals(
            listOf("c1" to JobType.SUMMARIZE, "c1" to JobType.EMBED),
            scheduler.enqueued,
        )
    }
}
