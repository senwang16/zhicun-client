package com.zhicun.app.data.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.domain.web.FetchedImage
import com.zhicun.app.domain.web.ImageFetcher
import com.zhicun.app.domain.web.UnsafeUrlException
import com.zhicun.app.domain.web.WebPage
import com.zhicun.app.domain.web.WebPageLoader
import com.zhicun.app.domain.web.WebSnapshotStore
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.aggregateJobsFrom
import com.zhicun.app.fake.testRecord
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 网页提取 Worker 测试（M8 验收：Mock HTTP 响应，验证结果落库与失败分类）。
 *
 * 抓取本身（重定向、大小上限、内容类型）由 `OkHttpWebPageLoaderTest` 用真实本地服务覆盖；
 * 这里替换掉抓取层，专注"抓回来之后怎么落库、异常怎么分类"。
 */
@RunWith(RobolectricTestRunner::class)
class WebExtractWorkerTest {

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var derivedRepository: FakeDerivedContentRepository
    private lateinit var loader: FakeWebPageLoader
    private lateinit var imageFetcher: FakeImageFetcher
    private lateinit var snapshotStore: FakeSnapshotStore
    private lateinit var executor: ProcessingJobExecutor
    private lateinit var sensitivityGuard: DerivedSensitivityGuard

    private val recordId = "rec-web"
    private val url = "https://example.com/post/1"

    @Before
    fun setUp() {
        jobRepository = FakeProcessingJobRepository()
        recordRepository = FakeRecordRepository(
            listOf(
                testRecord(
                    id = recordId,
                    type = RecordType.WEB_LINK,
                    rawText = null,
                    sourceUrl = url,
                ),
            ),
        ).aggregateJobsFrom(jobRepository)
        derivedRepository = FakeDerivedContentRepository()
        loader = FakeWebPageLoader()
        imageFetcher = FakeImageFetcher()
        snapshotStore = FakeSnapshotStore()
        executor = ProcessingJobExecutor(
            jobRepository,
            recordRepository,
            FakeAiSettings(),
            FakeAiGatewaySettings(),
        )
        sensitivityGuard = DerivedSensitivityGuard(SensitiveContentDetector(), recordRepository, FakeCollectionRepository())
    }

    @Test
    fun DoWork_normalPage_writesTitleAndPageText() = runTest {
        loader.page = pageOf(
            "<html><head><title>智存的设计取舍</title></head>" +
                "<body><article><p>${longBody()}</p></article></body></html>",
        )
        seedJob()

        val result = startWorker()

        assertTrue("应返回 Success，实际 $result", result is ListenableWorker.Result.Success)
        assertEquals(
            "智存的设计取舍",
            derivedRepository.getByRecordAndType(recordId, DerivedType.TITLE)?.content,
        )
        val pageText = derivedRepository.getByRecordAndType(recordId, DerivedType.PAGE_TEXT)
        assertTrue("正文应落库", pageText?.content?.contains("正文内容") == true)
        assertNull("正文够长就不该标未完整", pageText?.errorMessage)
        assertEquals(url, loader.lastUrl)
        assertEquals(JobStatus.DONE, currentJob().status)
    }

    @Test
    fun DoWork_shortPage_marksPartialButStillSucceeds() = runTest {
        // 登录墙的典型形态：有标题，正文只有一句
        loader.page = pageOf(
            "<html><head><title>请登录</title></head><body><p>请登录后查看全文</p></body></html>",
        )
        seedJob()

        val result = startWorker()

        assertTrue("抓到了内容就算成功，不能用失败遮住已抓到的片段", result is ListenableWorker.Result.Success)
        val pageText = derivedRepository.getByRecordAndType(recordId, DerivedType.PAGE_TEXT)
        assertEquals(WebExtractWorker.PARTIAL_REASON, pageText?.errorMessage)
        assertEquals("请登录后查看全文", pageText?.content)
    }

    /**
     * 纯前端渲染的骨架页（既没标题也没正文）：按「仅标题」降级**成功**。
     *
     * 2026-11-05 移植海外轨修复：旧语义判 Permanent 失败，界面上是一条红色记录。
     * 但标题与链接本来就在 `records` 里，摘要与向量还能按元数据继续生成 ——
     * 把它标成失败只会让用户以为"这个 App 坏了"，而不是"这个站点不让程序读"。
     */
    @Test
    fun DoWork_emptyPage_degradesToTitleOnlyWithoutFailing() = runTest {
        loader.page = pageOf("<html><body></body></html>")
        seedJob()

        val result = startWorker()

        assertTrue("无正文应按仅标题降级成功，实际 $result", result is ListenableWorker.Result.Success)
        assertNull("不该写入空的正文派生", derivedRepository.getByRecordAndType(recordId, DerivedType.PAGE_TEXT))
        assertEquals("任务不该被判失败", JobStatus.DONE, currentJob().status)
    }

