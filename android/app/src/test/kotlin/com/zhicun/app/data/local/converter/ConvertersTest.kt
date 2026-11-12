package com.zhicun.app.data.local.converter

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 枚举列的解码契约（2026-11-10 Bug 4）。
 *
 * ## 为什么值只能"按最不坏的语义"兜底
 * 这一组用例把每个兜底值钉住，防止后人"顺手"改成看起来更自然的默认值：
 * 例如把未知敏感状态兜底成 `UNKNOWN` 会让 `isSensitive=false`，
 * 等于把一段来路不明的内容当成可上传 —— 那是政策问题，不是显示问题。
 *
 * `@RunWith(RobolectricTestRunner)` 是必需的：未知值分支会 `Log.w`，
 * 而纯 JVM 下 `android.util.Log` 未 mock（本工程没开 returnDefaultValues）。
 */
@RunWith(RobolectricTestRunner::class)
class ConvertersTest {

    // ------------------------------------------------------------ 正常往返

    @Test
    fun derivedType_roundTrip() {
        assertEquals(DerivedType.TRANSCRIPTION, Converters.toDerivedType("TRANSCRIPTION"))
        assertEquals("TRANSCRIPTION", Converters.fromDerivedType(DerivedType.TRANSCRIPTION))
    }

    // ------------------------------------------------------------ 派生内容：跳过而不是抛

    /**
     * 真机 bug 的原样输入：预埋 SQL 写了 `TRANSCRIBE`，枚举里只有 `TRANSCRIPTION`。
     *
     * 这里必须得到 `UNKNOWN`（由仓储层跳过该行），**不能**抛异常 ——
     * 抛异常发生在 Room 读行途中，会让整页详情报 "Couldn't load this record"。
     */
    @Test
    fun toDerivedType_unknownRaw_isUnknownInsteadOfThrowing() {
        assertEquals(DerivedType.UNKNOWN, Converters.toDerivedType("TRANSCRIBE"))
        assertEquals(DerivedType.UNKNOWN, Converters.toDerivedType(""))
        // 字面量 UNKNOWN 同样视为不可信数据（见 DerivedType.fromRaw）
        assertEquals(DerivedType.UNKNOWN, Converters.toDerivedType("UNKNOWN"))
    }

    @Test
    fun derivedTypeFromRaw_unknown_isNull() {
        assertNull(DerivedType.fromRaw("TRANSCRIBE"))
        assertNull(DerivedType.fromRaw(null))
        assertNull(DerivedType.fromRaw("UNKNOWN"))
        assertEquals(DerivedType.SUMMARY, DerivedType.fromRaw("SUMMARY"))
    }

    // ------------------------------------------------------------ 其余枚举的兜底方向

    /** 记录类型只影响徽标；猜错也不能让用户打不开自己的记录。 */
    @Test
    fun toRecordType_unknown_fallsBackToTextNote() {
        assertEquals(RecordType.TEXT_NOTE, Converters.toRecordType("AUDIOBOOK"))
    }

    @Test
    fun toCaptureSource_unknown_fallsBackToManual() {
        assertEquals(CaptureSource.MANUAL, Converters.toCaptureSource("WIDGET"))
    }

    /** 落盘状态：绝不能把未知状态说成"已保存"。 */
    @Test
    fun toLocalStatus_unknown_fallsBackToFailed() {
        assertEquals(LocalStatus.FAILED, Converters.toLocalStatus("UPLOADING"))
    }

    /** 处理状态：未知按失败，界面才会给出"重新生成"，不会假装已完成。 */
    @Test
    fun toProcessingStatus_unknown_fallsBackToFailed() {
        assertEquals(ProcessingStatus.FAILED, Converters.toProcessingStatus("REVIEWING"))
    }

    /**
     * 敏感状态：未知必须进入"敏感"一侧（SUSPECTED 需要用户确认）。
     * 兜底成 UNKNOWN 会让 `isSensitive=false`，等于放行未确认内容。
     */
    @Test
    fun toSensitivityStatus_unknown_fallsBackToSuspected() {
        assertEquals(SensitivityStatus.SUSPECTED, Converters.toSensitivityStatus("REVIEWING"))
    }

    @Test
    fun toAssetSaveStatus_unknown_fallsBackToFailed() {
        assertEquals(AssetSaveStatus.FAILED, Converters.toAssetSaveStatus("PARTIAL"))
    }

    @Test
    fun toJobStatus_unknown_fallsBackToFailed() {
        assertEquals(JobStatus.FAILED, Converters.toJobStatus("PAUSED"))
    }

    /** 标签来源猜成 AI 会参与自动清理，因此未知值按用户标签保留。 */
    @Test
    fun toTagSource_unknown_fallsBackToUser() {
        assertEquals(TagSource.USER, Converters.toTagSource("IMPORT"))
    }

    /**
     * job_type **刻意保持严格**：它决定派哪个 Worker，兜底成任何真实类型
     * 都等于拿错误的任务处理用户数据。读路径已在 DAO 层按已知类型过滤。
     */
    @Test(expected = IllegalArgumentException::class)
    fun toJobType_unknown_keepsStrictParsing() {
        Converters.toJobType("TRANSCRIBE")
    }

    // ------------------------------------------------------------ 回归：已知值不受兜底影响

    @Test
    fun knownValues_areNotAffectedByFallbacks() {
        assertEquals(JobType.EMBED, Converters.toJobType("EMBED"))
        assertEquals(ProcessingStatus.LOCAL_ONLY, Converters.toProcessingStatus("LOCAL_ONLY"))
        assertEquals(SensitivityStatus.CLEAR, Converters.toSensitivityStatus("CLEAR"))
        assertEquals(LocalStatus.SAVED, Converters.toLocalStatus("SAVED"))
    }
}