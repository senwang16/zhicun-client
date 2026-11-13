package com.zhicun.app.presentation.inbox

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.OutDateStyle
import com.kizitonwose.calendar.core.daysOfWeek
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import com.zhicun.app.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 日历筛选弹层（M19，ModalBottomSheet + 月历）。
 *
 * ## 防锁定边界（DEVELOPMENT_PLAN M19 的裁决条件）
 * 这是**全工程唯一**允许 import `com.kizitonwose.calendar` 的文件：
 * 库只承担"月网格 + 切月手势"，选中态与圆点数据全在调用方
 * （[dayCounts] / [selectedDate] 都是纯 JDK 类型）。
 * 日后若弃用该库，重写本文件即可，其余代码零改动。
 *
 * 交互（与电商订单列表、日记类 App 同范式）：
 * 有记录的日期打圆点；点某天 = 筛选到那天（已选中再点同一天 = 清除）；
 * 筛选生效期间底部出现"清除日期筛选"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxCalendarSheet(
    dayCounts: Map<LocalDate, Int>,
    selectedDate: LocalDate?,
    onDismiss: () -> Unit,
    onDateSelected: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.testTag(InboxTestTags.CALENDAR_SHEET),
    ) {
        val today = remember { LocalDate.now() }
        val currentMonth = remember { YearMonth.now() }
        // 往前翻 12 个月足够覆盖"翻旧账"的常见深度；未来月没有记录，不提供
        val calendarState = rememberCalendarState(
            startMonth = currentMonth.minusMonths(START_MONTHS_BACK),
            endMonth = currentMonth,
            firstVisibleMonth = currentMonth,
            outDateStyle = OutDateStyle.EndOfRow,
        )
        val scope = rememberCoroutineScope()
        // 读取 firstVisibleMonth 是状态读取：切月后标题与箭头边界自动重组
        val visibleMonth = calendarState.firstVisibleMonth.yearMonth
        // locale 经 LocalConfiguration 读取（可观察）：语言/地区变更时星期行与首日正确重组，
        // 也避免 NonObservableLocale（在 composable 里裸读 Locale.getDefault 是 lint 错误）
        val locale = LocalConfiguration.current.locales[0]
        val monthPattern = stringResource(R.string.inbox_calendar_month_pattern)
        val monthFormatter = remember(locale, monthPattern) {
            DateTimeFormatter.ofPattern(monthPattern, locale)
        }
        val firstDayOfWeek = remember(locale) { firstDayOfWeekFromLocale(locale) }
        val weekdayNames = remember(locale, firstDayOfWeek) {
            daysOfWeek(firstDayOfWeek).map { it.getDisplayName(TextStyle.NARROW, locale) }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.inbox_calendar_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            calendarState.animateScrollToMonth(visibleMonth.minusMonths(1))
                        }
                    },
                    // 首月之后才可往前翻；到头了置灰而不是隐藏——箭头消失会让用户
                    // 以为月份列表"到此为止"，和"翻不动了"是两种理解
                    enabled = visibleMonth > calendarState.startMonth,
                    modifier = Modifier.testTag(InboxTestTags.CALENDAR_PREV_MONTH),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_left),
                        contentDescription = stringResource(R.string.inbox_calendar_previous_month),
                    )
                }
                Text(
                    text = visibleMonth.format(monthFormatter),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            calendarState.animateScrollToMonth(visibleMonth.plusMonths(1))
                        }
                    },
                    // 未来月不可翻：那天不可能有记录
                    enabled = visibleMonth < calendarState.endMonth,
                    modifier = Modifier.testTag(InboxTestTags.CALENDAR_NEXT_MONTH),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = stringResource(R.string.inbox_calendar_next_month),
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 12.dp)) {
                weekdayNames.forEach { dayName ->
                    Text(
                        text = dayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                // HorizontalCalendar 底层是 LazyRow，月份页高度是 wrap 的：给日历一个
                // 确定性高度（可用宽 ÷ 7 × 6，一个月最多 6 行），弹层高度不随月份行数
                // （4～6 行）切页而跳变；行内格子高度由日格自身的 aspectRatio 撑起。
                HorizontalCalendar(
                    state = calendarState,
                    modifier = Modifier.height(maxWidth * 6 / 7),
                    dayContent = { day ->
                        CalendarDayCell(
                            day = day,
                            count = dayCounts[day.date],
                            isSelected = day.date == selectedDate,
                            isToday = day.date == today,
                            onClick = {
                                // 点已选中的天 = 清除筛选，和 chip 的 × 是同一个动作的两个人口
                                onDateSelected(if (day.date == selectedDate) null else day.date)
                            },
                        )
                    },
                )
            }
            if (selectedDate != null) {
                TextButton(
                    onClick = { onDateSelected(null) },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag(InboxTestTags.CALENDAR_CLEAR),
                ) {
                    Text(text = stringResource(R.string.inbox_calendar_clear))
                }
            }
        }
    }
}

/**
 * 一个日格。渲染规则：
 * - 选中日：primary 实底圆；今天：primary 描边圆；其余裸数字
 * - 有记录的日（[count] 非空）：数字下方一颗圆点 —— 圆点是"这天值得点"的信号，
 *   不表达数量（3 条和 30 条对"要不要点进去看"的决策没有差别）
 * - 相邻月的补位格（InDate/OutDate）：变暗、不可点
 */
@Composable
private fun CalendarDayCell(
    day: CalendarDay,
    count: Int?,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    val inMonth = day.position == DayPosition.MonthDate
    val countDescription = count?.let { stringResource(R.string.inbox_calendar_day_has_records, it) }
    Box(
        // 库已在 CalendarMonths 里用 weight(1f) 给这个格子分配好了 1/7 宽度（有界）；
        // 这里**不能**再 fillMaxWidth(1/7)——两层相乘变视口的 1/49（≈21px），
        // aspectRatio 把高度也压成 21px，整张日历塌成一条残影（2026-10-24 实踩）。
        modifier = Modifier
            .aspectRatio(1f)
            .testTag(InboxTestTags.calendarDayTag(day.date))
            .then(if (countDescription != null) {
                Modifier.semantics { contentDescription = countDescription }
            } else {
                Modifier
            })
            .then(if (inMonth) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
            border = if (isToday && !isSelected) {
                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = day.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.onPrimary
                        inMonth -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                )
            }
        }
        if (count != null && inMonth) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
            )
        }
    }
}

private const val START_MONTHS_BACK = 12L
