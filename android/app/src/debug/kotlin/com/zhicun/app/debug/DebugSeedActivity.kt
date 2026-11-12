package com.zhicun.app.debug

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.zhicun.app.domain.repository.RecordRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 调试用「灌示例数据」Activity（**仅 debug 构建**）。
 *
 * 用途：M3 阶段没有创建记录的入口（M4 才有分享接收），需要一种方式让收件箱有内容可看，
 * 以便验收卡片排版、状态徽标、详情页。跑完立刻 `finish()`，不留界面。
 *
 * 使用：
 * ```
 * adb shell am start -n com.zhicun.app/com.zhicun.app.debug.DebugSeedActivity
 * ```
 * release 包里没有这个类，manifest 里的声明也只在 debug 生效。
 */
@AndroidEntryPoint
class DebugSeedActivity : ComponentActivity() {

    @Inject
    lateinit var recordRepository: RecordRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val inserted = try {
                DebugSeedData.seedIfEmpty(recordRepository)
            } catch (e: Exception) {
                // 调试工具同样不允许空 catch：失败要说清楚，否则用户以为"没反应"
                Toast.makeText(this@DebugSeedActivity, "灌数据失败：${e.message}", Toast.LENGTH_LONG)
                    .show()
                finish()
                return@launch
            }

            val message = if (inserted == 0) "已有数据，未插入" else "已插入 $inserted 条示例记录"
            Toast.makeText(this@DebugSeedActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
