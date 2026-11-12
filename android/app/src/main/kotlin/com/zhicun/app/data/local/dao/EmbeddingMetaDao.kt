package com.zhicun.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zhicun.app.data.local.entity.EmbeddingMetaEntity

/**
 * 向量元信息读写（开源改造 P1，DB v10）。
 *
 * 与 `derived_contents` 里的向量本体**成对写入**：向量先写、meta 后写。
 * 顺序是刻意的 —— 若进程在两次写之间被杀，留下的状态是"有向量、没身份证"，
 * 兼容性判断把缺失的 meta 当通配（见 `EmbeddingMetaEntity` 的注释），
 * 结果是"可能多召回几条"，而不是"这些向量直接消失"。
 */
@Dao
interface EmbeddingMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: EmbeddingMetaEntity)

    @Query("SELECT * FROM embedding_meta WHERE record_id = :recordId LIMIT 1")
    suspend fun getByRecordId(recordId: String): EmbeddingMetaEntity?

    @Query("SELECT COUNT(*) FROM embedding_meta")
    suspend fun count(): Int

    /**
     * 最近一次写入的向量元信息。
     *
     * 用途（开源改造 P1）：官方模式下客户端不知道网关当前用的是哪个 embedding 模型
     * （模型名由服务端部署决定、随响应回显），只能"由最近一次实际写入的向量"反推当前向量空间。
     * 自定义模式下模型名是用户填的，不需要这个反推。
     */
    @Query("SELECT * FROM embedding_meta ORDER BY embedded_at DESC LIMIT 1")
    suspend fun getLatest(): EmbeddingMetaEntity?
}