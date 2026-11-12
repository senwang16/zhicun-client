package com.zhicun.app.domain.maintenance

/**
 * 私有目录里文件的最小操作集（M15）。
 *
 * 只有三个动作，因为导出与清理只需要这三个：判断还在不在、看多大、删掉。
 * 抽成接口是为了让"导出时附件丢了""清理时删文件失败"这些分支能在纯 JVM 上测 ——
 * 真机上很难制造"文件突然不存在"的场景，但它是必然会发生的事（用户清过缓存、
 * 换机迁移只搬了数据库、系统回收了缓存目录）。
 */
interface LocalFileStore {

    fun exists(path: String): Boolean

    fun sizeBytes(path: String): Long

    /** @return true 表示文件已不存在（本来就没有也算成功，清理是幂等的） */
    fun delete(path: String): Boolean
}
