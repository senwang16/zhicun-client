package com.zhicun.app.data.settings

/**
 * 网关 access token 的加解密契约（2026-10-22 审核 P1 的修复件）。
 *
 * 存储格式由实现定义，但必须带**可识别前缀**：读侧据此区分
 * "本实现加密过的串"与"历史明文"（后者触发一次性迁移重写）。
 * 前缀识别本身只查格式、不做任何加解密或 IO，保证迁移判断在
 * 测试与真机上行为一致。
 *
 * 实现边界：
 * - [KeystoreTokenCipher] 用 AndroidKeyStore + AES/GCM（真机/模拟器）；
 * - 测试注入假实现（逆序或异或），不碰真 Keystore（JVM 无该 Provider）。
 */
interface TokenCipher {

    /** 明文 -> 带前缀的可存储串。同一明文两次加密结果不同（随机 IV），属预期。 */
    fun encrypt(plain: String): String

    /**
     * 存储串 -> 明文。
     * - 不是本实现的格式（历史明文）：返回 `null`，由调用方走迁移分支；
     * - 是本格式但解密失败（密钥被清除 / 数据被篡改）：返回 `null`，调用方按"未配置"降级，
     *   用户在设置页重新输入即可恢复——**不得**因解密失败把明文兜底读出来。
     */
    fun decrypt(stored: String): String?

    /** 是否为本实现的存储格式（只查前缀，无副作用）。 */
    fun isEncrypted(stored: String): Boolean

    companion object {
        /** 存储格式前缀；`1` 是格式版本，将来换算法时升 `enc2:` 并行保留两种读法。 */
        const val PREFIX = "enc1:"
    }
}
