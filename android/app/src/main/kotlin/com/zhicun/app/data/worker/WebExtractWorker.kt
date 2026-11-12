package com.zhicun.app.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.repository.DerivedContentRepository
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.domain.web.HtmlContentExtractor
import com.zhicun.app.domain.web.HttpStatusException
import com.zhicun.app.domain.web.ImageFetcher
import com.zhicun.app.domain.web.SnapshotImageInliner
import com.zhicun.app.domain.web.WebPageLoader
import com.zhicun.app.domain.web.WebSnapshotStore
import com.zhicun.app.domain.worker.ProcessorResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * 网页链接提取（M8 + C 快照）。
 *
 * 链路：`record.sourceUrl` → [WebPageLoader]（含 SSRF 校验、超时、大小上限）
 * → [HtmlContentExtractor] → 写 `TITLE` 与 `PAGE_TEXT` 两条派生内容
 * → 顺路把页面 HTML（正文图片已内嵌为 data URI，见 [SnapshotImageInliner]）存成快照附件、
 *   og:image 下载存为图片附件（C，离线浏览）。
 *
 * 异常不在这里 catch：[WebPageLoader] 抛的 `IOException`（超时、连接断开、非 2xx）
 * 会被 Executor 判为可重试，`UnsafeUrlException` / `UnsupportedContentException` 判为终态。
 * 两处各写一份分类规则必然漂移，规则只留在 Executor 一处。
 *
 * "未完整提取"的表达方式：派生内容的 `errorMessage` 非空 + `content` 仍有值。
 * 这样登录墙 / 纯前端渲染的页面既能保留抓到的片段，又能在界面上如实说明不完整 ——
 * 比直接判失败更接近用户的实际需要（他要的是"链接还在、能看到点东西"）。
 *
 * ## 快照/配图失败必须降级
 * 标题与正文才是这个任务的主产物；快照与 og:image 是附加能力，它们失败（写入失败、
 * 图片下线、SSRF 拒绝）只留日志，**不能**让任务失败——否则一次 og:image 404 会把
 * 一条已经提取成功的记录打成红色，诱导三次毫无意义的重试（坑：附加信息不拖死主路径）。
 */
