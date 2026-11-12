package com.zhicun.app.presentation.navigation

import android.net.Uri

/**
 * 路由表（Navigation Compose）。
 *
 * 收敛在一个 object 里而不是散落字符串字面量：路由是跨模块契约（M4 分享接收后要直接跳详情、
 * M10 搜索要跳详情），拼错只会在运行时才崩。
 */
object Routes {

    /** 收件箱（启动页）。 */
    const val INBOX: String = "inbox"

    /**
     * 首启引导（附录 T）。**不**加入 [ExternalRoutes.allowed]：引导只由"导航起点判定"
     * 进入，外部 Intent 没有理由把用户拽进来（重新看引导不是外部可达需求）。
     */
    const val ONBOARDING: String = "onboarding"

    /** 新建记录页（M5）。不需要参数。 */
    const val CREATE: String = "create"

    /** 新建页路由的可选参数名：剪贴板读不到跳过来时带一次性"长按可粘贴"提示（M13 D-1）。 */
    const val CREATE_PASTE_HINT_ARG: String = "pasteHint"

    /**
     * 带"长按可粘贴"提示的新建页路径。
     *
     * 用 query 参数而不是独立路由：普通入口（收件箱 FAB）`navigate("create")` 仍匹配
     * 同一个 composable 模板，参数默认 false，不用为提示复制一份页面。
     */
    const val CREATE_PASTE_HINT: String = "$CREATE?$CREATE_PASTE_HINT_ARG=true"

    /** 录音页（M9）。不需要参数：录完保存后直接跳详情。 */
    const val VOICE: String = "voice"

    /** 搜索页（M10）。关键词不放进路由：搜索是过程状态，重建页面时回到空白搜索框更合理。 */
    const val SEARCH: String = "search"

    /** 设置页（M13 起，M14 扩充）。 */
    const val SETTINGS: String = "settings"

    /** 归档箱（M16）。不需要参数：归档列表本身就是全部数据。 */
    const val ARCHIVE: String = "archive"

    /** 详情页路径模板中的参数名。 */
    const val RECORD_ID_ARG: String = "recordId"

    /** 详情页路由模板。 */
    const val RECORD_DETAIL: String = "record/{$RECORD_ID_ARG}"

    /**
     * 构造详情页实际路径。
     *
     * recordId 来自外部分享/剪贴板，理论上可能含 `/`、`?`、中文等字符，
     * 直接拼接会让 Navigation 把路径拆错（`record/a/b`），故必须编码。
     */
    fun recordDetail(recordId: String): String = "record/${Uri.encode(recordId)}"
}

/**
 * 允许由**外部 Intent** 直达的路由白名单（2026-10-24 全项目审查 L-1）。
 *
 * `MainActivity.EXTRA_START_ROUTE` 是 Intent extra，属于外部可写的输入：Activity 是
 * `exported=true` 的启动入口，adb、调试工具、以及将来若为深链加 scheme 的任意应用都能把它
 * 设成任意字符串。直接把它喂给 Navigation 等于让外部数据决定内部导航目标 —— 落到不存在的
 * 路由轻则白屏/抛异常，重则绕过页面入口处的状态约束。
 *
 * 这里用**封闭集合**而不是黑名单：新增可外部直达的页面必须显式登记，否则入口静默失效
 * （日志里能看到"未知路由，已忽略"），这正是我们想要的失败方向。
 *
 * 纯 Kotlin、无 Android 依赖，因此可以穷举验证。
 */
object ExternalRoutes {

    /** 全部可外部直达的路由。 */
    val allowed: Set<String> = setOf(
        Routes.INBOX,
        Routes.CREATE,
        Routes.CREATE_PASTE_HINT,
        Routes.VOICE,
    )

    /**
     * @return 合法路由原样返回；`null` 表示这个值不该被采纳（空、未知、或企图跳详情页等
     *   需要上下文的路由）。
     */
    fun sanitize(raw: String?): String? = raw?.takeIf { it in allowed }
}
