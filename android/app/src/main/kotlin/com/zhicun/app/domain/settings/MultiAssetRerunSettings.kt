package com.zhicun.app.domain.settings

/**
 * "多附件老记录已补跑过一次" 的一次性标记（DB v8 升级，D1 决策）。
 *
 * 为什么不用 `PRAGMA user_version` / Room 迁移里做：迁移必须只改数据、几毫秒内完成，
 * 而补跑要为每条记录重排后台任务（WorkManager、可能跨几十条记录）。放在启动流程里
 * 用独立 flag 控制，进程被杀最多重跑一遍 —— 逐附件结果是确定性 id，重跑只是覆盖，
 * 不会产生重复行。
 */
interface MultiAssetRerunSettings {

    /** 已经补跑过（或本次运行完成了补跑）即为 true，之后冷启动不再扫描。 */
    val completed: Boolean

    fun markCompleted()
}
