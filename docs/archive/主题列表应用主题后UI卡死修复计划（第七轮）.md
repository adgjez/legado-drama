# 主题列表应用主题后 UI 卡死修复计划（第七轮）

> 针对根因：一次性点击触发"重建风暴 + 竞态双重建"（AppCompat 模式重建 + RECREATE 同步广播 + 页面显式重建，含 `App.onConfigurationChanged` 反馈环）。本轮把已归档根因分析（`docs/archive/主题列表应用主题后UI卡死根因分析.md`）第 5/8/11/12/13 节的方案在 `test` 分支完整落地。
> 状态：✅ 已完成（2026-09-10 提交归档；构建验证通过）

## 背景一句话

应用主题 → `ThemeConfig.applyDayNight` 同时触发 `AppCompatDelegate.setDefaultNightMode`（模式切换即重建）+ `postEvent(RECREATE)`（各页重建）+ 反馈环（`App.onConfigurationChanged` 再次 `applyDayNight`）→ 一次点击 3~6 次重建风暴 → 部分 ROM 上窗口状态被破坏，页面定格、触摸失效。当前 `test` 分支缺少此前订立的全部修复（已逐条核对）。

## 改动清单

| # | 文件 | 改动 | 对应分析章节 |
|---|------|------|--------------|
| 1 | `app/src/main/java/io/legado/app/App.kt` | `onConfigurationChanged`：UI 模式变化时只 `applyTheme()` + `notifyRecreate()`，**不再调用 `setDefaultDarkMode`/`applyDayNight`**（断开反馈环） | 12.2 |
| 2 | `app/src/main/java/io/legado/app/help/config/ThemeConfig.kt` | 新增 `notifyRecreate()` **尾沿防抖**广播（静默 1.5s 窗口合并多路触发，窗口内新操作会顺延，不吞真实操作）；`applyDayNight` 的 `postEvent(RECREATE)` 改用它 | 12.2/13.2 |
| 3 | `app/src/main/AndroidManifest.xml` | `ThemeManageActivity`、`ConfigActivity` 增加 `android:configChanges="uiMode"`（AppCompat 不再自动重建，重建收敛为事件唯一触发） | 5.1/10.1 |
| 4 | `app/src/main/java/io/legado/app/help/config/AppConfig.kt` | `isNightTheme` setter 同步刷新 `themeMode` 缓存并退出墨水屏模式，消除目标模式判别错误 | 5.2 |
| 5 | `app/src/main/java/io/legado/app/base/BaseComposeActivity.kt` | 新增 `recreate()` 合并守卫（同实例重建完成前只接受一次） | 5.4 |
| 6 | `app/src/main/java/io/legado/app/ui/config/theme/manage/ThemeManageActivity.kt` | 覆写 `onConfigurationChanged` → `recreate()`，日夜模式变化自行一次重建 | 5.3 |
| 7 | `app/src/main/java/io/legado/app/base/BaseActivity.kt` | `upBackgroundImage()` 解码+模糊移出主线程（`Dispatchers.Default` + 主线程回设 + 生命周期守卫） | 8.2 |
| 8 | `app/src/main/java/io/legado/app/ui/main/MainActivity.kt` | RECREATE 重建延后到 `onResume`（前台立即、后台标记） | 11.2 |
| 9 | `app/src/main/java/io/legado/app/ui/config/ConfigActivity.kt` | 同上 | 11.2 |
| 10 | `app/src/main/java/io/legado/app/ui/config/theme/legacy/ThemeConfigFragment.kt` | `recreateActivities()` 改用 `ThemeConfig.notifyRecreate()` | 13.2 |

## 实施顺序

1. 文档（本计划 + 根因分析第 14 章）先落盘；
2. 以上 1~10 按"根因 → 防抖 → 收口"的顺序实现，改动各自独立可审；
3. 构建验证：`./gradlew assembleDebug`（默认 appMax flavor）无编译错误；
4. code-review：Standards（项目编码规范）+ Spec（是否按本计划实现）两维度；
5. 提交：本地 commit，conventional commits 中文规范，不 push。

## 验证方式

1. 构建通过、安装后手动过一遍：日夜主题互切、同模式主题互切、带背景图主题互切；
2. 观察点：应用主题后页面只重建一次（无需多次闪烁），点击/滑动立即恢复，背景图正常显示；
3. Logcat 佐证：一次应用主题只出现 1 条 `post: RECREATE`（旧实现为 6 条）——对应根因分析 13.3 的验收标准。

## 不在本次范围（follow-up）

- `Toolkit.blur` 耗时优化（小图模糊放大）——主线程已不在阻塞，属性能项；
- `clearBg` 文件遍历删除异步化；
- 高亮规则/阅读页等其他页面的同类重建竞态治理（如有复现再单独立项）；
- 旁路未收口：`NavigationBarConfig.kt:305`、`BaseBookshelfFragment.kt:362` 仍直接 `postEvent(EventBus.RECREATE)`（绕过防抖与延后重建），与 #3 "重建收敛为事件唯一触发"的字面不一致，属遗留风险，另行治理；
- 防抖设计说明：按 code-review 建议采用**尾沿合并**（静默 1.5s 窗口结束后才发一次；窗口内新操作顺延），避免吞掉 ≤1.5s 内连续两次真实的主题操作。