    @Test
    fun DoWork_recordWithoutSourceUrl_failsWithoutFetching() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId.getValue(recordId)
            .copy(sourceUrl = null)
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals("没有链接就不该发起请求", 0, loader.callCount)
    }

    @Test
    fun DoWork_unsafeUrl_failsPermanently() = runTest {
        loader.failure = UnsafeUrlException("拒绝访问内网地址")
        seedJob()

        val result = startWorker()

        assertTrue("SSRF 拒绝不该重试，实际 $result", result is ListenableWorker.Result.Failure)
        assertEquals(JobStatus.FAILED, currentJob().status)
    }

    @Test
    fun DoWork_networkError_retries() = runTest {
        loader.failure = IOException("连接超时")
        seedJob()

        val result = startWorker()

        assertTrue("网络失败应重试，实际 $result", result is ListenableWorker.Result.Retry)
        val job = currentJob()
        assertEquals(JobStatus.QUEUED, job.status)
        assertEquals(1, job.attempts)
    }

    // ------------------------------------------------ 快照与 og:image（C）

    @Test
    fun DoWork_normalPage_savesHtmlSnapshotFromSameResponse() = runTest {
        val html = "<html><head><title>智存</title></head><body><article><p>${longBody()}</p></article></body></html>"
        loader.page = pageOf(html)
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        // 无图页面：内嵌器不改动内容，快照仍取自同一响应；有图页面的内嵌行为在下面两条
        assertTrue(
            "快照必须来自同一响应的字节",
            snapshotStore.savedHtmlBytes?.toString(Charsets.UTF_8)?.contains("智存") == true,
        )
    }

    @Test
    fun DoWork_pageWithImages_snapshotEmbedsImagesAsDataUri() = runTest {
        // 查看页禁网禁脚本，远程 <img> 必然裂图——保存时就得把图抓进 HTML（C 补）
        loader.page = pageOf(
            "<html><head><title>智存</title></head><body><article><p>${longBody()}</p>" +
                "<img src=\"$url/img/a.png\" /></article></body></html>",
        )
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("正文图片要真的去下载", "$url/img/a.png", imageFetcher.fetchedUrl)
        val saved = snapshotStore.savedHtmlBytes?.toString(Charsets.UTF_8)
        assertTrue("快照里应有内嵌的 data URI 图片", saved?.contains("data:image/png;base64,") == true)
    }

    @Test
    fun DoWork_imageFetchFails_snapshotStillSavedWithOriginalSrc() = runTest {
        // 图片 404 不能拖垮快照：降级保留原 src，与"附加能力失败必须降级"同一口径
        loader.page = pageOf(
            "<html><head><title>智存</title></head><body><article><p>${longBody()}</p>" +
                "<img src=\"$url/img/a.png\" /></article></body></html>",
        )
        imageFetcher.failure = IOException("HTTP 404")
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        val saved = snapshotStore.savedHtmlBytes?.toString(Charsets.UTF_8)
        assertTrue("快照必须仍然保存", saved?.contains("智存") == true)
        assertTrue("失败的图保留原 src", saved?.contains("$url/img/a.png") == true)
        assertFalse("不应出现内嵌图", saved?.contains("data:image") == true)
    }

    @Test
    fun DoWork_pageWithOgImage_downloadsAndSavesImage() = runTest {
        loader.page = pageOf(
            "<html><head>" +
                "<meta property=\"og:image\" content=\"$url/cover.jpg\" />" +
                "<title>智存</title></head><body><article><p>${longBody()}</p></article></body></html>",
        )
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("$url/cover.jpg", imageFetcher.fetchedUrl)
        assertEquals("响应声明的类型要原样进附件行", "image/png", snapshotStore.savedImageMime)
    }

    @Test
    fun DoWork_ogImageFetchFails_stillSucceeds() = runTest {
        // og:image 404 是常态（站点换图、防盗链），不能把已提取成功的记录拖成失败
        loader.page = pageOf(
            "<html><head>" +
                "<meta property=\"og:image\" content=\"$url/cover.jpg\" />" +
                "<title>智存</title></head><body><article><p>${longBody()}</p></article></body></html>",
        )
        imageFetcher.failure = IOException("HTTP 404")
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        val pageText = derivedRepository.getByRecordAndType(recordId, DerivedType.PAGE_TEXT)
        assertTrue("正文照常落库", pageText?.content?.contains("正文内容") == true)
    }

    @Test
    fun DoWork_snapshotWriteFails_stillSucceeds() = runTest {
        // 磁盘满/目录建不出来时，标题与正文已经拿到了，快照只是锦上添花
        loader.page = pageOf(
            "<html><head><title>智存</title></head><body><article><p>${longBody()}</p></article></body></html>",
        )
        snapshotStore.htmlFailure = IOException("No space left on device")
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            "智存",
            derivedRepository.getByRecordAndType(recordId, DerivedType.TITLE)?.content,
        )
    }

    @Test
    fun DerivedContentIds_areStableForTitleAndPageText() {
        assertEquals(
            "$recordId:${DerivedType.TITLE}",
            DerivedContentKeys.idFor(recordId, DerivedType.TITLE),
        )
        assertEquals(
            "$recordId:${DerivedType.PAGE_TEXT}",
            DerivedContentKeys.idFor(recordId, DerivedType.PAGE_TEXT),
        )
    }

    private fun pageOf(html: String) = WebPage(
        finalUrl = url,
        content = html.toByteArray(Charsets.UTF_8),
        declaredCharset = "utf-8",
    )

    private fun longBody(): String = (1..40).joinToString(" ") { "第${it}段正文内容，用于超过三百字的完整性阈值。" }

    private suspend fun seedJob() {
        jobRepository.insert(
            ProcessingJob(
                id = "job-1",
                recordId = recordId,
                jobType = JobType.WEBPAGE_EXTRACT,
                status = JobStatus.QUEUED,
                attempts = 0,
            ),
        )
    }

    /**
     * 换成一条敏感网页记录：抓取闸门只看这一条（2026-10-23 复核 F-5）。
     *
     * @param webFetchConfirmedRevision 用户单独"允许抓取此网页"时的版本号；
     *   null = 从未允许（按下标默认值），等于 [recordRevision] 时视为当前版本已允许
     * @param recordRevision 记录当前版本，用来构造"允许过、后来内容被改"的形态
     */
    private fun useSuspectedRecord(
        webFetchConfirmedRevision: Int? = null,
        recordRevision: Int = 1,
    ) {
        recordRepository = FakeRecordRepository(
            listOf(
                testRecord(
                    id = recordId,
                    type = RecordType.WEB_LINK,
                    rawText = null,
                    sourceUrl = url,
                    sensitivityStatus = SensitivityStatus.SUSPECTED,
                    recordRevision = recordRevision,
                    webFetchConfirmedRevision = webFetchConfirmedRevision,
                ),
            ),
        ).aggregateJobsFrom(jobRepository)
        executor = ProcessingJobExecutor(
            jobRepository,
            recordRepository,
            FakeAiSettings(),
            FakeAiGatewaySettings(),
        )
    }

    private suspend fun currentJob(): ProcessingJob =
        jobRepository.getByRecordAndType(recordId, JobType.WEBPAGE_EXTRACT)!!

    private suspend fun startWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<WebExtractWorker>(RuntimeEnvironment.getApplication())
            .setWorkerFactory(factory())
            .setInputData(
                workDataOf(
                    ProcessingWorkKeys.RECORD_ID to recordId,
                    ProcessingWorkKeys.JOB_TYPE to JobType.WEBPAGE_EXTRACT.name,
                ),
            )
            .build()
            .startWork()
            .get()

    private fun factory(): WorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = if (workerClassName == WebExtractWorker::class.java.name) {
            WebExtractWorker(
                appContext = appContext,
                params = workerParameters,
                executor = executor,
                webPageLoader = loader,
                imageFetcher = imageFetcher,
                snapshotStore = snapshotStore,
                derivedContentRepository = derivedRepository,
                sensitivityGuard = sensitivityGuard,
            )
        } else {
            null
        }
    }

    /** 抓取层的假实现：能返回固定页面或抛固定异常。 */
    private class FakeWebPageLoader : WebPageLoader {

        var page: WebPage? = null
        var failure: Throwable? = null
        var callCount: Int = 0
            private set
        var lastUrl: String? = null
            private set

        override suspend fun load(url: String): WebPage {
            callCount++
            lastUrl = url
            failure?.let { throw it }
            return page ?: error("测试未设置 page")
        }
    }

    /** og:image 抓取的假实现。 */
    private class FakeImageFetcher : ImageFetcher {

        var failure: Throwable? = null
        var fetchedUrl: String? = null
            private set
        var callCount: Int = 0
            private set

        override suspend fun fetch(url: String): FetchedImage {
            callCount++
            fetchedUrl = url
            failure?.let { throw it }
            // 一张最小 PNG 的头几个字节即可，Worker 不解析图片内容
            return FetchedImage(bytes = byteArrayOf(0x89.toByte(), 0x50.toByte()), mimeType = "image/png")
        }
    }

    /** 快照落盘的假实现：记录调用，可注入失败。 */
    private class FakeSnapshotStore : WebSnapshotStore {

        var htmlFailure: Throwable? = null
        var imageFailure: Throwable? = null
        var savedHtmlBytes: ByteArray? = null
            private set
        var savedImageMime: String? = null
            private set

        override suspend fun saveHtmlSnapshot(recordId: String, htmlBytes: ByteArray): Asset {
            htmlFailure?.let { throw it }
            savedHtmlBytes = htmlBytes
            return Asset(
                id = "asset-html",
                recordId = recordId,
                mimeType = "text/html",
                localPath = "/fake/snapshot.html",
                fileSize = htmlBytes.size.toLong(),
                fileHash = null,
                originalUri = null,
                saveStatus = AssetSaveStatus.SAVED,
            )
        }

        override suspend fun saveOgImage(
            recordId: String,
            imageBytes: ByteArray,
            mimeType: String,
        ): Asset {
            imageFailure?.let { throw it }
            savedImageMime = mimeType
            return Asset(
                id = "asset-image",
                recordId = recordId,
                mimeType = mimeType,
                localPath = "/fake/og_image.png",
                fileSize = imageBytes.size.toLong(),
                fileHash = null,
                originalUri = null,
                saveStatus = AssetSaveStatus.SAVED,
            )
        }
    }

    /**
     * 疑似敏感且未确认：**一个请求都不发**（R-7）。
     *
     * 用户口径是"敏感信息只留在手机内"。抓取虽然不经 AI 网关，但 URL 会到达源站
     * （可能带预签名令牌），属于同一类后果，因此连 URL 都不交出去。
     * 返回 success 是刻意的：策略性不做不是失败，页面上有文案说明原因。
     */
    @Test
    fun DoWork_suspectedRecord_neverRequestsSourceSite() = runTest {
        useSuspectedRecord()
        loader.page = pageOf(
            "<html><head><title>x</title></head><body><p>${longBody()}</p></body></html>",
        )
        seedJob()

        val result = startWorker()

        assertTrue("策略性不做不是失败，实际 $result", result is ListenableWorker.Result.Success)
        assertEquals("敏感记录绝不能向源站发请求", 0, loader.callCount)
        assertEquals(JobStatus.CANCELLED, currentJob().status)
        assertNull(derivedRepository.getByRecordAndType(recordId, DerivedType.PAGE_TEXT))
    }

    /**
     * 敏感但用户**单独允许过**抓取：请求照发（2026-10-23 复核 F-5）。
     *
     * 这条是 F-5 的核心：抓取与云端上传是两次独立的授权，"允许抓取"不看云端开关，
     * 也不要求 `cloudConfirmedRevision`。整改前放行条件挂在云端确认上，
     * 而那个确认要求云端通路可用，默认无网关的安装包里敏感网页永远抓不下来。
     */
    @Test
    fun DoWork_sensitiveButFetchAllowed_requestsSourceSite() = runTest {
        useSuspectedRecord(webFetchConfirmedRevision = 1, recordRevision = 1)
        loader.page = pageOf(
            "<html><head><title>x</title></head><body><p>${longBody()}</p></body></html>",
        )
        seedJob()

        val result = startWorker()

        assertFalse("允许抓取后应当正常完成，实际 $result", result is ListenableWorker.Result.Retry)
        assertEquals("允许抓取后必须真的去请求", 1, loader.callCount)
        assertEquals(JobStatus.DONE, currentJob().status)
        assertNotNull(derivedRepository.getByRecordAndType(recordId, DerivedType.PAGE_TEXT))
    }

    /**
     * 允许过、但内容后来被改了（`recordRevision` 递增）：重新拦下。
     *
     * 授权绑定在"第几版内容"上：用户改了 URL 或正文，就该重新问一次
     * "允许访问这个地址吗" —— 否则一次允许等于永久通行证。
     */
    @Test
    fun DoWork_fetchAllowedThenRecordEdited_blocksAgain() = runTest {
        useSuspectedRecord(webFetchConfirmedRevision = 1, recordRevision = 2)
        loader.page = pageOf(
            "<html><head><title>x</title></head><body><p>${longBody()}</p></body></html>",
        )
        seedJob()

        val result = startWorker()

        assertTrue("策略性不做不是失败，实际 $result", result is ListenableWorker.Result.Success)
        assertEquals("版本对不上就不能发请求", 0, loader.callCount)
        assertEquals(JobStatus.CANCELLED, currentJob().status)
    }
}
