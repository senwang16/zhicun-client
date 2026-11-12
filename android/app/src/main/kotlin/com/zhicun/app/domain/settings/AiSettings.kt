package com.zhicun.app.domain.settings

/**
 * 云端 AI 处理开关（M14）。
 *
 * ## 为什么这个开关必须影响"调度"而不只是"界面"
 * 一旦任务被排进 WorkManager 队列，补投、手动重试、开机恢复等路径都可能绕过执行侧的判断。
 * 把开关判据放在**调度侧**（不排任务），就没有任何一条路径能把它悄悄传上去 ——
 * 与敏感内容门禁（`ScheduleProcessingUseCase`，M11）用的是同一条思路。
 *
 * ## 默认值取"开"
 * 关掉的后果是摘要、AI 标签、语义搜索、语音转写全部不可用：新用户装完打开会以为
 * "这 App 什么都不会"。因此默认开，并把"会发送什么"写在设置页开关下方（PRD §8.3 的知情要求）。
 * 要改成隐私优先只动 [PreferencesAiSettings] 一处默认值。
 */
interface AiSettings {

    /** 是否允许把内容发送到云端做 AI 处理。 */
    val cloudAiEnabled: Boolean

    fun setCloudAiEnabled(enabled: Boolean)
}
