package com.zhicun.app.fake

import com.zhicun.app.domain.maintenance.LocalFileStore

/**
 * 私有目录文件的假实现（M15）。
 *
 * 真机上很难制造"文件突然不存在""文件删不掉"这两件事，但它们**必然**会发生
 * （用户清过缓存、换机只搬了数据库、文件被别的进程占着）。导出与清理的健壮性
 * 全压在这两个分支上，所以必须能在纯 JVM 上把它们摆出来。
 *
 * @param present 初始"磁盘上存在"的绝对路径集合
 */
class FakeLocalFileStore(
    present: Collection<String> = emptyList(),
) : LocalFileStore {

    /** 当前"磁盘上存在"的路径。 */
    val presentPaths: MutableSet<String> = present.toMutableSet()

    /** 放在这里面的路径删除会失败（模拟文件被占用 / 只读挂载）。 */
    val undeletable: MutableSet<String> = mutableSetOf()

    /** 每次成功删除的路径，按调用顺序。 */
    val deleted: MutableList<String> = mutableListOf()

    /** 删除回调：用来验证"先删文件、再删数据库行"的顺序。 */
    var onDelete: ((String) -> Unit)? = null

    override fun exists(path: String): Boolean = path in presentPaths

    override fun sizeBytes(path: String): Long =
        if (path in presentPaths) DEFAULT_SIZE_BYTES else 0L

    override fun delete(path: String): Boolean {
        onDelete?.invoke(path)
        return when {
            path in undeletable -> false
            // 本来就不在也算成功：清理是幂等的，第二次点不该报错
            path !in presentPaths -> true
            else -> {
                presentPaths -= path
                deleted += path
                true
            }
        }
    }

    private companion object {
        const val DEFAULT_SIZE_BYTES = 1024L
    }
}
