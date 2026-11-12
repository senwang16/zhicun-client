package com.zhicun.app.presentation.floating

import android.content.Context
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/**
 * dp → px（M13）。
 *
 * `WindowManager.LayoutParams` 的 x/y/width/height 全是**像素**，而 Compose 侧用的是 dp。
 * 两边换算必须走同一个函数，否则"气泡 48dp、窗口 48*3=144px"这类硬编码会在
 * 不同密度设备上错位（低密度机上气泡被裁掉一半，高密度机上四周多一圈空隙）。
 */
fun Dp.px(context: Context): Int =
    (value * context.resources.displayMetrics.density).roundToInt()
