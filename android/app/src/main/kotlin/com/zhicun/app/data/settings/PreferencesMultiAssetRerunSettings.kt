package com.zhicun.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.zhicun.app.domain.settings.MultiAssetRerunSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MultiAssetRerunSettings] 的 SharedPreferences 实现。
 *
 * 与 [PreferencesOnboardingSettings] 同一取向：低频读写、不缓存。键名带 `v8`，
 * 将来若再做同类数据形态升级，可以另起一个键而不用兼容旧语义。
 */
@Singleton
class PreferencesMultiAssetRerunSettings @Inject constructor(
    @ApplicationContext context: Context,
) : MultiAssetRerunSettings {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val completed: Boolean
        get() = prefs.getBoolean(KEY_COMPLETED_V8, false)

    override fun markCompleted() {
        prefs.edit { putBoolean(KEY_COMPLETED_V8, true) }
    }

    private companion object {
        const val PREFS_NAME = "multi_asset_rerun"
        const val KEY_COMPLETED_V8 = "completed_v8"
    }
}
