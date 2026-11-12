package com.zhicun.app.domain.export

import java.io.File

/**
 * 把导出的压缩包交给系统分享面板（M15）。
 *
 * 抽成接口的理由与反馈入口相同：`Intent` / `Uri` / `FileProvider` 都是 Android 类型，
 * 而设置页的 ViewModel 需要"导出完成后调一下"这个动作。
 */
interface ArchiveShareLauncher {

    /**
     * 打开分享面板让用户把 ZIP 发出去。
     *
     * @return `true` = 面板真的被拉起了；`false` = 没能拉起（拿不到 content URI，
     *   或系统没有任何能处理 `application/zip` 的界面）。
     *
     * **必须把结果告诉调用方**（M16 修正）：导出的包只落在应用私有缓存目录里，
     * 用户没有别的入口能取到它。分享失败却报"导出完成"，等于告诉他"东西好了"却拿不到 ——
     * 而这个包随时可能被系统回收。所以失败时要能提示"文件已生成、但分享面板没打开"并给重试入口。
     *
     * 实现不得上抛异常：分享失败是正常分支（极端裁剪 ROM 上确实起不来），
     * 不是需要崩溃的编程错误。
     */
    fun share(archive: File): Boolean
}
