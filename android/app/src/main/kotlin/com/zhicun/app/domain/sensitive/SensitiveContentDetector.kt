package com.zhicun.app.domain.sensitive

import com.zhicun.app.domain.model.SensitivityStatus
import javax.inject.Inject

/**
 * 本地敏感信息规则检测（ARCHITECTURE.md §6.5：第一阶段只用本地正则，不调云端）。
 *
 * 设计取舍：
 * - **纯 Kotlin**，无 Android 依赖，可直接单测；正则全部预编译，避免每次扫描重复编译。
 * - 只回答"像不像"，不回答"是不是"：命中的含义是"疑似"，最终是否外发由用户决定（PRD §6.4）。
 * - 本类**不知道自己扫的是哪一段**，扫描范围由调用方决定。因此调用方必须把"所有会离开设备或
 *   显示给别人的文字"一起交进来：保存链路是「正文 + 分享标题」
 *   （见 `SaveRecordUseCase.buildRecord`），派生内容链路是 OCR / 转写 / 网页正文
 *   （见 `DerivedSensitivityGuard`）。2026-10-24 全项目审查发现标题曾漏扫，故在此写明契约。
 *
 * 明确的能力边界：规则检测**不保证覆盖所有敏感内容**，也不做上下文语义判断
 * （例如手机号出现在通讯录里是正常内容，出现在合同里可能敏感）。
 */
class SensitiveContentDetector @Inject constructor() {

    /**
     * 扫描一段文本。
     *
     * @return 命中的规则种类；空集合表示"未发现明显风险"（不等于绝对安全，文案需如实表达）。
     */
    fun scan(text: String?): SensitiveScan {
        if (text.isNullOrBlank()) return SensitiveScan(emptySet())

        val kinds = SensitiveKind.entries.filterTo(LinkedHashSet()) { kind ->
            // 有些规则光看形状不够（16 位数字满地都是），要再过一道校验（[SensitiveKind.validate]）。
            // `any` 仍然是短路的：找到第一个通过校验的命中就停。
            kind.pattern.findAll(text).any { match -> kind.validate(match.value) }
        }
        return SensitiveScan(kinds)
    }

    /** 把扫描结果映射成记录字段的取值。 */
    fun toSensitivityStatus(scan: SensitiveScan): SensitivityStatus =
        if (scan.isSuspected) SensitivityStatus.SUSPECTED else SensitivityStatus.CLEAR
}

/**
 * Luhn 校验（银行卡号的自校验位）。
 *
 * 为什么必须加：`[0-9]{16}` 这种形状的规则会把订单号、时间戳串、物流单号全部判成"疑似银行卡"。
 * 误报的代价不是"多点一次确认"，而是用户从此不再相信这个标记 —— 真敏感的内容也会被无视。
 * Luhn 让 16~19 位数字的误报率降到约 1/10。
 */
private fun luhnValid(raw: String): Boolean {
    val digits = raw.filter { it.isDigit() }
    if (digits.length !in 13..19) return false
    var sum = 0
    var doubling = false
    for (index in digits.indices.reversed()) {
        var digit = digits[index] - '0'
        if (doubling) {
            digit *= 2
            if (digit > 9) digit -= 9
        }
        sum += digit
        doubling = !doubling
    }
    return sum % 10 == 0
}

/**
 * 检测结果。
 *
 * @param kinds 命中的规则；用 `Set` 是为了让"同一类命中多次"不重复计入
 */
data class SensitiveScan(val kinds: Set<SensitiveKind>) {
    val isSuspected: Boolean get() = kinds.isNotEmpty()

    /** 供日志/排查用的一行摘要（**不含命中原文**，避免把敏感内容写进日志）。 */
    fun summary(): String = if (kinds.isEmpty()) "none" else kinds.joinToString(",") { it.name }
}

/**
 * 规则集合。每条的 [pattern] 都写成"尽量窄"的形式，宁可漏报也不要大面积误报——
 * 误报会让用户对"疑似敏感"标签脱敏，之后真敏感的内容也不当回事了。
 *
 * @param validate 形状命中之后的二次校验（默认全通过）。用于"光看形状误报太多"的规则。
 */
enum class SensitiveKind(val pattern: Regex, val validate: (String) -> Boolean = { true }) {

    /** PEM 私钥块（最危险的一类，必然命中）。 */
    PRIVATE_KEY(
        Regex("-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"),
    ),

    /** 常见大模型/平台 API Key。 */
    API_KEY(
        Regex(
            """(?:sk-[A-Za-z0-9_\-]{16,})""" +
                """|(?:AKIA[0-9A-Z]{16})""" +
                """|(?:gh[pousr]_[A-Za-z0-9]{20,})""" +
                """|(?:AIza[0-9A-Za-z_\-]{35})""",
        ),
    ),

    /** 形如 password: xxx / token=xxx 的赋值语句。 */
    PASSWORD_LIKE(
        Regex("""(?i)\b(?:password|passwd|pwd|secret|access[_-]?token|api[_-]?key)\b\s*[:=：]\s*\S{6,}"""),
    ),

    /** 中国大陆 18 位身份证号（末位可为 X）。 */
    CN_ID_CARD(
        Regex("""(?<!\d)[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)"""),
    ),

    /** 中国大陆手机号。 */
    CN_MOBILE(
        Regex("""(?<!\d)1[3-9]\d{9}(?!\d)"""),
    ),

    /** 邮箱地址。 */
    EMAIL(
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""),
    ),

    /**
     * 银行卡号（2026-10-23 审查 R-2 附带发现：隐私说明承诺检测银行卡，此前没有对应规则）。
     *
     * 形状只收 16~19 位（含空格/连字符分组），再过 [luhnValid]：
     * 国内常见的 19 位借记卡、16 位信用卡都在范围内，而订单号这类纯数字串基本过不了自校验。
     */
    BANK_CARD(
        Regex("""(?<![\d\-])[1-9]\d{3}(?:[ \-]?\d{4}){3}(?:[ \-]?\d{1,3})?(?![\d\-])"""),
        ::luhnValid,
    ),
}
