package com.zhicun.app.data.local.converter

import android.util.Log
import androidx.room.TypeConverter
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.model.TagSource

/**
 * 枚举 ↔ 数据库 TEXT 的转换。
 *
 * 统一用枚举**常量名**存储（而非 ordinal）：ordinal 会随枚举顺序调整而静默错位，
 * 常量名可读、可迁移、可在 SQL 里直接比对。
 *
 * ⚠️ 因此**枚举常量名等同数据库契约**，重命名必须配套 Room Migration 做数据订正。
 *
 * ## 读方向一律容错（2026-11-10 Bug 4）
 * 写方向恒为 `name`，读方向不再直接用 `valueOf`：库里一旦出现无法识别的值
 * （手工 SQL 预埋、外部导入、版本回退），`valueOf` 会在 Room 读行时抛
 * `IllegalArgumentException`，一条脏数据就废掉整页详情 / 整批恢复任务。
 *
 * 现在的规则是**"按最不坏的语义兜底 + 留日志"**，兜底值的选择见下面每个转换器的注释；
 * 其中 `DerivedType` 没有安全的同类值，因此兜底到专用的 [DerivedType.UNKNOWN]，
 * 由仓储层跳过该行（不展示、不导出）。`JobType` 保持严格解析，理由见 `toJobType`。
 */
object Converters {

    private const val TAG = "Converters"

    @TypeConverter
    fun fromRecordType(value: RecordType): String = value.name

    /**
     * 未知记录类型兜底为 [RecordType.TEXT_NOTE]。
     *
     * 记录类型只决定徽标与默认分区，**不决定数据能否打开**：如果为了"类型不许猜"
     * 而抛异常，用户会连原文、附件、来源都看不到 —— 宁可少一个正确的徽标。
     */
    @TypeConverter
    fun toRecordType(value: String): RecordType = decode(value, RecordType.TEXT_NOTE)

    @TypeConverter
    fun fromCaptureSource(value: CaptureSource): String = value.name

    /** 采集来源只是统计口径，未知值按 [CaptureSource.MANUAL]（"不是任何自动化入口"）。 */
    @TypeConverter
    fun toCaptureSource(value: String): CaptureSource = decode(value, CaptureSource.MANUAL)

    @TypeConverter
    fun fromLocalStatus(value: LocalStatus): String = value.name

    /**
     * 未知落盘状态兜底为 [LocalStatus.FAILED]。
     *
     * 与 [AssetSaveStatus] 同一条取向：**不能凭未知状态向用户声称"已保存"**，
     * 失败态至少会走"重试 / 说明"的既有分支。
     */
    @TypeConverter
    fun toLocalStatus(value: String): LocalStatus = decode(value, LocalStatus.FAILED)

    @TypeConverter
    fun fromProcessingStatus(value: ProcessingStatus): String = value.name

    /**
     * 未知处理状态兜底为 [ProcessingStatus.FAILED]。
     *
     * 失败态会让详情页给出"重新生成"入口（`isIncomplete`），比假装 DONE
     * 把一条其实没处理完的记录显示成"已完成"更诚实。
     */
    @TypeConverter
    fun toProcessingStatus(value: String): ProcessingStatus = decode(value, ProcessingStatus.FAILED)

    @TypeConverter
    fun fromSensitivityStatus(value: SensitivityStatus): String = value.name

    /**
     * 未知敏感状态兜底为 [SensitivityStatus.SUSPECTED]（**保守方向**）。
     *
     * 不能兜底成 UNKNOWN：`UNKNOWN.isSensitive == false`，等于把未知内容当成可外发，
     * 与 PRD §6.4"不确定就不上传"相悖。SUSPECTED 会继续要求用户确认，风险最小。
     */
    @TypeConverter
    fun toSensitivityStatus(value: String): SensitivityStatus =
        decode(value, SensitivityStatus.SUSPECTED)

    @TypeConverter
    fun fromAssetSaveStatus(value: AssetSaveStatus): String = value.name

    /** 未知附件状态兜底为 [AssetSaveStatus.FAILED]：不能声称一个不知道是否落盘的文件已保存。 */
    @TypeConverter
    fun toAssetSaveStatus(value: String): AssetSaveStatus = decode(value, AssetSaveStatus.FAILED)

    @TypeConverter
    fun fromDerivedType(value: DerivedType): String = value.name

    /**
     * 未知派生类型 → [DerivedType.UNKNOWN]（由仓储层跳过该行）。
     *
     * 这里不能猜成某个真实类型：把 `TRANSCRIBE` 当成 `TRANSCRIPTION` 会让脏数据
     * 混进语音分区显示；猜成 SUMMARY 更会把一段转写当成摘要展示。
     * 跳过并留日志是唯一不产生假信息的选择（用户仍能看到其余派生内容与原文）。
     */
    @TypeConverter
    fun toDerivedType(value: String): DerivedType {
        DerivedType.fromRaw(value)?.let { return it }
        Log.w(TAG, "无法识别的派生内容类型 content_type=$value，该行将被跳过")
        return DerivedType.UNKNOWN
    }

    @TypeConverter
    fun fromJobType(value: JobType): String = value.name

    /**
     * **刻意保持严格**：未知 `job_type` 直接抛异常。
     *
     * 工作类型决定派哪个 Worker（`WorkManagerProcessingScheduler.workerClassFor`），
     * 兜底成任何一个真实类型都等于"拿错误的任务去处理用户数据"（例如把转写当 OCR 跑）。
     * 这类行只会来自数据损坏，正确处置是**不读它** —— 读路径已按已知类型过滤
     * （见 `ProcessingJobDao.getByStatuses` 的 jobTypes 参数），因此这里抛异常不会被
     * 正常流程触发，反而是"又有人绕过过滤直接读库"的哨兵。
     */
    @TypeConverter
    fun toJobType(value: String): JobType = JobType.valueOf(value)

    @TypeConverter
    fun fromJobStatus(value: JobStatus): String = value.name

    /** 未知任务状态兜底为 [JobStatus.FAILED]：宁可显示失败，也不假装它还在正常跑。 */
    @TypeConverter
    fun toJobStatus(value: String): JobStatus = decode(value, JobStatus.FAILED)

    @TypeConverter
    fun fromTagSource(value: TagSource): String = value.name

    /** 未知标签来源按用户标签处理（[TagSource.USER]）：AI 标签会参与自动清理，猜错会误删。 */
    @TypeConverter
    fun toTagSource(value: String): TagSource = decode(value, TagSource.USER)

    /**
     * 按常量名解析，失败时返回 [fallback] 并留一条日志。
     *
     * 日志里只打"列值 + 兜底值"，不打整行内容（避免把用户数据写进日志，与
     * `SensitiveContentDetector` 的同一取向）。
     */
    private inline fun <reified E : Enum<E>> decode(raw: String, fallback: E): E {
        enumValues<E>().firstOrNull { it.name == raw }?.let { return it }
        Log.w(TAG, "无法识别的枚举值 $raw，按 $fallback 兜底")
        return fallback
    }
}