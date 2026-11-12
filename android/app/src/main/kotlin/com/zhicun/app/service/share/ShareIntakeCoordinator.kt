package com.zhicun.app.service.share

import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.zhicun.app.R
import com.zhicun.app.domain.usecase.SaveRecordUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * 分享接收的编排层：解析 → 保存 → 决定提示文案。
 *
 * 从 Activity 里抽出来的唯一目的是**可测**：Activity 受 Hilt 托管，
 * 单测里要跑起来得引入一整套 Hilt 测试环境；而这段"解析完怎么存、失败了说什么"
 * 才是真正的业务编排，值得用普通 Robolectric 测试覆盖。
 *
 * Activity 于是退化成一个壳：拿到 [ShareFeedback] → Toast → finish。
 */
@Singleton
class ShareIntakeCoordinator @Inject constructor(
    private val saveRecord: SaveRecordUseCase,
) {

    /**
     * @param mimeTypeLookup 用于把通配声明类型（如相册分享的 `image` 前缀）回查成真实类型；
     *   传 `null` 时该步骤退化，附件会以"类型未知"入库（测试场景常用）
     */
    suspend fun handle(
        intent: Intent?,
        packageManager: PackageManager?,
        referrerPackage: String?,
        mimeTypeLookup: MimeTypeLookup? = null,
    ): ShareFeedback {
        val payload = SharePayloadParser.parse(
            intent = intent,
            packageManager = packageManager,
            referrerPackage = referrerPackage,
            mimeTypeLookup = mimeTypeLookup,
        )

        if (!payload.hasContent) return emptyFeedback(payload)

        return try {
            savedFeedback(saveRecord(payload))
        } catch (e: CancellationException) {
            // 协程取消必须原样上抛，不能被吞掉（QODER P3）
            throw e
        } catch (e: Exception) {
            // 空 catch 禁止（AGENTS.md §8）：落日志 + 给用户一句人话
            Log.e(TAG, "保存分享内容失败", e)
            ShareFeedback(R.string.share_save_failed)
        }
    }

    private companion object {
        const val TAG = "ShareIntake"
    }
}
