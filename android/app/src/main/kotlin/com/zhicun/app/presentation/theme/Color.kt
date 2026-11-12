package com.zhicun.app.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * 智存品牌色板。
 *
 * 主色取深青绿（与启动器图标背景 `#0F766E` 同源）：语义上是"收进来、存得住"，
 * 比常见的高饱和蓝紫更贴近"资料柜"的气质，也不会和系统默认色撞。
 *
 * 命名用「色相 + 明度序号」，不按用途命名 —— 同一个色值在亮/暗两套 scheme 里
 * 承担的角色不同，按用途命名会导致暗色方案里出现 `LightPrimary` 这种自相矛盾的名字。
 */

// 主色相：青绿
val Teal900 = Color(0xFF04322F)
val Teal800 = Color(0xFF0B4F4A)
val Teal700 = Color(0xFF0F766E)
val Teal500 = Color(0xFF14A398)
val Teal300 = Color(0xFF6FD3C8)
val Teal200 = Color(0xFF9FE3DB)
val Teal50 = Color(0xFFE6F7F5)

// 次色相：石灰蓝（用于次要信息、分隔感）
val Slate900 = Color(0xFF1B2526)
val Slate700 = Color(0xFF3D4A4B)
val Slate500 = Color(0xFF5B6A6B)
val Slate300 = Color(0xFFA9B6B6)
val Slate200 = Color(0xFFCBD5D4)
val Slate100 = Color(0xFFE4EAE9)

// 中性面（Neutral50 即页面底 #F6F8F7，卡片保持纯白，让白卡片浮在浅灰底上）
val Neutral0 = Color(0xFFFFFFFF)
val Neutral50 = Color(0xFFF6F8F7)
val Neutral100 = Color(0xFFF1F4F3)
val Neutral900 = Color(0xFF101413)
val Neutral800 = Color(0xFF1A1F1E)
val Neutral700 = Color(0xFF242B2A)

/**
 * 记录类型色（2026-10 UI 改版）：卡片左上角 32dp 类型图标的底色与前景色。
 *
 * 白底资料柜原则下品牌青绿只做焦点色，类型辨识交给这组低饱和"软底 + 深前景"：
 * 网页蓝 / 文字青绿 / 语音琥珀 / 图片紫 / 文件红。仅用于亮色模式，暗色模式回落中性色。
 */
val TypeWeb = Color(0xFF2563EB)
val TypeWebContainer = Color(0xFFEAF1FE)
val TypeVoiceContainer = Color(0xFFFFF2DE)
val TypeImage = Color(0xFF7C3AED)
val TypeImageContainer = Color(0xFFF1ECFE)
val TypeFile = Color(0xFFC0352C)
val TypeFileContainer = Color(0xFFFDE9E7)

// 语义色（红涨绿跌等金融约定的例外：这里是状态色，不是行情色）
val StatusAmber = Color(0xFFB26A00)
val StatusAmberContainer = Color(0xFFFFE7C2)
val StatusGreen = Color(0xFF1F7A46)
val StatusGreenContainer = Color(0xFFCDEFD9)
val StatusRed = Color(0xFFB3261E)
val StatusRedContainer = Color(0xFFF9DEDC)

// 详情页 AI 摘要区块的边框（2026-10 改版：软底 Teal50 + 该边框，见设计稿 02）
val AiBlockBorder = Color(0xFFBCE5DE)

// 暗色下 error 需抬高明度才不闷（亮色的 #B3261E 在深底上对比过低）
val ErrorDark = Color(0xFFFFB4AB)
val ErrorContainerDark = Color(0xFF8C1D18)

// 状态色的暗色版本：深底上必须提亮，否则绿/琥珀在 #101413 上几乎不可读
val StatusGreenDark = Color(0xFF7BD8A0)
val StatusAmberDark = Color(0xFFFFC46B)
