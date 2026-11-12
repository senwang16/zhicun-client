package com.zhicun.app.data.worker

import com.zhicun.app.domain.model.DerivedType

/**
 * 派生内容主键的构造规则。
 *
 * 用确定性 id（`recordId:类型` / `recordId:类型:assetId`）而不是随机 UUID：同一记录同一类型
 * 重新处理时，主键不变，`REPLACE` 就是"更新这一行"；用随机 id 会走"唯一索引冲突 → 删旧行
 * → 插新行"，主键无谓地漂移，排查时也看不出这两次结果其实是同一条记录的同一种派生。
 */
internal object DerivedContentKeys {

    /**
     * @param assetId 附件级结果的归属附件；`null` = 记录级（摘要、网页正文等）
     */
    fun idFor(recordId: String, type: DerivedType, assetId: String? = null): String =
        if (assetId == null) "$recordId:$type" else "$recordId:$type:$assetId"
}
