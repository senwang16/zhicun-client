package com.zhicun.app.domain.ocr

/**
 * 文字识别引擎（M7）。
 *
 * 抽成接口的理由与 M4/M5 的 `MimeTypeLookup`、`MimeTypeResolver` 一致：
 * ML Kit 的 `TextRecognizer` 依赖真实图片解码与 GMS 运行时，在 JVM 单测里跑不起来。
 * 上层（`OcrWorker`）只应该依赖"给个图片路径、还我一段文字"这个契约，
 * 这样"识别结果怎么写库、失败怎么分类"就能被穷举测试。
 *
 * 实现方必须遵守的约定：
 * - 路径读不出来（文件被删、权限丢失）抛 [java.io.IOException] —— 上层会据此判"可重试"；
 * - 图片能读但识别不出文字，返回**空串**而不是抛异常 —— 那是正常结果，不是错误；
 * - 其它失败（引擎初始化失败、图片解码失败）可以抛任意异常，上层会判"终态失败"。
 */
interface OcrEngine {

    /**
     * 识别图片中的文字。
     *
     * @param imagePath App 私有目录下的图片绝对路径（`Asset.localPath`）
     * @return 识别出的文本；多行以 `\n` 分隔，无文字时为空串
     */
    suspend fun recognize(imagePath: String): String
}
