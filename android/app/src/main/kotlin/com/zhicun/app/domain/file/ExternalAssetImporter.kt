package com.zhicun.app.domain.file

import com.zhicun.app.domain.model.InputUri

/**
 * 把外部 URI 的内容复制进 App 私有目录（domain 契约，data 层实现）。
 *
 * 为什么单独抽接口：复制要碰 `ContentResolver`、`filesDir`、`InputStream`，
 * 全是 Android 的东西；而"复制成功后要建 Asset 记录、失败要标注失败"是业务规则。
 * 把 Android 部分挡在接口后面，`CopyAssetToPrivateDirUseCase` 才能在纯 JVM 下测。
 */
interface ExternalAssetImporter {

    /**
     * 复制 [source] 指向的内容到 `filesDir/assets/<recordId>/` 下。
     *
     * @return 落盘后的绝对路径、实际字节数与 SHA-256
     * @throws AssetTooLargeException 超过单文件体积上限（PRD §11：拒绝并解释，不崩溃）
     * @throws AssetImportException 打不开输入流、读取中断、目标目录不可写等
     */
    suspend fun import(recordId: String, source: InputUri): ImportedAsset
}

/** 复制结果。`localPath` 必须是绝对路径（AGENTS.md §3 禁止相对路径）。 */
data class ImportedAsset(
    val localPath: String,
    /**
     * 落盘时确定的具体 MIME 类型（非通配）。
     *
     * 为什么由导入器返回而不是让用例沿用 [InputUri.mimeType]：解析阶段可能拿不到类型
     * （provider 不响应 `getType`、或调用方没传 `ContentResolver`），而导入器此刻**正在读文件**，
     * 它有最后一次机会查真实类型。让保存 Asset 时以它为准，可以避免把一张 png 存成
     * `application/octet-stream`（M4 审核发现的问题之一）。
     */
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String?,
    /**
     * 复制时查到的原始文件名（`OpenableColumns.DISPLAY_NAME`），拿不到为 `null`。
     *
     * 导入器本来就要读它来决定磁盘文件名（清洗前的原貌只在这里拿得到），
     * 顺手带上来让 Asset 能存一份给界面展示 —— 磁盘名是清洗过的，不是用户看到的名字。
     */
    val displayName: String? = null,
)

/** 附件复制失败（可预期的 IO / 权限问题）。 */
open class AssetImportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 附件超过体积上限。单独成类，便于上层给出不同的提示文案。 */
class AssetTooLargeException(
    val limitBytes: Long,
    message: String,
) : AssetImportException(message)
