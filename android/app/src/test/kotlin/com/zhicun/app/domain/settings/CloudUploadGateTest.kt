package com.zhicun.app.domain.settings

import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.fake.testRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 上传闸门的唯一判据（M14，纯函数）。
 *
 * 调度、重试、各云端 Worker 与状态聚合都读这一个函数，因此它的行为必须毫无歧义：
 * 返回 null 才允许上传，任何非 null 都是"不许传"的原因。
 */
class CloudUploadGateTest {

    @Test
    fun Gate_openAndClear_allowsUpload() {
        assertNull(
            CloudUploadGate.blockReason(
                cloudAiEnabled = true,
                gatewayConfigured = true,
                record = testRecord(sensitivityStatus = SensitivityStatus.CLEAR),
            ),
        )
    }

    /** `UNKNOWN` 不等于敏感：检测还没跑完时默认放行，否则所有新记录都会被挡住。 */
    @Test
    fun Gate_openAndUnknown_allowsUpload() {
        assertNull(
            CloudUploadGate.blockReason(
                cloudAiEnabled = true,
                gatewayConfigured = true,
                record = testRecord(sensitivityStatus = SensitivityStatus.UNKNOWN),
            ),
        )
    }

    @Test
    fun Gate_openButSuspected_blocksWithSensitiveReason() {
        assertEquals(
            CloudUploadGate.REASON_SENSITIVE,
            CloudUploadGate.blockReason(
                cloudAiEnabled = true,
                gatewayConfigured = true,
                record = testRecord(sensitivityStatus = SensitivityStatus.SUSPECTED),
            ),
        )
    }

    @Test
    fun Gate_openButConfirmedSensitive_blocksWithoutUserConfirmation() {
        assertEquals(
            CloudUploadGate.REASON_SENSITIVE,
            CloudUploadGate.blockReason(
                cloudAiEnabled = true,
                gatewayConfigured = true,
                record = testRecord(sensitivityStatus = SensitivityStatus.CONFIRMED_SENSITIVE),
            ),
        )
    }

    /** 用户确认过**当前内容版本**：允许上传，但敏感标记本身不洗白（PRD §6.4）。 */
    @Test
    fun Gate_sensitiveButConfirmedCurrentRevision_allowsUpload() {
        assertNull(
            CloudUploadGate.blockReason(
                cloudAiEnabled = true,
                gatewayConfigured = true,
                record = testRecord(
                    sensitivityStatus = SensitivityStatus.SUSPECTED,
                    recordRevision = 3,
                    cloudConfirmedRevision = 3,
                ),
            ),
        )
    }

    /** 确认后内容又被编辑：修订号变了，旧确认立即失效。 */
    @Test
    fun Gate_sensitiveWithStaleConfirmation_blocksAgain() {
        assertEquals(
            CloudUploadGate.REASON_SENSITIVE,
            CloudUploadGate.blockReason(
                cloudAiEnabled = true,
                gatewayConfigured = true,
                record = testRecord(
                    sensitivityStatus = SensitivityStatus.SUSPECTED,
                    recordRevision = 4,
                    cloudConfirmedRevision = 3,
                ),
            ),
        )
    }

    @Test
    fun Gate_closedButClear_blocksWithCloudReason() {
        assertEquals(
            CloudUploadGate.REASON_CLOUD_DISABLED,
            CloudUploadGate.blockReason(
                cloudAiEnabled = false,
                gatewayConfigured = true,
                record = testRecord(sensitivityStatus = SensitivityStatus.CLEAR),
            ),
        )
    }

    /** 默认安装包没有网关配置：不上传，也别让每条记录挂"处理失败"（QODER P2-3）。 */
    @Test
    fun Gate_gatewayNotConfigured_blocksWithConfigReason() {
        assertEquals(
            CloudUploadGate.REASON_NOT_CONFIGURED,
            CloudUploadGate.blockReason(
                cloudAiEnabled = true,
                gatewayConfigured = false,
                record = testRecord(sensitivityStatus = SensitivityStatus.CLEAR),
            ),
        )
    }

    /**
     * 多个条件同时成立时，报"开关已关闭"。
     *
     * 不是随便挑的：开关是用户**刚刚**亲手关的，这个解释比"系统觉得你的内容敏感"
     * 更贴近他此刻的意图，也更不会让他去详情页反复点"确认上传"（点了也没用）。
     */
    @Test
    fun Gate_cloudDisabledAndSensitive_reportsCloudDisabled() {
        assertEquals(
            CloudUploadGate.REASON_CLOUD_DISABLED,
            CloudUploadGate.blockReason(
                cloudAiEnabled = false,
                gatewayConfigured = true,
                record = testRecord(sensitivityStatus = SensitivityStatus.SUSPECTED),
            ),
        )
    }

