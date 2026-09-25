# Android Lint 指南（工具本身、与普通 Lint 的区别、项目用法）

> 本文是**工程配置参考 / 手册**，不是强制规范红线：讲清 Android Lint 是什么、查什么、和 commitlint / ktlint 这类工具的区别，以及本项目怎么跑、基线机制怎么用。
> **什么时候读**：CI 里 Android Lint 步骤报错、想明白某个 lint 诊断在说什么、要给新代码过 lint 时。

## 1. 是什么

Android Lint 是 Android SDK 自带的静态代码分析工具，随 Gradle Android 插件（AGP）一起执行。它不运行 app，而是把项目的 Kotlin / Java 源码、`AndroidManifest.xml`、`res` 资源、依赖库统一解析，对照 200 多种内建检查规则找潜在问题，并按严重程度分三档：

| 档位    | 含义                 | 典型例子                                      |
| ------- | -------------------- | --------------------------------------------- |
| Error   | 会导致功能失效的硬伤 | `onBackPressed` 在 API 36+ 手势返回下不被调用 |
| Warning | 潜在隐患             | 调用了已废弃 API、布局里硬编码文案            |
| Hint    | 优化建议             | 布局层级过深、可加 `contentDescription`       |

CI 里默认「有 Error 就构建失败」（`abortOnError`），Warning 和 Hint 只进报告不阻塞。

## 2. 查什么（按分类举例）

| 分类            | 检查项示例                                                                                                        | 为什么重要                               |
| --------------- | ----------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| API 兼容 / 废弃 | `onBackPressed` 迁移到 `OnBackPressedDispatcher`；`Icons.Filled.*` 换 AutoMirrored 版；`bundleOf` 换原生 `Bundle` | 新系统上行为会变，只升级编译版本发现不了 |
| 资源国际化      | 布局硬编码文案（应抽到 strings）；`MissingTranslation`（本项目已显式关闭，见 app/build.gradle）                   | 不抽资源无法多语言 / 翻译                |
| RTL 适配        | `paddingLeft/Right` 应换 `paddingStartEnd`、`Gravity.LEFT/RIGHT` 应换 `START/END`                                 | 阿拉伯语等 RTL 布局下 UI 错位            |
| 可访问性        | 图标无 `contentDescription`、触控目标过小                                                                         | 无障碍服务效果差                         |
| 性能与安全      | 不必要的对象分配、可致 ANR 的主线程磁盘访问                                                                       | 卡顿与崩溃隐患                           |

## 3. 和「普通 lint」的区别

「Lint」是静态检查工具的通用叫法。本项目同时存在多个 lint 系工具，职责完全不同：

| 工具              | 检查对象                               | 检查内容                                             | CI 位置                                          |
| ----------------- | -------------------------------------- | ---------------------------------------------------- | ------------------------------------------------ |
| commitlint        | 提交信息                               | 是否符合 Conventional Commits 中文规范               | lint.yaml「Check Commit Messages」               |
| spotless + ktlint | `.kt` 文件文本                         | Kotlin 代码风格（尾随逗号、导入、换行）              | lint.yaml「Kotlin Format Check」（可选，不阻塞） |
| Android Lint      | 代码 + 资源 + manifest                 | 平台语义（兼容性 / 废弃 API / 资源 / 无障碍 / 性能） | lint.yaml「Android Lint」（硬性必过）            |
| prettier          | `.js / .ts / .jsx / .tsx / .vue / .md` | 前端与文档格式（package.json `lint-staged` 实配）    | 仅本地 pre-commit                                |

一句话：普通 lint 查**风格**，Android Lint 查**会不会出事**。ktlint 不会告诉你 `onBackPressed` 在新系统失效，Android Lint 会。

## 4. 项目里的用法

- 入口任务与 CI：`./gradlew :app:lintAppMaxDebug`（CI 同命令，见 lint.yaml「Android Lint」步骤）。
- **基线机制**：存量问题全部进了 `app/lint-baseline.xml`（约 740KB，91 errors / 1569 warnings 固化），配置在 app/build.gradle 的 `lint { baseline = file("lint-baseline.xml") }`。底线之后 lint 只拦**新增**问题，存量不再提示也不阻塞。
- 本地看报告：跑完任务后打开 `app/build/reports/lint-results-appMaxDebug.html`，按条有解释与跳转，比终端好看。
- 更新基线时机：当你**有意修掉一批存量问题**或升级 AGP 导致大量噪音时，跑一次 `./gradlew :app:updateLintBaselineAppMaxDebug` 重新拍快照（全量分析约 10 分钟）。平时不要主动更新基线，否则等于把新问题也豁免了。
