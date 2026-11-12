package com.zhicun.app.data.web

import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.fake.FakeAssetRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 网页快照落盘（C）。
 *
 * 重点钉"幂等重跑"：任务重跑（补投/手动重试）必须覆盖同一行、同一文件，
 * 不能堆积副本 —— AssetDao.insert 是 ABORT 冲突策略，任何"直接 insert 撞主键"的
 * 实现会在真机上让重试当场崩掉，这条用例就是它的回归闸。
 */
@RunWith(RobolectricTestRunner::class)
class WebSnapshotStoreImplTest {

    private val recordId = "rec-snap"

    private fun newStore() = WebSnapshotStoreImpl(
        context = RuntimeEnvironment.getApplication(),
        assetRepository = assets,
    )

    private val assets = FakeAssetRepository()

    private fun filesDir(): File = RuntimeEnvironment.getApplication().filesDir

    @Test
    fun SaveHtmlSnapshot_writesFileAndAssetRow() = runTest {
        val asset = newStore().saveHtmlSnapshot(recordId, "<html>快照</html>".toByteArray())

        assertEquals(WebSnapshotStoreImpl.htmlAssetId(recordId), asset.id)
        assertEquals("text/html", asset.mimeType)
        assertEquals(AssetSaveStatus.SAVED, asset.saveStatus)
        val file = File(asset.localPath)
        assertTrue("文件必须真实存在：$asset.localPath", file.exists())
        assertTrue(file.readText().contains("快照"))
        assertEquals("路径必须在 filesDir/assets/<recordId>/ 下", filesDir().absolutePath, file.parentFile.parentFile.parent)
        assertEquals(1, assets.stored.size)
    }

    @Test
    fun SaveOgImage_unknownMime_stillUsesStableFileName() = runTest {
        val asset = newStore().saveOgImage(recordId, byteArrayOf(1), "image/avif")

        assertEquals("真实类型以附件行的 mime 为准，不看本地文件名", "image/avif", asset.mimeType)
        assertTrue("文件名必须固定，mime 变化不能产生第二个路径", asset.localPath.endsWith("og_image.img"))
    }

    /** 核心回归：重跑覆盖同一行、同一文件，不堆积副本、不撞 ABORT 主键。 */
    @Test
    fun Rerun_overwritesSameRowAndFile() = runTest {
        val store = newStore()
        val first = store.saveHtmlSnapshot(recordId, "<html>第一版</html>".toByteArray())
        val second = store.saveHtmlSnapshot(recordId, "<html>第二版</html>".toByteArray())

        assertEquals("两次必须命中同一附件行", first.id, second.id)
        assertEquals(1, assets.stored.size)
        assertEquals(AssetSaveStatus.SAVED, second.saveStatus)
        assertEquals("文件内容应是最新一版", "<html>第二版</html>", File(second.localPath).readText())
    }

    /** mime 变了（站点换图格式）也不能产生第二个文件或第二行。 */
    @Test
    fun Rerun_withChangedMime_staysOnSameRowAndFile() = runTest {
        val store = newStore()
        val a = store.saveOgImage(recordId, byteArrayOf(1), "image/jpeg")
        val b = store.saveOgImage(recordId, byteArrayOf(1, 2), "image/png")

        assertEquals(a.id, b.id)
        assertEquals(a.localPath, b.localPath)
        assertEquals(1, assets.stored.size)
        assertEquals("image/png", assets.stored.single().mimeType)
    }
}
