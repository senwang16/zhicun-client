package com.zhicun.app.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import javax.inject.Inject

/**
 * [TokenCipher] 的 AndroidKeyStore 实现：AES-256/GCM，密钥不出安全硬件（T-010）。
 *
 * ## 为什么不用 androidx.security:security-crypto
 * 架构 §6.6 字面要求 EncryptedSharedPreferences，但该库已被 AndroidX 宣布停止维护
 * （1.1.0-alpha07 后弃用，官方建议自管）。EncryptedSharedPreferences 的本质就是
 * AndroidKeyStore AES/GCM + 明文表头，本实现手写同一套机制：**满足"Keystore 保护的
 * 加密存储"这一架构意图，同时零新依赖、不引弃用组件**。2026-10-22 审核 P1 的修复，
 * 偏差记录见 DEVELOPMENT_PLAN.md M9 偏差 D-2 的关闭说明。
 *
 * ## 失败语义（全部降级为"未配置"，绝不回退明文）
 * - 密钥生成 / Cipher 初始化失败（极老设备或 Keystore 损坏）：抛给调用方视为未配置；
 * - 解密失败（密钥被系统清除、密文被改）：[decrypt] 返回 null，用户重新输入 token 即可。
 *
 * 密文格式：`enc1:<base64(iv)>:<base64(ciphertext)>`。GCM 每次加密取随机 IV，
 * 因此同一 token 两次存储的密文不同——比较 token 必须先解密，不能比密文。
 */
class KeystoreTokenCipher @Inject constructor() : TokenCipher {

    private val keyAlias: String = DEFAULT_KEY_ALIAS

    override fun isEncrypted(stored: String): Boolean = stored.startsWith(TokenCipher.PREFIX)

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return TokenCipher.PREFIX +
            Base64.encodeToString(iv, Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(stored: String): String? {
        if (!isEncrypted(stored)) return null
        return try {
            val body = stored.removePrefix(TokenCipher.PREFIX)
            val parts = body.split(':')
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            // 密钥被清除/密文损坏等：按"未配置"降级。不打堆栈，一行原因即可。
            Log.w(TAG, "token 解密失败，按未配置处理：${e.javaClass.simpleName}")
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "KeystoreTokenCipher"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "zhicun_gateway_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
