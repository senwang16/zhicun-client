package com.zhicun.app.domain.settings

import java.util.Locale

/**
 * 界面语言（P11 / R-I18N-01…05）。
 *
 * 只有 `zh` / `en` 两种，没有"跟随系统"第三项：首启已经按设备语言落过一次地，
 * 之后若继续跟随系统，"我明明选了 English，重启又变回中文"就成了不可控行为。
 *
 * 切换只影响界面资源，**不产生任何 AI 请求、不重新生成历史结果**（R-I18N-02/05）；
 * AI 输出语言由派发任务时的快照单独记录（见 `ProcessingJob.outputLanguage`）。
 */
enum class AppLanguage(val code: String, val tag: String, val locale: Locale) {
    ZH("zh", "zh-CN", Locale.SIMPLIFIED_CHINESE),
    EN("en", "en", Locale.ENGLISH),
    ;

    companion object {
        /** 库里/网络上的 `zh`/`en`；缺失或非法一律按中文（与历史数据回填口径一致）。 */
        fun fromCode(raw: String?): AppLanguage = if (raw == EN.code) EN else ZH

        /**
         * 首次启动的默认值（R-I18N-01）：`zh*` → 中文，其余 → English。
         *
         * 只看语言子标签，不看地区：`zh-TW` / `zh-HK` 也用中文界面（当前只有简体资源，
         * Android 会按 `zh` 解析到 `values-zh`）；`fr-FR` / `en-US` 都落到 English。
         */
        fun fromDevice(locale: Locale = Locale.getDefault()): AppLanguage =
            if (locale.language == ZH.code) ZH else EN
    }
}

/** 语言偏好契约（纯 domain，测试可给定值）。 */
interface LanguagePreference {
    fun uiLanguage(): AppLanguage

    fun set(language: AppLanguage)
}

/**
 * 没有语言来源时的兜底（历史构造点与 JVM 测试）。
 *
 * 恒为中文：宁可回到旧行为，也不要因为少注入一个偏好就把中文用户的摘要变成英文。
 * `set` 是空操作 —— 真正的写入方是设置页，走 [UiLanguageSettings]。
 */
object DefaultLanguagePreference : LanguagePreference {
    override fun uiLanguage(): AppLanguage = AppLanguage.ZH

    override fun set(language: AppLanguage) = Unit
}

/**
 * 界面语言的读写口（P11-02）。
 *
 * 与 [LanguagePreference] 的区别：偏好只管存，这里的 `select` 还要同步系统
 * "应用语言"（Android 13+），因此实现落在 data 层。ViewModel 只依赖这个接口，
 * 不直接碰 `Context`。
 */
interface UiLanguageSettings {
    fun current(): AppLanguage

    fun select(language: AppLanguage)

    /**
     * 切换后调用方是否还要自己 `recreate()`。
     *
     * Android 13+ 写 `LocaleManager.applicationLocales` 会由系统重建 Activity，
     * 界面再调一次 `recreate()` 就是**双重建**（闪两下、ViewModel 之外的状态丢两次）。
     */
    val recreateRequiredAfterSelect: Boolean
}