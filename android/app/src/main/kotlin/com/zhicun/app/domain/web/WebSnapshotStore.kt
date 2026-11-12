package com.zhicun.app.domain.web

import com.zhicun.app.domain.model.Asset

/**
 * 网页快照的落盘 + 落附件行（C：HTML 快照离线浏览）。
 *
 * 快照进 **Asset 表** 而不是新的派生内容类型：虽然它由后台任务生成（像派生内容），
 * 但它**不可重建**——网页会变、会下线，快照的价值恰恰是"存下当时的样子"，
 * 生命周期应跟随记录本身。进 Asset 表让 M15 的导出与清理零改动顺路接管
 * （`.html` 扩展名映射已存在），不需要另开一条"派生文件清理"路径。
 *
 * ## 幂等
 * 附件 id 是确定性的（`<recordId>:snapshot` / `<recordId>:og_image`），任务重跑时
 * 覆盖同一行、同一文件，而不是堆积副本。覆盖整行依赖
 * `AssetDao.insert` 的 `ON CONFLICT REPLACE`（同 id 更新 mime/size/hash），
 * 文件名固定不随 mime 变化，路径永远稳定。
 *
 * @throws java.io.IOException 文件写入失败（磁盘满等）——快照是附加能力，
 *   调用方（Worker）应降级处理而不是让主任务失败
 */
interface WebSnapshotStore {

    /**
     * 保存 HTML 快照。字节是"可直接离线渲染"的形态（不再是逐字节原文）：
     * 调用方须先用 [SnapshotImageInliner] 把正文图片内嵌为 data URI、统一 UTF-8，
     * 否则禁网的查看页里所有远程图片都会裂图。
     */
    suspend fun saveHtmlSnapshot(recordId: String, htmlBytes: ByteArray): Asset

    /** 保存页面配图（og:image 已下载好的字节）。 */
    suspend fun saveOgImage(recordId: String, imageBytes: ByteArray, mimeType: String): Asset
}
