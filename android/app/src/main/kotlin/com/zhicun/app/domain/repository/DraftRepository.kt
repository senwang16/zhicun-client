package com.zhicun.app.domain.repository

/**
 * 「未保存草稿」的存取（M5）。
 *
 * **只存正文文本，不存附件 URI**：附件是外部 `content://`，读权限只在本次授权有效，
 * 进程重启后这些 URI 已经打不开。把死链路存进草稿，用户回来点保存会得到一堆"附件复制失败"，
 * 比没有草稿更糟。附件在草稿恢复时丢弃，并在界面上如实说明。
 *
 * 只允许存在一份草稿（新建页只有一个），因此接口没有 id 参数。
 */
interface DraftRepository {

    /** @return 上次未保存的正文；没有草稿返回 `null` */
    suspend fun loadText(): String?

    /** 写入草稿；传入空串等价于 [clear]（避免残留空文件让界面误判"有草稿"）。 */
    suspend fun saveText(text: String)

    /** 保存成功或用户明确丢弃后清空。 */
    suspend fun clear()
}
