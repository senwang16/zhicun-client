package com.zhicun.app.data.file

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 私有目录文件操作（M15）—— 真实文件系统。
 *
 * 导出与清理对这三个动作的容错要求是"失败也不许抛"：文件被占用、目录被系统清了、
 * 只读挂载，这些在真机上都属于日常。因此这里钉的是**返回值语义**，
 * 尤其是"删一个本来就不存在的文件算成功"——清理是幂等的。
 */
@RunWith(RobolectricTestRunner::class)
class AndroidLocalFileStoreTest {

    private lateinit var store: AndroidLocalFileStore
    private lateinit var dir: File

    @Before
    fun setUp() {
        store = AndroidLocalFileStore()
        dir = File(RuntimeEnvironment.getApplication().cacheDir, "file-store-test").apply { mkdirs() }
    }

    private fun file(name: String, content: String = "x"): File =
        File(dir, name).apply { writeText(content) }

    @Test
    fun Exists_isTrueOnlyForRealFiles() {
        val present = file("a.png")
        val missing = File(dir, "nope.png")

        assertTrue(store.exists(present.path))
        assertFalse(store.exists(missing.path))
    }

    /** 空路径不能当成"当前目录"而返回 true —— 那会让清理对着一个无效路径反复尝试。 */
    @Test
    fun Exists_isFalseForBlankPath() {
        assertFalse(store.exists(""))
        assertFalse(store.exists("   "))
    }

    @Test
    fun SizeBytes_reportsLengthAndZeroWhenMissing() {
        val present = file("a.png", "12345")

        assertEquals(5L, store.sizeBytes(present.path))
        assertEquals(0L, store.sizeBytes(File(dir, "nope.png").path))
    }

    @Test
    fun Delete_removesExistingFile() {
        val present = file("a.png")

        assertTrue(store.delete(present.path))
        assertFalse(present.exists())
    }

    /** 幂等：第二次删同一个文件也必须返回成功，否则界面会把"已清理"报成"删不掉"。 */
    @Test
    fun Delete_missingFileCountsAsSuccess() {
        assertTrue(store.delete(File(dir, "never-existed.png").path))
    }

    /**
     * 空路径是 no-op：`File("")` 指向**进程工作目录**，
     * 若因某次迁移把 `localPath` 变成空串，这里绝不能去删一个碰巧是目录的路径。
     */
    @Test
    fun Delete_blankPathIsNoOp() {
        val before = dir.exists()

        assertTrue("清理是幂等的：没什么可删也算成功", store.delete(""))

        assertEquals("目录不能被误删", before, dir.exists())
    }
}
