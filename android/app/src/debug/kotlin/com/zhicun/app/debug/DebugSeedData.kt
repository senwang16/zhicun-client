package com.zhicun.app.debug

import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.repository.RecordRepository
import java.util.UUID
import kotlinx.coroutines.flow.first

/**
 * 调试用示例数据（**仅 debug 构建**，release APK 里不存在这个类）。
 *
 * 为什么需要它：M3 只有"看"没有"存"，M4 才有分享入口。若不做这个工具，
 * 收件箱永远显示空状态，无法手工验收卡片排版与详情页。
 *
 * 触发方式（设备或模拟器）：
 * ```
 * adb shell am start -n com.zhicun.app/com.zhicun.app.debug.DebugSeedActivity
 * ```
 * 幂等：库里已有数据时不再插入，避免反复触发把收件箱塞满。
 */
object DebugSeedData {

    /** 生成一批覆盖各类型/状态的示例记录（时间戳递减，保证列表有稳定顺序）。 */
    fun buildRecords(now: Long = System.currentTimeMillis()): List<Record> = listOf(
        Record(
            id = UUID.randomUUID().toString(),
            type = RecordType.WEB_LINK,
            captureSource = CaptureSource.SYSTEM_SHARE,
            rawText = "Jetpack Compose 是 Android 的现代声明式 UI 工具包。\n" +
                "用更少的代码、更直观的 API 构建原生界面，并与现有代码逐步互操作。",
            sourceUrl = "https://developer.android.com/jetpack/compose",
            sourceTitle = "Jetpack Compose 官方文档",
            sourceApp = "Chrome",
            createdAt = now,
            updatedAt = now,
            localStatus = LocalStatus.SAVED,
            processingStatus = ProcessingStatus.DONE,
            sensitivityStatus = SensitivityStatus.CLEAR,
            recordRevision = 1,
            isDeleted = false,
        ),
        Record(
            id = UUID.randomUUID().toString(),
            type = RecordType.TEXT_NOTE,
            captureSource = CaptureSource.MANUAL,
            rawText = "周末待办\n买牛奶两盒\n把阳台的灯换掉",
            sourceUrl = null,
            sourceTitle = null,
            sourceApp = null,
            createdAt = now - 60_000L,
            updatedAt = now - 60_000L,
            localStatus = LocalStatus.SAVED,
            processingStatus = ProcessingStatus.PENDING,
            sensitivityStatus = SensitivityStatus.UNKNOWN,
            recordRevision = 1,
            isDeleted = false,
        ),
        Record(
            id = UUID.randomUUID().toString(),
            type = RecordType.IMAGE,
            captureSource = CaptureSource.GALLERY,
            rawText = null,
            sourceUrl = null,
            sourceTitle = "桌面截图 2026-10-21",
            sourceApp = "相册",
            createdAt = now - 120_000L,
            updatedAt = now - 120_000L,
            localStatus = LocalStatus.SAVED,
            processingStatus = ProcessingStatus.PROCESSING,
            sensitivityStatus = SensitivityStatus.UNKNOWN,
            recordRevision = 1,
            isDeleted = false,
        ),
        Record(
            id = UUID.randomUUID().toString(),
            type = RecordType.VOICE_NOTE,
            captureSource = CaptureSource.FLOATING,
            rawText = "（语音转写中）",
            sourceUrl = null,
            sourceTitle = null,
            sourceApp = null,
            createdAt = now - 180_000L,
            updatedAt = now - 180_000L,
            localStatus = LocalStatus.SAVED,
            processingStatus = ProcessingStatus.FAILED,
            sensitivityStatus = SensitivityStatus.SUSPECTED,
            recordRevision = 1,
            isDeleted = false,
        ),
    )

    /** 库为空才写入；返回实际写入条数（0 表示已有数据、未改动）。 */
    suspend fun seedIfEmpty(repository: RecordRepository): Int {
        if (repository.observeAllOnce().isNotEmpty()) return 0
        val records = buildRecords()
        records.forEach { repository.save(it) }
        return records.size
    }
}

/**
 * `observeAll()` 是无限 Flow，取首帧需要一个辅助扩展。
 * 放在这里而不是塞进仓储接口：仓储接口只暴露业务语义，不迁就调试工具。
 */
private suspend fun RecordRepository.observeAllOnce(): List<Record> = observeAll().first()
