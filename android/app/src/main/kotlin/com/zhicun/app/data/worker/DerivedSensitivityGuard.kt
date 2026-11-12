package com.zhicun.app.data.worker

import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 派生正文的敏感复查（2026-10-22 QODER P1-1 整改）。
 *
 * 保存时 [SensitiveContentDetector] 只覆盖"用户当场交进来的文字"（正文 + 分享标题）；而 OCR、
 * 语音转写、网页正文是后台任务才写出的"派生正文"，里面可能含证件号、卡号、API Key 等，
 * 却会被 Summarize / Embed 拼进输入再上传。若不在派生正文落库时复查，一条"分享文字干净、
 * 但截图里是身份证"的记录就会绕过 `CloudUploadGate` 被上传——违背 strings.xml 对用户的承诺。
 *
 * 用法：OCR / 转写 / 网页提取各自写完派生内容后，立刻调用 [elevateIfSuspected]。
 * 命中即把记录升为 `SUSPECTED`；后续云端任务（摘要/向量）执行时会再读一次门禁
 * （见 [CloudUploadGate]），从而被拦下。语音场景有一个固有暴露面：**音频在转写前无法预检**，
 * 那一次音频上传已经发生；这里挡住的是"转写文本被摘要/向量二次上传"。
 */
@Singleton
class DerivedSensitivityGuard @Inject constructor(
    private val detector: SensitiveContentDetector,
    private val recordRepository: RecordRepository,
    private val collectionRepository: CollectionRepository,
) {

    /**
     * 派生正文落库后调用。
     *
     * 只在"命中"时升级，绝不把 `CLEAR`/`UNKNOWN` 写回 —— 本类只负责发现新风险，
     * 不负责洗白；洗白必须由用户显式确认（PRD §6.4）。升级同时作废已有的云端确认
     * （新发现的风险使旧确认失效），由 `RecordRepository.elevateSensitivity` 原子完成。
     *
     * ## 同时通知合集（M20）
     * 不管是否命中，派生正文落地都意味着"成员内容变了"：所属合集要重算敏感并集、
     * 作废旧确认，详情页也要因为这次写库而刷新。已删除成员没有合集参与机会，刷新是空操作。
     */
    suspend fun elevateIfSuspected(
        recordId: String,
        derivedText: String,
        now: Long = System.currentTimeMillis(),
    ) {
        if (derivedText.isBlank()) return
        if (detector.scan(derivedText).isSuspected) {
            recordRepository.elevateSensitivity(recordId, now)
        }
        collectionRepository.refreshForMemberChange(recordId, now)
    }
}