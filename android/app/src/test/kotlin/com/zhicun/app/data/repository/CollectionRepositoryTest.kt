package com.zhicun.app.data.repository

import com.zhicun.app.data.local.AppDatabase
import com.zhicun.app.data.local.AppDatabaseFactory
import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.fake.testDerived
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 合集成员关系的 Room 集成测试（M20）。
 *
 * 用真实 SQLite 验证三件在假实现里测不出来的事：
 * 1. 建合集与写成员在**同一事务**里，成员顺序按入参落 `position`；
 * 2. 成员变化刷新合集敏感并集时，**清掉云端确认**（用户确认的是当时那份内容）；
 * 3. 快照指纹随生成更新，成员内容再变时 `isStale` 会重新变真。
 */
@RunWith(RobolectricTestRunner::class)
class CollectionRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var records: RecordRepositoryImpl
    private lateinit var collections: CollectionRepositoryImpl
    private lateinit var derived: DerivedContentRepositoryImpl

    @Before
    fun setUp() {
        database = AppDatabaseFactory.createInMemory(RuntimeEnvironment.getApplication())
        records = RecordRepositoryImpl(
            database.recordDao(),
            database.collectionDao(),
            SensitiveContentDetector(),
        )
        collections = CollectionRepositoryImpl(
            database = database,
            recordDao = database.recordDao(),
            collectionDao = database.collectionDao(),
            derivedContentDao = database.derivedContentDao(),
            sensitiveDetector = SensitiveContentDetector(),
        )
        derived = DerivedContentRepositoryImpl(database.derivedContentDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seed(
        id: String,
        createdAt: Long = 1_000L,
        sensitivity: SensitivityStatus = SensitivityStatus.CLEAR,
        isDeleted: Boolean = false,
    ): Record {
        val record = testRecord(
            id = id,
            createdAt = createdAt,
            updatedAt = createdAt,
            sensitivityStatus = sensitivity,
            isDeleted = isDeleted,
        )
        records.save(record)
        return record
    }

    private suspend fun createCollection(
        id: String = "collection-1",
        memberIds: List<String>,
        sensitivity: SensitivityStatus = SensitivityStatus.CLEAR,
        description: String = "合集说明",
    ): Record {
        val collection = testRecord(
            id = id,
            type = RecordType.COLLECTION,
            rawText = description,
            sensitivityStatus = sensitivity,
        )
        collections.create(collection, memberIds, now = 5_000L)
        return collection
    }

    @Test
    fun create_persistsCollectionAndMembersInGivenOrder() = runBlocking {
        seed("a")
        seed("b")

        createCollection(memberIds = listOf("b", "a"))

        val members = collections.getMembers("collection-1")
        assertEquals(listOf("b", "a"), members.map { it.recordId })
        assertEquals(listOf(0, 1), members.map { it.position })
        assertTrue(members.all { it.snapshotFingerprint == null })
        assertEquals(RecordType.COLLECTION, records.getById("collection-1")?.type)
        // create 只写成员关系，不复制成员内容
        assertEquals("第一行标题\n第二行正文", records.getById("a")?.rawText)
    }

    @Test
    fun membersWithRecords_keepsSoftDeletedMember() = runBlocking {
        seed("a")
        seed("b")
        createCollection(memberIds = listOf("a", "b"))
        database.recordDao().softDelete("a", updatedAt = 6_000L)

        val loaded = collections.getMembersWithRecords("collection-1")

        assertEquals(listOf("a", "b"), loaded.map { it.member.recordId })
        assertTrue("已删除来源应保留展示", loaded.first { it.member.recordId == "a" }.record.isDeleted)
    }

    @Test
    fun refreshForMemberChange_unionIncludesSuspectedAndClearsConfirmation() = runBlocking {
        seed("a", sensitivity = SensitivityStatus.CLEAR)
        seed("b", sensitivity = SensitivityStatus.CLEAR)
        createCollection(memberIds = listOf("a", "b"))
        records.confirmCloudProcessing(
            id = "collection-1",
            revision = 1,
            scope = setOf(ContentPart.RAW_TEXT),
            status = ProcessingStatus.PENDING,
            updatedAt = 5_000L,
        )

        records.elevateSensitivity("a", updatedAt = 7_000L)
        collections.refreshForMemberChange("a", now = 7_000L)

        val refreshed = records.getById("collection-1")!!
        assertEquals(SensitivityStatus.SUSPECTED, refreshed.sensitivityStatus)
        assertNull("成员内容变了，旧确认必须作废", refreshed.cloudConfirmedRevision)
        assertTrue(refreshed.cloudConfirmedScope.isEmpty())
        assertEquals(7_000L, refreshed.updatedAt)
    }

    @Test
    fun refreshForMemberChange_excludesDeletedMember() = runBlocking {
        seed("a", sensitivity = SensitivityStatus.SUSPECTED)
        seed("b", sensitivity = SensitivityStatus.CLEAR)
        createCollection(memberIds = listOf("a", "b"), sensitivity = SensitivityStatus.SUSPECTED)
        database.recordDao().softDelete("a", updatedAt = 6_000L)

        collections.refreshForMemberChange("a", now = 7_000L)

        // 已删除成员的内容不会进入重新生成的摘要，因此不再影响合集徽标
        assertEquals(SensitivityStatus.CLEAR, records.getById("collection-1")?.sensitivityStatus)
    }

    @Test
    fun gateRecord_collectionUsesLiveUnionOfActiveMembers() = runBlocking {
        seed("a", sensitivity = SensitivityStatus.CLEAR)
        seed("b", sensitivity = SensitivityStatus.CONFIRMED_SENSITIVE)
        val stored = createCollection(memberIds = listOf("a", "b"))
        assertEquals(SensitivityStatus.CLEAR, stored.sensitivityStatus)

        val gated = records.gateRecord(stored)

        assertEquals(SensitivityStatus.CONFIRMED_SENSITIVE, gated.sensitivityStatus)
    }

    /**
     * 合集说明（`raw_text`）也会进入摘要输入，必须单独扫描。
     *
     * 只按成员并集判闸会让"成员都干净、说明里写了 API Key"的合集被当成 CLEAR 放行 ——
     * 那条说明正好是要发给云端的内容。
     */
    @Test
    fun gateRecord_includesSensitiveCollectionDescription() = runBlocking {
        seed("a", sensitivity = SensitivityStatus.CLEAR)
        seed("b", sensitivity = SensitivityStatus.CLEAR)
        val stored = createCollection(
            memberIds = listOf("a", "b"),
            description = "我的密钥 sk-abcdefghijklmnopqrstuvwxyz",
        )

        val gated = records.gateRecord(stored)

        assertEquals(SensitivityStatus.SUSPECTED, gated.sensitivityStatus)
    }

    @Test
    fun refreshForMemberChange_keepsSensitiveDescription() = runBlocking {
        seed("a", sensitivity = SensitivityStatus.CLEAR)
        seed("b", sensitivity = SensitivityStatus.CLEAR)
        createCollection(
            memberIds = listOf("a", "b"),
            description = "password: supersecret",
        )

        collections.refreshForMemberChange("a", now = 7_000L)

        assertEquals(
            SensitivityStatus.SUSPECTED,
            records.getById("collection-1")?.sensitivityStatus,
        )
    }

    @Test
    fun gateRecord_nonCollectionReturnsUnchanged() = runBlocking {
        val note = seed("a", sensitivity = SensitivityStatus.CLEAR)

        assertEquals(note, records.gateRecord(note))
    }

    @Test
    fun isStale_afterMarkGeneratedAndContentChange() = runBlocking {
        seed("a")
        seed("b")
        createCollection(memberIds = listOf("a", "b"))
        assertTrue("从未生成过应算过期", collections.isStale("collection-1"))

        collections.markGenerated("collection-1", now = 8_000L)
        assertFalse(collections.isStale("collection-1"))

        records.updateRawText(
            id = "a",
            rawText = "改过的内容",
            sensitivityStatus = SensitivityStatus.CLEAR,
            expectedRevision = 1,
            updatedAt = 9_000L,
        )

        assertTrue("成员内容变了，合集应重新变为过期", collections.isStale("collection-1"))
    }

    @Test
    fun markGenerated_refreshesDeletedMembersToo() = runBlocking {
        seed("a")
        seed("b")
        createCollection(memberIds = listOf("a", "b"))
        database.recordDao().softDelete("a", updatedAt = 6_000L)

        collections.markGenerated("collection-1", now = 8_000L)

        assertFalse(collections.isStale("collection-1"))
    }

    @Test
    fun purgeMember_cascadesMembershipRow() = runBlocking {
        seed("a")
        seed("b")
        createCollection(memberIds = listOf("a", "b"))
        database.recordDao().softDelete("a", updatedAt = 6_000L)

        // 物理清理会级联删除成员关系；合集只剩 b，不会留下指向空记录的关系行
        database.maintenanceDao().purgeRecord("a")

        assertEquals(listOf("b"), collections.getMembers("collection-1").map { it.recordId })
    }

    @Test
    fun effectiveSensitivity_bodyDerivedChangeMakesCollectionStale() = runBlocking {
        seed("a")
        seed("b")
        createCollection(memberIds = listOf("a", "b"))
        collections.markGenerated("collection-1", now = 8_000L)
        assertFalse(collections.isStale("collection-1"))

        derived.upsert(
            testDerived(
                id = "a:OCR_TEXT",
                recordId = "a",
                contentType = DerivedType.OCR_TEXT,
                content = "截图里的字",
            ),
        )

        assertTrue("派生正文变化也要算过期", collections.isStale("collection-1"))
    }
}