    /** 网关未配置优先于敏感：两种情况都传不出去，但前者是用户可先修复的配置问题。 */
    @Test
    fun Gate_notConfiguredAndSensitive_reportsNotConfigured() {
        assertEquals(
            CloudUploadGate.REASON_NOT_CONFIGURED,
            CloudUploadGate.blockReason(
                cloudAiEnabled = true,
                gatewayConfigured = false,
                record = testRecord(sensitivityStatus = SensitivityStatus.SUSPECTED),
            ),
        )
    }

    /**
     * 检索侧只问"云端这条路能不能走"（R-8）。
     *
     * 搜索没有"某一条记录"可以判，所以判据必须是通路级的；
     * 但也不能让检索自己写 `if (!cloudAiEnabled)` —— 那会漏掉网关条件，
     * 与调度/Worker 侧的判据分叉。
     */
    @Test
    fun CloudPathEnabled_returnsNull() {
        assertNull(CloudUploadGate.cloudPathBlockedReason(cloudAiEnabled = true, gatewayConfigured = true))
    }

    @Test
    fun CloudPath_disabledOrUnconfigured_reportsExactReason() {
        assertEquals(
            CloudUploadGate.Reason.CLOUD_DISABLED,
            CloudUploadGate.cloudPathBlockedReason(cloudAiEnabled = false, gatewayConfigured = true),
        )
        assertEquals(
            CloudUploadGate.Reason.NOT_CONFIGURED,
            CloudUploadGate.cloudPathBlockedReason(cloudAiEnabled = true, gatewayConfigured = false),
        )
    }

    // ------------------------------------------------- 确认范围（2026-10-23 R-2）

    /**
     * 确认过的是"原文"，这一次要发的是后来才出现的转写文本 → 必须重新确认。
     *
     * 这就是 R-2 的漏洞本身：版本号没变（派生内容不递增 revision），
     * 只比版本号会把用户没看过的内容一起放行。
     */
    @Test
    fun Gate_confirmedScopeMissingRequiredPart_blocksSensitive() {
        assertEquals(
            CloudUploadGate.REASON_SENSITIVE,
            CloudUploadGate.blockReason(
                cloudAiEnabled = true,
                gatewayConfigured = true,
                record = testRecord(
                    sensitivityStatus = SensitivityStatus.SUSPECTED,
                    recordRevision = 2,
                    cloudConfirmedRevision = 2,
                    cloudConfirmedScope = setOf(ContentPart.RAW_TEXT),
                ),
                requiredParts = setOf(ContentPart.RAW_TEXT, ContentPart.TRANSCRIPTION),
            ),
        )
    }

    /** 这一次用到的东西都在确认范围里 → 放行。 */
    @Test
    fun Gate_confirmedScopeCoversRequiredParts_allowsUpload() {
        assertNull(
            CloudUploadGate.blockReason(
                cloudAiEnabled = true,
                gatewayConfigured = true,
                record = testRecord(
                    sensitivityStatus = SensitivityStatus.SUSPECTED,
                    recordRevision = 2,
                    cloudConfirmedRevision = 2,
                    cloudConfirmedScope = setOf(ContentPart.RAW_TEXT, ContentPart.TRANSCRIPTION),
                ),
                requiredParts = setOf(ContentPart.TRANSCRIPTION),
            ),
        )
    }

    /**
     * 摘要 / 标签不需要单独确认：它们是 AI 从**已确认内容**里浓缩出来的，
     * 却会被下一步（向量化）反过来外发。若也要求确认，就会出现
     * "确认 → 生成摘要 → 向量被拦 → 再确认 → 摘要又变"的死循环，语义检索永久不可用。
     */
    @Test
    fun Gate_aiProducedParts_doNotRequireTheirOwnConfirmation() {
        assertNull(
            CloudUploadGate.blockReason(
                cloudAiEnabled = true,
                gatewayConfigured = true,
                record = testRecord(
                    sensitivityStatus = SensitivityStatus.SUSPECTED,
                    recordRevision = 2,
                    cloudConfirmedRevision = 2,
                    cloudConfirmedScope = setOf(ContentPart.RAW_TEXT),
                ),
                requiredParts = setOf(ContentPart.SUMMARY, ContentPart.TAGS),
            ),
        )
    }

    /**
     * 给不出内容块时退回版本级判据（入口预检、网页抓取都属这一档）。
     *
     * 这条刻意钉住"留空 ≠ 更严格"：留空只用于**还没算出来**的场合，
     * 真正发请求前必须带上清单，否则 R-2 的漏洞仍在。
     */
    @Test
    fun Gate_noRequiredParts_fallsBackToRevisionCheck() {
        assertNull(
            CloudUploadGate.blockReason(
                cloudAiEnabled = true,
                gatewayConfigured = true,
                record = testRecord(
                    sensitivityStatus = SensitivityStatus.SUSPECTED,
                    recordRevision = 2,
                    cloudConfirmedRevision = 2,
                    cloudConfirmedScope = setOf(ContentPart.RAW_TEXT),
                ),
            ),
        )
    }
}
