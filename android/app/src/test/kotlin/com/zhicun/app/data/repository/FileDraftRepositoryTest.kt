package com.zhicun.app.data.repository

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 草稿文件仓储测试。
 *
 * 只在 `@Rule TemporaryFolder` 上依赖文件系统，不需要 `Context`/数据库；
 * 但异常分支会 `Log.w`，因此仍要跑在 Robolectric 上
 * （纯 JVM 下 `android.util.Log` 是未实现的桩，会抛 not mocked）。
 */
@RunWith(RobolectricTestRunner::class)
class FileDraftRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun repository(): FileDraftRepository =
        FileDraftRepository(File(tempFolder.root, "drafts/create-draft.txt"))

    @Test
    fun LoadText_noDraftFile_returnsNull() = runTest {
        assertNull(repository().loadText())
    }

    @Test
    fun SaveThenLoad_roundTripsText() = runTest {
        val repository = repository()

        repository.saveText("明天要买牛奶")
        assertEquals("明天要买牛奶", repository.loadText())
    }

    @Test
    fun SaveText_createsMissingParentDirectory() = runTest {
        val repository = repository()

        repository.saveText("x")

        assertTrue(File(tempFolder.root, "drafts").isDirectory)
    }

    @Test
    fun SaveBlankText_clearsDraftInsteadOfWritingEmpty() = runTest {
        val repository = repository()
        repository.saveText("有内容")

        repository.saveText("   ")

        assertNull("空草稿会让界面误判'有草稿'，应直接删除", repository.loadText())
    }

    @Test
    fun Clear_removesDraftFile() = runTest {
        val repository = repository()
        repository.saveText("要丢掉的")
        val draftFile = File(tempFolder.root, "drafts/create-draft.txt")
        assertTrue(draftFile.exists())

        repository.clear()

        assertFalse(draftFile.exists())
        assertNull(repository.loadText())
    }

    @Test
    fun SaveText_overwritesPreviousDraftAndLeavesNoTempFile() = runTest {
        val repository = repository()
        repository.saveText("第一版")

        repository.saveText("第二版")

        assertEquals("第二版", repository.loadText())
        val leftovers = File(tempFolder.root, "drafts").listFiles()?.filter { it.name.endsWith(".tmp") }
        assertTrue("不应残留临时文件：$leftovers", leftovers.isNullOrEmpty())
    }

    @Test
    fun LoadText_trimsSurroundingWhitespace() = runTest {
        val repository = repository()

        repository.saveText("  两边有空格  ")

        assertEquals("两边有空格", repository.loadText())
    }
}
