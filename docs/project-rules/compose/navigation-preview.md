# Compose UI 规范 — 导航与 Preview

> 原 `UI-ARCHITECTURE.md`（2026-08-19）拆分产物：§9、§10，章节编号沿用原编号，跨文件引用按「文件名 §编号」格式书写。生效范围、执行方式、老代码策略等通用约定见 [README.md](./README.md)。
> **最后更新**：2026-08-19

---

## 9. 导航规范

> **现状**：项目尚未引入 Navigation Compose（无 `NavHost` / `NavRoute` 相关依赖）。本节是为引入导航体系时立的**先行约定**；现行页面跳转仍走 Intent / Activity，暂不按本节强规则检查，设计新导航层时必须遵循。

- 路由路径**必须**集中定义（如 `object NavRoute`），**禁止**在调用点散落路由字符串字面量。
- 路由参数**必须**通过 `navArgument` + `NavType` 定义，Screen 统一解包成 `NavArgs` 数据类（见 `migration-review.md` §17 违规 D）后使用，**禁止**在 Screen 里直接 `savedStateHandle["xxx"]` 再手动转类型。
- 回栈操作（跳指定页、关指定页）**必须**走统一的 `NavController` 扩展或路由管理器，**禁止**调用点散落 `popBackStack("xxx", false)` 字面量。
- 路由定义与 `NavArgs` **禁止**依赖 ViewModel / 数据层类，导航层保持可独立拆分。

---

## 10. Preview 规范

### 10.1 通用组件（强制）

- `ui/widget/components/` 下（跨 Feature 复用层）的**每个**公共 Composable **必须**附带至少一个 `@Preview`。
- Preview 命名格式：`{Composable名}Preview`，如 `AppListItemPreview`（下方示例的 `AppListItem` 为目标态组件、尚未落地，同 `structure.md` §3.1 说明）。
- **推荐**提供多状态 Preview（正常 / 禁用 / 空数据 / 长文本截断）。

```kotlin
@Preview(name = "Normal")
@Preview(name = "Long text", locale = "zh")
@Composable
private fun AppListItemPreview() {
    LegadoTheme {
        AppListItem(
            icon = Icons.Default.Book,
            title = "书源管理",
            subtitle = "已导入 23 个书源"
        )
    }
}
```

### 10.2 Screen 级（推荐）

- Screen 级 Composable **推荐**写 Preview，至少覆盖默认状态。
- 如果 Screen 依赖 ViewModel，用 fake data 手动构造 `UiState` 传入，禁止在 Preview 里调真实 Repository。

### 10.3 禁止项

- **禁止** Preview 函数设为 `public`。必须 `private`，它们不参与生产编译。
- **禁止** 在 Preview 里写业务逻辑。Preview 只负责渲染验证。

---
