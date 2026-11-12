package com.zhicun.app.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 排版：基于 Material 3 默认 Typography 的局部调整。
 *
 * 不引入自定义字体文件：中文字体体积大（思源黑体全量 >8MB），
 * MVP 阶段用系统字体（`FontFamily.Default`），Android 各版本会走 MiSans/HarmonyOS Sans/
 * 思源等厂商字体，中文观感已足够；需要品牌字体时再在 M14 统一替换。
 *
 * 调整点：列表卡片标题偏重（SemiBold）以强化层级；正文行高放宽到 1.5 倍，
 * 中文方块字在 1.2 倍行高下容易糊成一片。
 */
val AppTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
        // 记录正文（详情页）用等宽可读性更好的默认字族，避免长链接排版跳动
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.Default),
    )
}

/** 展示原始内容（用户输入/OCR 结果）时统一使用的样式，保证「原文」永远是同一个观感。 */
val RawTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = 15.sp,
    lineHeight = 24.sp,
)
