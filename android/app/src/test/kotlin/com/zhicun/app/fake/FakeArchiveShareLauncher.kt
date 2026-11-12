package com.zhicun.app.fake

import com.zhicun.app.domain.export.ArchiveShareLauncher
import java.io.File

/**
 * 分享面板的假实现（M15；M16 起能模拟"面板起不来"）。
 *
 * 记下被分享的文件，测试据此回答一个问题：**导出完成后有没有真的交给系统**。
 * 用户拿不到包的"导出成功"是假的，这条链路必须能被断言。
 */
class FakeArchiveShareLauncher : ArchiveShareLauncher {

    /** 所有尝试分享过的文件，含失败的（失败也会留下一条记录，便于断言"试过了"）。 */
    val shared: MutableList<File> = mutableListOf()

    /** 置 false 时模拟"分享面板打不开"（极端裁剪 ROM / 没有能处理 zip 的应用）。 */
    var succeeds: Boolean = true

    override fun share(archive: File): Boolean {
        shared += archive
        return succeeds
    }
}
