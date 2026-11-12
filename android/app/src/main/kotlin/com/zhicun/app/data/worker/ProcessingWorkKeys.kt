package com.zhicun.app.data.worker

import com.zhicun.app.domain.model.JobType

/**
 * Worker 输入参数与唯一任务名的约定。
 *
 * 唯一任务名必须是 `recordId + jobType` 的组合：只按 recordId 会让同一条记录的 OCR 与网页提取
 * 互相顶掉（WorkManager 的 unique work 是按名字去重的）。冒号做分隔符是因为 recordId 是 UUID，
 * 不含冒号，不会产生歧义。
 */
object ProcessingWorkKeys {

    const val RECORD_ID = "recordId"
    const val JOB_TYPE = "jobType"

    /** 所有处理任务的公共 tag，便于调试与批量取消。 */
    const val WORK_TAG = "zhicun_processing"

    fun uniqueWorkName(recordId: String, jobType: JobType): String = "processing:$recordId:$jobType"
}
