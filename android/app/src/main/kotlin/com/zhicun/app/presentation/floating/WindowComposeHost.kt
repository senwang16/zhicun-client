package com.zhicun.app.presentation.floating

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.view.View

/**
 * 把 Compose 装进 `WindowManager` 时需要的"Android 生命周期宿主"（M13）。
 *
 * ## 为什么必须有这个东西
 * `ComposeView` 依赖三层 `ViewTree` owner 才能工作：`LifecycleOwner`（驱动重组）、
 * `ViewModelStoreOwner`（`viewModel()`）、`SavedStateRegistryOwner`（`rememberSaveable`）。
 * 正常 Activity 里这三层由系统自动种在 DecorView 上；而通过 `WindowManager.addView()`
 * 挂上去的 View **没有 Activity 祖先**，三者全是 null，一渲染就抛
 * `IllegalStateException: ViewTreeLifecycleOwner not found`。
 *
 * 因此由一个普通对象实现三个接口，再逐层 set 到 View 上。它不需要是 Service ——
 * 只需要在窗口存在期间保持 RESUMED，销毁时转 DESTROYED 让 Compose 清掉观察者。
 */
class WindowComposeHost : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    /** 窗口添加前调用：恢复（空的）已保存状态并进入 RESUMED，Compose 才会开始绘制。 */
    fun attach() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** 窗口移除后调用：进入 DESTROYED 并清空 ViewModel，避免持有界面引用。 */
    fun detach() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }

    /** 把三层 owner 种到某个 View 上（必须在 `setContent` 之前调用）。 */
    fun installOn(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}
