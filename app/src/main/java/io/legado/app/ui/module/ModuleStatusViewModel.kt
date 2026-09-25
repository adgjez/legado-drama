package io.legado.app.ui.module

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 模块状态ViewModel
 * 管理模块状态列表，提供手动刷新功能
 *
 * 快照函数经构造注入（默认读 [ModuleStatusProvider]），JVM 单测可直接提供假快照
 */
class ModuleStatusViewModel(
    private val snapshotProvider: () -> List<ModuleStatusItem> = ModuleStatusProvider::snapshot
) : ViewModel() {

    // 可变状态流（内部可写）
    private val _modules = MutableStateFlow(snapshotProvider())
    // 只读状态流（外部观察）
    val modules: StateFlow<List<ModuleStatusItem>> = _modules.asStateFlow()

    /**
     * 刷新模块状态
     * 重新从各服务获取最新状态快照
     */
    fun refresh() {
        _modules.value = snapshotProvider()
    }
}