@HiltWorker
class WebExtractWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    executor: ProcessingJobExecutor,
    private val webPageLoader: WebPageLoader,
    private val imageFetcher: ImageFetcher,
    private val snapshotStore: WebSnapshotStore,
    private val derivedContentRepository: DerivedContentRepository,
    private val sensitivityGuard: DerivedSensitivityGuard,
) : BaseProcessingWorker(appContext, params, executor) {

    override suspend fun perform(record: Record): ProcessorResult {
        // 疑似敏感且用户没有**单独允许抓取这个网页**：不发任何请求
        // （2026-10-23 R-7 / 用户口径"敏感信息只留在手机内"，F-5 起改为独立确认）。
        // 抓取虽然不经 AI 网关，但 URL 会到达第三方站点（可能带预签名令牌），
        // 属于"内容出设备"，与上传同一类后果。判据只看敏感这一条，不看云端开关 ——
        // 关掉云端 AI 时网页提取本来就该继续可用。
        //
        // 用的是 webFetchBlockedReason 而不是 sensitiveEgressReason：后者的放行条件是
        // "云端上传确认"，而那个确认要求云端通路可用，默认无网关的安装包里
        // 敏感网页会永远抓不下来（F-5 记录的死路）。
        CloudUploadGate.webFetchBlockedReason(record)?.let { reason ->
            return ProcessorResult.Blocked(reason.message)
        }

        val url = record.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ProcessorResult.Permanent("记录没有链接，无法提取正文")

        val page = try {
            webPageLoader.load(url)
        } catch (e: HttpStatusException) {
            if (e.statusCode in SITE_REFUSED_CODES) {
                // 站点明确拒绝程序化访问（知乎等）：重试结论不会变，也不该让整条记录显示"失败"。
                // 标题与链接仍在 rawText/sourceTitle 里，摘要与向量继续按元数据生成 ——
                // 用户至少能按标题搜到，而不是得到一条红色失败记录。
                Log.i(
                    TAG,
                    "站点拒绝自动读取 HTTP ${e.statusCode}，按仅标题处理 recordId=${record.id}",
                )
                return ProcessorResult.Success
            }
            // 5xx / 其他异常交给 Executor 的重试策略
            throw e
        }
        val extracted = HtmlContentExtractor.extract(page)

        if (extracted.title == null && extracted.text.isBlank()) {
            // 既没标题也没正文：典型是纯前端渲染或登录墙。重试同样拿不到，
            // 按"仅标题"降级成功处理，避免把整条记录打成 FAILED。
            Log.i(TAG, "页面没有可提取正文，按仅标题处理 recordId=${record.id}")
            return ProcessorResult.Success
        }

        val now = System.currentTimeMillis()
        extracted.title?.let { title ->
            derivedContentRepository.upsert(
                DerivedContent(
                    id = DerivedContentKeys.idFor(record.id, DerivedType.TITLE),
                    recordId = record.id,
                    contentType = DerivedType.TITLE,
                    content = title,
                    processorVersion = PROCESSOR_VERSION,
                    processedAt = now,
                    errorMessage = null,
                ),
            )
        }
        derivedContentRepository.upsert(
            DerivedContent(
                id = DerivedContentKeys.idFor(record.id, DerivedType.PAGE_TEXT),
                recordId = record.id,
                contentType = DerivedType.PAGE_TEXT,
                content = extracted.text,
                processorVersion = PROCESSOR_VERSION,
                processedAt = now,
                errorMessage = if (extracted.partial) PARTIAL_REASON else null,
            ),
        )

        // 网页正文可能含 Token / 卡号：复查标题+正文，命中则升级敏感标记，
        // 挡住后续 Summarize / Embed 的上传（QODER P1-1）。
        sensitivityGuard.elevateIfSuspected(
            record.id,
            listOfNotNull(extracted.title, extracted.text).joinToString("\n"),
        )

        // 快照直接取自同一响应的字节：不再发第二次请求，SSRF 校验已经在 load() 里做过。
        // 正文图片在保存时内嵌为 data URI（C 补）：查看页禁网禁脚本，远程 <img> 必然裂图，
        // 只有把图抓进 HTML 里，离线打开才有图；单图失败由内嵌器降级，不拖垮快照。
        saveQuietly("HTML 快照") {
            val inlined = SnapshotImageInliner.inline(page, imageFetcher)
            if (inlined.failedCount > 0) {
                Log.i(
                    TAG,
                    "快照内嵌图片：成功 ${inlined.inlinedCount} 张，失败跳过 ${inlined.failedCount} 张",
                )
            }
            snapshotStore.saveHtmlSnapshot(record.id, inlined.htmlBytes)
        }
        extracted.imageUrl?.let { imageUrl ->
            saveQuietly("og:image") {
                val image = imageFetcher.fetch(imageUrl)
                snapshotStore.saveOgImage(record.id, image.bytes, image.mimeType)
            }
        }

        Log.i(
            TAG,
            "网页提取完成 recordId=${record.id} 标题=${extracted.title?.length ?: 0}字 " +
                "正文=${extracted.text.length}字 partial=${extracted.partial}",
        )
        return ProcessorResult.Success
    }

    /** 附加能力失败只留痕：磁盘满、图片 404、og:image 指向内网，都不该连累正文提取。 */
    private inline fun saveQuietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what 保存失败（不影响正文提取）", e)
        }
    }

    companion object {
        internal const val PROCESSOR_VERSION = "jsoup/1.23.2"

        /** 正文偏短时的说明文案，会显示在详情页的正文上方。 */
        internal const val PARTIAL_REASON = "正文较少，可能需要登录或页面由脚本渲染"

        /**
         * 视为"站点拒绝自动读取"的状态码。
         *
         * 401/403 是登录墙或反爬、429 是限流、451 是法律原因不可用 —— 同一请求
         * 立即重试都不会得到不同结果。5xx 不在此列（服务端临时故障，仍可重试）。
         */
        internal val SITE_REFUSED_CODES = setOf(401, 403, 429, 451)

        private const val TAG = "WebExtractWorker"
    }
}
