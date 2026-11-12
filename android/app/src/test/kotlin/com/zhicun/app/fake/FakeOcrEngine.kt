package com.zhicun.app.fake

import com.zhicun.app.domain.ocr.OcrEngine

/**
 * OCR 引擎假实现（M7）。
 *
 * 记录调用次数与最后一次传入的路径，这样"幂等跳过时不该调用引擎"可以被断言，
 * 而不用去看日志。
 */
class FakeOcrEngine(
    /** 识别结果；置空可模拟"图里没有文字"。 */
    var result: String = "识别出的文字",
) : OcrEngine {

    /** 置为非 null 时 [recognize] 抛出它（`IOException` = 可重试，其它 = 终态失败）。 */
    var failure: Throwable? = null

    /** 按图片路径返回不同结果（多图测试）；未命中时退回 [result]。 */
    val resultByPath: MutableMap<String, String> = mutableMapOf()

    var callCount: Int = 0
        private set

    var lastPath: String? = null
        private set

    override suspend fun recognize(imagePath: String): String {
        callCount++
        lastPath = imagePath
        failure?.let { throw it }
        return resultByPath[imagePath] ?: result
    }
}
