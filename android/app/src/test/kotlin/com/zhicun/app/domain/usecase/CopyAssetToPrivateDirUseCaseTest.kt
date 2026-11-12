package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.InputUri
import com.zhicun.app.fake.FakeAssetImporter
import com.zhicun.app.fake.FakeAssetRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 附件复制用例：成功态与失败态的字段约定。 */
class CopyAssetToPrivateDirUseCaseTest {

    private lateinit var importer: FakeAssetImporter
    private lateinit var assetRepository: FakeAssetRepository
    private lateinit var useCase: CopyAssetToPrivateDirUseCase

    private val source = InputUri(uri = "content://media/external/images/42", mimeType = "image/jpeg")

    @Before
    fun setUp() {
        importer = FakeAssetImporter()
        assetRepository = FakeAssetRepository()
        useCase = CopyAssetToPrivateDirUseCase(importer, assetRepository)
    }

    @Test
    fun Copy_success_persistsSavedAssetWithHashAndSize() = runTest {
        val asset = useCase(recordId = "rec-1", source = source, orderIndex = 0)

        assertEquals(AssetSaveStatus.SAVED, asset.saveStatus)
        assertEquals("rec-1", asset.recordId)
        assertEquals("image/jpeg", asset.mimeType)
        assertEquals(2048L, asset.fileSize)
        assertEquals("deadbeef", asset.fileHash)
        assertEquals(source.uri, asset.originalUri)
        assertEquals(listOf(asset), assetRepository.stored)
    }

    @Test
    fun Copy_failure_persistsFailedAssetWithOriginalUri() = runTest {
        importer.failNext = true

        val asset = useCase(recordId = "rec-2", source = source, orderIndex = 1)

        assertEquals(AssetSaveStatus.FAILED, asset.saveStatus)
        assertEquals("", asset.localPath)
        assertEquals(0L, asset.fileSize)
        assertEquals(null, asset.fileHash)
        assertEquals("失败也要落库，保留元数据", listOf(asset), assetRepository.stored)
        assertEquals("失败态也要记住顺序", 1, asset.orderIndex)
    }

    @Test
    fun Copy_sourceMimeMissing_usesMimeResolvedByImporter() = runTest {
        // 解析阶段没查到类型（mimeType = null），导入器复制时查到了真实类型：
        // Asset 必须以导入器返回的类型为准，否则一张 png 会被记成 application/octet-stream
        importer.resolvedMime = "image/png"

        val asset = useCase(recordId = "rec-3", source = source.copy(mimeType = null), orderIndex = 0)

        assertEquals("image/png", asset.mimeType)
    }

    @Test
    fun Copy_bothMimeSourcesMissing_fallsBackToOctetStream() = runTest {
        importer.resolvedMime = null

        val asset = useCase(recordId = "rec-4", source = source.copy(mimeType = null), orderIndex = 0)

        assertEquals("application/octet-stream", asset.mimeType)
    }

    @Test
    fun Copy_importerDisplayNameWins_overParsedName() = runTest {
        // 导入器此刻正读着文件，它查到的名字是权威值；解析阶段的名字只做兜底
        importer.resolvedDisplayName = "IMG_20261001.jpg"

        val asset = useCase(
            recordId = "rec-5",
            source = source.copy(displayName = "分享时的旧名字.jpg"),
            orderIndex = 2,
        )

        assertEquals("IMG_20261001.jpg", asset.fileName)
        assertEquals(2, asset.orderIndex)
    }

    @Test
    fun Copy_importerCannotReadName_fallsBackToParsedName() = runTest {
        importer.resolvedDisplayName = null

        val asset = useCase(
            recordId = "rec-6",
            source = source.copy(displayName = "截图.png"),
            orderIndex = 0,
        )

        assertEquals("截图.png", asset.fileName)
    }
}
