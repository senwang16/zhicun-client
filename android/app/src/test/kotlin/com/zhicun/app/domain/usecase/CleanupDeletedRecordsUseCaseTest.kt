package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.Asset
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeLocalFileStore
import com.zhicun.app.fake.FakeMaintenanceRepository
import com.zhicun.app.fake.testAsset
import com.zhicun.app.fake.testRecord
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 清理已删除记录（M15）。
 *
 * 这是全 App 唯一一处**物理删除**，所以每条用例都在守同一件事：
 * 删的必须是"用户已经删过的东西"，而且**顺序不能反**。
 *
 * 顺序（先文件、后数据库行）不是洁癖：反过来一旦删文件失败，数据库里就不剩任何线索
 * 指向那些文件 —— 它们会永远占着磁盘且再也找不到。
 *
 * 由这条顺序推出第二条规矩（M16）：**只要有文件删不掉，这条记录的数据库行就不清**。
 * 所以下面同时守着"失败要如实报"和"失败后还能重试成功"两件事。
 */
class CleanupDeletedRecordsUseCaseTest {

    private lateinit var maintenance: FakeMaintenanceRepository
    private lateinit var assets: FakeAssetRepository
    private lateinit var files: FakeLocalFileStore
    private lateinit var events: MutableList<String>

    @Before
    fun setUp() {
        events = mutableListOf()
        maintenance = FakeMaintenanceRepository()
        assets = FakeAssetRepository()
        files = FakeLocalFileStore()

        // 两个假实现共用一条事件流，才能断言"文件先删、行后删"
        files.onDelete = { events += "delete:$it" }
        maintenance.onPurge = { events += "purge:$it" }
    }

    private fun useCase() = CleanupDeletedRecordsUseCase(
        maintenanceRepository = maintenance,
        assetRepository = assets,
        fileStore = files,
    )

    private suspend fun givenDeletedRecord(id: String, vararg assetList: Asset) {
        maintenance.softDeletedRecords += testRecord(id = id, isDeleted = true)
        assetList.forEach { assets.save(it) }
    }

    // ------------------------------------------------------------ 顺序

    @Test
    fun Cleanup_deletesFilesBeforeRows() = runBlocking {
        val asset = testAsset(id = "a1", recordId = "r1")
        givenDeletedRecord("r1", asset)
        files.presentPaths += asset.localPath

        useCase()()

        assertEquals(
            listOf("delete:${asset.localPath}", "purge:r1"),
            events,
        )
    }

    // ------------------------------------------------------------ 计数

    @Test
    fun Cleanup_reportsWhatWasRemoved() = runBlocking {
        val kept = testAsset(id = "a1", recordId = "r1")
        val gone = testAsset(id = "a2", recordId = "r1", mimeType = "image/jpeg")
        givenDeletedRecord("r1", kept, gone)
        files.presentPaths += kept.localPath

        val report = useCase()()

        assertEquals(1, report.recordsDeleted)
        assertEquals(1, report.assetsDeleted)
        assertEquals("文件本来就不在：不算失败", 1, report.missingAssetFiles)
        assertEquals(0, report.failedAssetFiles)
        assertTrue(report.hasWork)
        assertFalse(report.hasFailure)
    }

    @Test
    fun Cleanup_noDeletedRecords_reportsNothing() = runBlocking {
        val report = useCase()()

        assertEquals(0, report.recordsDeleted)
        assertEquals(0, report.assetsDeleted)
        assertFalse(report.hasWork)
        assertFalse(report.hasFailure)
        assertTrue("确实无事可做时 nothingDone 才为 true", report.nothingDone)
        assertTrue("没有可清理的东西时不该碰数据库", maintenance.purged.isEmpty())
    }

    // ------------------------------------------------------------ 失败分支

    /**
     * 文件删不掉（占用 / 只读）时必须记账，并且**这条记录的数据库行不能清**。
     *
     * `purgeRecord` 会连 asset 行一起删掉，删完就再没有线索指向那个残留文件 ——
     * 它永远占着磁盘，下次清理也查不到它，用户只看到"清理成功、空间没变"。
     */
    @Test
    fun Cleanup_undeletableFileKeepsRowPurgeable() = runBlocking {
        val locked = testAsset(id = "a1", recordId = "r1")
        givenDeletedRecord("r1", locked)
        files.presentPaths += locked.localPath
        files.undeletable += locked.localPath

        val report = useCase()()

        assertEquals(1, report.failedAssetFiles)
        assertEquals("文件删不掉就不能清行", 0, report.recordsDeleted)
        assertTrue("行必须留着，否则文件永久失联", maintenance.purged.isEmpty())
        assertTrue(report.hasFailure)
        assertFalse(
            "什么都没删成、还留着打不开的记录，不能说成『没有需要清理的记录』",
            report.nothingDone,
        )
    }

