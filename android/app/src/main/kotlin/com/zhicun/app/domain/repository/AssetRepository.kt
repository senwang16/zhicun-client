package com.zhicun.app.domain.repository

import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.AssetSaveStatus

/** 附件仓储。 */
interface AssetRepository {

    suspend fun getByRecordId(recordId: String): List<Asset>

    suspend fun save(asset: Asset)

    /** 文件复制完成后更新落盘状态；只有 SAVED 才代表附件可用。 */
    suspend fun updateSaveStatus(id: String, status: AssetSaveStatus)

    /**
     * 删除附件行（2026-10-29 删除单个附件）。
     *
     * 调用方必须先删掉磁盘文件：行一旦没了，就再没有线索指向那个文件。
     * 该附件的派生内容由调用方一并清理（库里没有外键）。
     */
    suspend fun deleteById(id: String)

    /**
     * 图片或音频达到 2 个的记录 id（不含网页快照）。
     * v8 升级后的一次性重跑据此找出"旧版本只处理了一条"的记录。
     */
    suspend fun getRecordIdsWithMultipleMedia(): List<String>
}
