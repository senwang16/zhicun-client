package com.zhicun.app.domain.worker

import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.RecordType

/**
 * 记录类型 → 需要跑的后台任务。
 *
 * 单独抽出来是因为**两个地方要用同一张表**：保存后的自动调度（`ScheduleProcessingUseCase`）
 * 与用户在详情页点"重新识别/重新转写"（`RetryProcessingUseCase`）。两处各写一份 `when`，
 * 迟早出现"新建时会跑、重试时不会跑"的错位。
 *
 * ## M11 起每条记录都要摘要（SUMMARIZE）
 * 摘要的输入是"记录里所有可读文字"，因此图片/网页/语音类型也在列。它们与
 * OCR / 网页提取 / 转写**并行入队**，谁先跑完不一定 —— `SummarizeWorker` 会检查上游任务
 * 是否还在跑：在跑就返回"可重试"，跑完再取正文。这样不需要在调度层造一条任务依赖链
 * （WorkManager 的链式任务与唯一任务名、补投逻辑都会互相干扰）。
 *
 * ## 云端任务与本地任务
 * [CLOUD_JOBS] 里的是"把内容发到网关"的任务。两道门禁都在调度侧拦：
 * 疑似敏感的记录（M11）、用户关掉云端 AI 时（M14），都不排这些任务。
 */
object ProcessingJobTargets {

    /**
     * 需要把内容上传到 AI 网关的任务。
     *
     * ## 为什么 TRANSCRIPTION 在这里（M14 修正 M9 的漏项）
     * 语音转写走的是云端 ASR（T-006，`POST /api/v1/transcribe`），音频字节会被上传。
     * M9 实现时这张表只有摘要与向量，导致**一段疑似敏感的录音会被原样传上云端**，
     * 而界面上还显示"仅本地"——与 PRD §6.4 直接冲突。
     * 收录判据是"这个任务会不会把内容发出设备"，不是"它是不是 AI 任务"。
     */
    val CLOUD_JOBS: Set<JobType> = setOf(
        JobType.SUMMARIZE,
        JobType.EMBED,
        JobType.TRANSCRIPTION,
    )

    fun of(type: RecordType): List<JobType> = when (type) {
        RecordType.IMAGE -> listOf(JobType.OCR, JobType.SUMMARIZE, JobType.EMBED)
        RecordType.WEB_LINK ->
            listOf(JobType.WEBPAGE_EXTRACT, JobType.SUMMARIZE, JobType.EMBED)

        // M9：录音先落盘再转写。转写失败不影响音频播放，因此这条任务失败不该让记录不可用
        RecordType.VOICE_NOTE ->
            listOf(JobType.TRANSCRIPTION, JobType.SUMMARIZE, JobType.EMBED)

        RecordType.TEXT_NOTE,
        RecordType.SHARED_TEXT,
        RecordType.FILE,
        -> listOf(JobType.SUMMARIZE, JobType.EMBED)

        // M20：合集只有"对成员内容的理解"要算，没有自己的 OCR/网页提取/转写。
        // 成员的上游任务各自挂在成员记录上；合集摘要会等待成员正文就绪。
        RecordType.COLLECTION -> listOf(JobType.SUMMARIZE, JobType.EMBED)
    }

    /**
     * 本次**真的会执行**的任务（2026-10-23 整改 F-4）。
     *
     * 云端被挡（开关关闭 / 网关未配置 / 敏感未确认）时滤掉 [CLOUD_JOBS]。
     * 返回空列表意味着"点重试不会有任何事发生" —— 界面据此**不显示**重试按钮，
     * 改成"去打开云端 AI"的设置入口。
     *
     * 这份过滤必须与 [com.zhicun.app.domain.usecase.RetryProcessingUseCase] 共用：
     * 界面自己写一个近似判据的话，迟早出现"按钮能点、点了什么都没发生"
     * （正是 F-4 记录的问题：`TEXT_NOTE` 的任务全是云端任务，云关时一个都跑不了）。
     *
     * @param cloudBlocked 记录级门禁结果，即 `CloudUploadGate.reasonOf(...) != null`
     */
    fun runnable(type: RecordType, cloudBlocked: Boolean): List<JobType> =
        of(type).filter { it !in CLOUD_JOBS || !cloudBlocked }
}
