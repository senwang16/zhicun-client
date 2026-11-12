package com.zhicun.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * 把 `Dispatchers.Main` 换成测试调度器。
 *
 * ViewModel 的 `viewModelScope` 跑在 `Dispatchers.Main.immediate` 上，
 * JVM 单测没有 Main Looper，不替换会直接抛
 * `Module with the Main dispatcher had failed to initialize`。
 *
 * 选 `UnconfinedTestDispatcher`（而不是 Standard）的原因：
 * `stateIn(WhileSubscribed)` 的上游在订阅时立刻开始跑，Standard 需要手动 `advanceUntilIdle()`，
 * 每个用例都要多一步且容易漏。Unconfined 下断言更直接：
 * 不订阅时 `uiState.value` 就是 initialValue（Loading），一旦订阅同步进入真实状态。
 */
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
