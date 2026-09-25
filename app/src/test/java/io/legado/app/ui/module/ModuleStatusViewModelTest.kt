package io.legado.app.ui.module

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模块状态 ViewModel 测试（testing.md §16：快照函数注入，无需 Android 依赖）
 * 覆盖：初始快照、构造时读取次数、刷新后状态更新。
 */
class ModuleStatusViewModelTest {

    private fun items(status: ModuleRunStatus) =
        listOf(ModuleStatusItem(name = "Web 服务", status = status))

    @Test
    fun `初始值来自快照提供者`() {
        val viewModel = ModuleStatusViewModel { items(ModuleRunStatus.RUNNING) }

        assertEquals(items(ModuleRunStatus.RUNNING), viewModel.modules.value)
    }

    @Test
    fun `构造时快照只读取一次`() {
        var calls = 0
        val viewModel = ModuleStatusViewModel {
            calls += 1
            items(ModuleRunStatus.IDLE)
        }

        assertEquals(1, calls)
        assertEquals(items(ModuleRunStatus.IDLE), viewModel.modules.value)
    }

    @Test
    fun `refresh 重新读取快照并更新状态`() {
        var current = items(ModuleRunStatus.IDLE)
        val viewModel = ModuleStatusViewModel { current }
        assertEquals(items(ModuleRunStatus.IDLE), viewModel.modules.value)

        current = items(ModuleRunStatus.ERROR)
        viewModel.refresh()

        assertEquals(items(ModuleRunStatus.ERROR), viewModel.modules.value)
    }

    @Test
    fun `数值状态枚举映射保持稳定`() {
        val viewModel = ModuleStatusViewModel { items(ModuleRunStatus.DISABLED) }
        val status = viewModel.modules.value.single().status

        assertEquals(ModuleRunStatus.DISABLED, status)
        assertEquals(3, status.ordinal)
    }
}