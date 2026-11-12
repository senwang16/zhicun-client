package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.repository.DerivedContentRepository
import javax.inject.Inject

/**
 * 取一条记录的全部派生内容（OCR 文字、网页正文…）。
 *
 * 取不到不算错误：派生内容本来就是"可能还没有"的东西（尚未处理 / 处理失败），
 * 空列表就是它的正常形态。真正的读取异常不吞掉 —— 详情页要如实告诉用户
 * "读取失败"，而不是把异常伪装成"没有识别结果"。
 */
class GetDerivedContentUseCase @Inject constructor(
    private val derivedContentRepository: DerivedContentRepository,
) {
    suspend operator fun invoke(recordId: String): List<DerivedContent> {
        if (recordId.isBlank()) return emptyList()
        return derivedContentRepository.getByRecordId(recordId)
    }
}
