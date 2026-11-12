package com.zhicun.app.domain.clipboard

/**
 * 一次剪贴板读取的结论。
 *
 * 把"没内容""不是文字""系统不给读"分成三个分支，是因为界面要给三种不同的话：
 * 空剪贴板让用户手动记一条，图片/文件告诉用户不支持，读不到则提示系统限制。
 * 合并成 `null` 时用户只会看到一句含糊的"没读到"。
 */
sealed interface ClipboardReadResult {

    /** 读到可保存的文字（可能含首尾空白，由调用方归一化）。 */
    data class Text(val value: String) : ClipboardReadResult

    /** 剪贴板为空、或内容只有空白。 */
    data object Empty : ClipboardReadResult

    /** 剪贴板里是图片 / 文件 / URI 等非文字内容（本功能不支持）。 */
    data object Unsupported : ClipboardReadResult

    /** 剪贴板服务不可用、没有前台焦点、或读取抛异常。 */
    data object Unavailable : ClipboardReadResult
}

/**
 * 读系统剪贴板（M13）。
 *
 * ## 为什么是接口
 * `ClipboardManager` 是 Android 框架类型，直接依赖会让"剪贴板入口"这条链路
 * 只能在 Robolectric 或真机上验证。抽成接口后，`CaptureClipboardUseCase` 的
 * "有内容就存、空白就提示、保存失败怎么办"全都能用纯 JVM 用例穷举。
 *
 * ## 调用前提：App 必须处于前台
 * Android 10（API 29）起，只有默认输入法或**当前获得焦点**的应用能读剪贴板。
 * 悬浮球本身不在前台，所以链路必须是"点剪贴板 → 拉起一个透明 Activity → 在它的
 * `onResume` 里读"。后台 Service 里直接读只会稳定拿到 null，然后在用户看来
 * 就是"点了没反应"。
 */
interface ClipboardReader {

    suspend fun read(): ClipboardReadResult
}
