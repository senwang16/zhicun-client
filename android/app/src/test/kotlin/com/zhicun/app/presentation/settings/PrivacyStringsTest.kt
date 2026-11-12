package com.zhicun.app.presentation.settings

import android.content.Context
import com.zhicun.app.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 隐私说明的事实性守卫（2026-10-23 复核 F-7）。
 *
 * 不比对整段文案（那会让每次改措辞都要改测试），只钉两件曾经错在**事实**上的事：
 * 1. 文本里不能有 Markdown 标记 —— 设置页用纯 `Text` 渲染，星号会原样显示给用户；
 * 2. 敏感提示要覆盖检测器真正支持的类别 —— 漏掉"银行卡号"会让用户以为它不会被识别。
 */
@RunWith(RobolectricTestRunner::class)
class PrivacyStringsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** 隐私说明是纯文本渲染：任何 `**` 都会变成用户眼里的两个星号。 */
    @Test
    fun PrivacyDetail_hasNoMarkdownMarkers() {
        val text = context.getString(R.string.settings_privacy_detail)

        assertFalse("隐私说明不能残留 Markdown 标记：$text", text.contains("**"))
    }

    /** 敏感检测器支持银行卡（Luhn），提示文案必须跟上，否则用户会误判。 */
    @Test
    fun SensitiveHint_coversBankCard() {
        val text = context.getString(R.string.detail_ai_sensitive_hint)

        assertTrue("敏感提示要写明银行卡号：$text", text.contains("银行卡号"))
    }

    /**
     * 抓取与上传是两次独立授权（F-5）：隐私说明要能看出这一点。
     *
     * 整改前这里的第 4 点写着"确认不受上面的开关影响"——那个确认在加了云端通路预检后
     * 已经要求开关打开，文案与实现相反。
     */
    @Test
    fun PrivacyDetail_documentsFetchAndUploadSeparately() {
        val text = context.getString(R.string.settings_privacy_detail)

        assertTrue("隐私说明要提到单独的抓取确认：$text", text.contains("允许抓取"))
        assertTrue("隐私说明要说明上传确认需要开关与网关：$text", text.contains("网关可用"))
    }

    /**
     * 设备维度统计的披露（2026-11-07 审核 P2-4）。
     *
     * 网关会保存 `SHA-256(salt|X-Client-Id)` 的前 16 位 hex 一年。实现上不可反查，
     * 但"隐私最小化"不等于"可以不披露"——说明里必须让用户知道有这个标识。
     */
    @Test
    fun PrivacyDetail_disclosesDeviceIdentifierHashing() {
        val text = context.getString(R.string.settings_privacy_detail)

        assertTrue("隐私说明要披露设备标识随请求发送：$text", text.contains("设备标识"))
        assertTrue("隐私说明要说明只保存加盐哈希：$text", text.contains("哈希"))
    }
}