    /**
     * 同一记录里部分文件删得掉、一个删不掉：可删的那部分照删（不浪费这一趟），
     * 但整条记录的行仍然保留 —— 这条记录还没清干净。
     */
    @Test
    fun Cleanup_partialFileFailureStillKeepsRow() = runBlocking {
        val ok = testAsset(id = "a1", recordId = "r1")
        val locked = testAsset(id = "a2", recordId = "r1", mimeType = "image/jpeg")
        givenDeletedRecord("r1", ok, locked)
        files.presentPaths += ok.localPath
        files.presentPaths += locked.localPath
        files.undeletable += locked.localPath

        val report = useCase()()

        assertEquals(1, report.assetsDeleted)
        assertEquals(1, report.failedAssetFiles)
        assertEquals(0, report.recordsDeleted)
        assertTrue(maintenance.purged.isEmpty())
    }

    /**
     * 失败可重试：文件被释放后，下次清理必须能把这条记录正常清掉。
     * 这一条是"保留行"这个取舍的全部意义 —— 否则保留行只是把垃圾留在列表里。
     */
    @Test
    fun Cleanup_retryAfterFileReleasedPurgesRow() = runBlocking {
        val locked = testAsset(id = "a1", recordId = "r1")
        givenDeletedRecord("r1", locked)
        files.presentPaths += locked.localPath
        files.undeletable += locked.localPath

        val first = useCase()()
        assertEquals(0, first.recordsDeleted)

        // 用户关掉占用文件的进程后重试
        files.undeletable.clear()
        val second = useCase()()

        assertEquals("重试成功", 1, second.recordsDeleted)
        assertEquals(1, second.assetsDeleted)
        assertEquals(0, second.failedAssetFiles)
        assertEquals(listOf("r1"), maintenance.purged)
    }

    /**
     * 上一趟已经删掉的文件不会在重试时重复计数：
     * 它的 asset 行还在（因为行没被清），但磁盘上已经没了，归入 `missingAssetFiles`。
     */
    @Test
    fun Cleanup_retryDoesNotRecountAlreadyDeletedFiles() = runBlocking {
        val ok = testAsset(id = "a1", recordId = "r1")
        val locked = testAsset(id = "a2", recordId = "r1", mimeType = "image/jpeg")
        givenDeletedRecord("r1", ok, locked)
        files.presentPaths += ok.localPath
        files.presentPaths += locked.localPath
        files.undeletable += locked.localPath

        useCase()() // 第一趟：ok 删掉，locked 失败

        files.undeletable.clear()
        val second = useCase()()

        assertEquals(1, second.assetsDeleted)
        assertEquals("上一趟删掉的 ok 不该再算一次删除", 1, second.missingAssetFiles)
        assertEquals(1, second.recordsDeleted)
    }

    /** 护栏把记录拦下（返回 false）时，不能把它算进"已清理"。 */
    @Test
    fun Cleanup_refusedPurgeIsNotCounted() = runBlocking {
        givenDeletedRecord("r1")
        maintenance.refusedIds += "r1"

        val report = useCase()()

        assertEquals(0, report.recordsDeleted)
        assertFalse(report.hasWork)
    }

    /**
     * 重复执行是常态（用户连点、失败后重试）：第二次必须什么都不做、也不报错。
     * 假实现和真实 SQL 一样，删掉的记录不会再出现。
     */
    @Test
    fun Cleanup_isIdempotent() = runBlocking {
        val asset = testAsset(id = "a1", recordId = "r1")
        givenDeletedRecord("r1", asset)
        files.presentPaths += asset.localPath

        useCase()()
        val second = useCase()()

        assertEquals(0, second.recordsDeleted)
        assertEquals(0, second.assetsDeleted)
        assertEquals(0, second.failedAssetFiles)
    }

    /**
     * 数据库层面的异常整体上抛，由界面提示重试。
     * 不在用例里逐条吞掉，否则"库坏了"会被显示成"部分成功、可稍后再试"，
     * 用户反复重试却永远清不干净。
     */
    @Test
    fun Cleanup_repositoryFailurePropagates() = runBlocking {
        givenDeletedRecord("r1")
        maintenance.failWith = IOException("database is malformed")

        val error = runCatching { useCase()() }.exceptionOrNull()

        assertTrue(error is IOException)
    }
}
