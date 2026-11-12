package com.zhicun.app.domain.sensitive

import com.zhicun.app.domain.model.SensitivityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 敏感信息规则检测测试。
 *
 * 重点不只是"该命中的要命中"，还包括"不该命中的别乱报"——
 * 误报会让用户对标签脱敏，真敏感时反而不当回事。
 */
class SensitiveContentDetectorTest {

    private val detector = SensitiveContentDetector()

    @Test
    fun Scan_openAiStyleKey_marksSuspected() {
        val scan = detector.scan("这是我的 key: sk-abcdefghijklmnop1234567890")

        assertTrue(scan.kinds.contains(SensitiveKind.API_KEY))
        assertEquals(SensitivityStatus.SUSPECTED, detector.toSensitivityStatus(scan))
    }

    @Test
    fun Scan_pemPrivateKey_marksSuspected() {
        val scan = detector.scan("-----BEGIN RSA PRIVATE KEY-----\nMIIEow...")

        assertTrue(scan.kinds.contains(SensitiveKind.PRIVATE_KEY))
    }

    @Test
    fun Scan_passwordAssignment_marksSuspected() {
        val scan = detector.scan("数据库配置 password: hunter2hunter2")

        assertTrue(scan.kinds.contains(SensitiveKind.PASSWORD_LIKE))
    }

    @Test
    fun Scan_chineseIdCard_marksSuspected() {
        val scan = detector.scan("身份证 110105199003071234 请勿外传")

        assertTrue(scan.kinds.contains(SensitiveKind.CN_ID_CARD))
    }

    @Test
    fun Scan_mobileAndEmail_marksSuspected() {
        val scan = detector.scan("联系 13800138000 或 mail@example.com")

        assertTrue(scan.kinds.contains(SensitiveKind.CN_MOBILE))
        assertTrue(scan.kinds.contains(SensitiveKind.EMAIL))
    }

    @Test
    fun Scan_ordinaryNote_isClear() {
        val scan = detector.scan("周末买牛奶两盒，顺便取快递")

        assertFalse(scan.isSuspected)
        assertEquals(SensitivityStatus.CLEAR, detector.toSensitivityStatus(scan))
    }

    @Test
    fun Scan_longDigitStringWithoutPhonePrefix_isNotMobile() {
        // 订单号/时间戳这类长数字不该被当成手机号
        val scan = detector.scan("订单号 20261021123456789012")

        assertFalse(scan.kinds.contains(SensitiveKind.CN_MOBILE))
    }

    @Test
    fun Scan_blankText_isClear() {
        assertFalse(detector.scan("").isSuspected)
        assertFalse(detector.scan("   \n ").isSuspected)
        assertFalse(detector.scan(null).isSuspected)
    }

    @Test
    fun Scan_multipleKinds_reportsAll() {
        val scan = detector.scan("A sk-abcdefghijklmnop1234567890 B 13800138000")

        assertEquals(setOf(SensitiveKind.API_KEY, SensitiveKind.CN_MOBILE), scan.kinds)
    }

    @Test
    fun Summary_containsNoOriginalText() {
        // 日志里只能出现规则名，绝不能带出命中原文（否则等于把敏感内容写进 logcat）
        val scan = detector.scan("key sk-abcdefghijklmnop1234567890")

        assertFalse(scan.summary().contains("sk-"))
        assertTrue(scan.summary().contains("API_KEY"))
    }

    /**
     * 银行卡（R-2 附带发现：隐私说明承诺了"银行卡"，此前没有任何规则）。
     *
     * 光看形状会把订单号、流水号一起报成敏感，所以加了 Luhn 自校验：
     * 有效卡号命中，形状相同但校验位不对的数字不命中。
     */
    @Test
    fun Scan_bankCardWithValidChecksum_marksSuspected() {
        assertTrue(detector.scan("卡号 4111 1111 1111 1111 转账").kinds.contains(SensitiveKind.BANK_CARD))
        assertTrue(detector.scan("5555555555554444").kinds.contains(SensitiveKind.BANK_CARD))
    }

    @Test
    fun Scan_digitStringFailingLuhn_isNotBankCard() {
        assertFalse(detector.scan("流水号 1234567890123456").kinds.contains(SensitiveKind.BANK_CARD))
    }

    @Test
    fun Scan_longOrderNumber_isNotBankCard() {
        assertFalse(detector.scan("订单号 20261021123456789012").kinds.contains(SensitiveKind.BANK_CARD))
    }
}
