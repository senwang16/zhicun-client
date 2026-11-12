package com.zhicun.app.service

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.zhicun.app.R

/**
 * 麦克风权限的透明中转页（2026-10-26）。
 *
 * ## 为什么需要它
 * 悬浮气泡是**没有 Activity 的前台 Service**，没法直接弹运行时权限框。
 * 长按录音而 `RECORD_AUDIO` 未授予时，由本页（透明、无 UI）拉一次系统权限框，
 * 拿到结果后立刻 `finish()` 回到用户原来的界面。
 *
 * ## 不自动续录
 * 请求权限时系统弹窗会抢占焦点，用户按住气泡的手指必然已经抬起，此时"自动继续录音"
 * 会得到一个没有结束信号、只能靠点气泡停下来的录音。因此授权成功后**不**自动开录，
 * 由用户再次长按气泡（那时已有权限，录音正常开始）。
 *
 * ## 永久拒绝要能走（2026-10-28 修复）
 * 系统在用户第二次拒绝后不再弹框，只给一句"去系统设置开启"不提供入口，用户只能自己翻菜单。
 * 因此 `shouldShowRequestPermissionRationale` 为 false（系统不会再弹）时，直接跳到本应用
 * 详情页；首次拒绝（仍可再次申请）只提示，不打断用户。
 */
class MicPermissionProxyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "麦克风权限申请结果 granted=$granted")
            FloatingBubbleService.onMicPermissionResult(granted)
            // 未授权时不能静默失败（PRD v1.2 §4.3）：顶部条在透明页后面，用户看不到提示
            if (!granted) {
                Toast.makeText(
                    this,
                    R.string.floating_record_permission_denied,
                    Toast.LENGTH_LONG,
                ).show()
                if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    openAppSettings()
                }
            }
            finish()
        }
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * 打开本应用详情页。
     *
     * 用 `APPLICATION_DETAILS_SETTINGS` 而不是 `ACTION_APPLICATION_SETTINGS`：后者是系统
     * 应用列表，用户还得再找智存一次；前者直达权限开关。失败只留痕：页面已经 finish，
     * 再抛异常只会变成一次用户无法理解的崩溃。
     */
    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "打开应用详情页失败", e)
        }
    }

    private companion object {
        const val TAG = "MicPermission"
    }
}