# 构建命令与工程配置参考

> 本文档是构建体系的**工程配置参考**（不是强制红线）：构建命令全表、Web 前端命令、Build Variants、SDK/JDK、版本目录。
> **什么时候读**：构建报错、遇到不认识的 flavor / 命令、首次构建、涉及 `gradle/libs.versions.toml` 或 `modules/web/` 时。
> 日常最常用的 6 条命令已放在 CLAUDE.md，这里记录完整矩阵。

## 1. Gradle 构建命令

Gradle wrapper（Windows 下为 `gradlew.bat`），JDK 17 要求。

| 命令                                            | 说明                                                                                        |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `./gradlew assembleDebug`                       | Debug 构建（默认 flavor：appMax）                                                           |
| `./gradlew assembleRelease`                     | Release 构建（ProGuard + resource shrinking）                                               |
| `./gradlew assembleAppMaxDebug`                 | appMax（`io.legado.app.yuedu`，共存包）                                                     |
| `./gradlew assembleAppLegacyRelease`            | appLegacy（`io.legado.app`，与原版一致）                                                    |
| `./gradlew assembleAppSDebug`                   | appS（`io.legado.app.yuedu.a`）                                                             |
| `./gradlew installDebug` / `installAppMaxDebug` | 安装到设备                                                                                  |
| `./gradlew test`                                | 单元测试                                                                                    |
| `./gradlew connectedAndroidTest`                | 仪器测试（Instrumented tests）                                                              |
| `./gradlew stop`                                | 停止 Gradle daemon                                                                          |
| `./gradlew.bat :app:compileAppMaxDebugKotlin`   | 语法检查式编译（"Grammar Test"）                                                            |
| `./gradlew lint`                                | Android Lint（CI 实际入口为 `:app:lintAppMaxDebug` 单变体）                                 |
| `./gradlew spotlessCheck`                       | Kotlin 格式检查：只检查自 origin/main 以来的改动（CI 为可选警示，continue-on-error 不阻塞） |
| `./gradlew spotlessApply`                       | 自动修正全部 Kotlin 格式问题；提交前可手动执行                                              |
| `./gradlew app:downloadCronet`                  | **首次构建前必须执行**，下载 Cronet 原生库                                                  |
| `./gradlew assembleDebug --warning-mode all`    | 查看 DSL 语法警告（Windows/Mac/Linux 同命令）                                               |

### Kotlin 代码格式（spotless + ktlint）

- 配置位于根 `build.gradle` 的 `spotless {}` 块；`ratchetFrom 'origin/main'` 使检查只覆盖**自 origin/main 以来的改动**，存量代码不强制全量合规。
- **前提**：本地首次使用前需先 `git fetch origin main`（让 `origin/main` ref 存在），否则 `spotlessCheck` 会因找不到基线而报错；CI 通过 `fetch-depth: 0` 满足该前提。
- 与存量惯例冲突的风格类规则已在根 build.gradle 的 `editorConfigOverride` 中显式关闭（完整清单以该配置块为准）：函数/属性命名（Compose 大写组件名、驼峰常量）、import 字母序、wildcard 导入、注释位置类规则、行宽（暂放开）等。需要调整时改根 build.gradle 的 `editorConfigOverride`。
- Kotlin 格式化由 spotless 负责；`prettier`（node）按 package.json `lint-staged` 实配只处理 `js/ts/jsx/tsx/vue/md`，**不碰 `.java` 与 `.kt`**。

## Web 前端（modules/web）

嵌入 HTTP 服务器的前端是 Vue 3 + Vite 应用，构建产物同步到 `app/src/main/assets/web/vue/`。

```bash
cd modules/web
pnpm install        # requires Node >= 20, pnpm >= 9
pnpm dev            # local dev server with HMR
pnpm build          # production build + syncs to assets/web/vue/
pnpm lint:fix       # eslint auto-fix
pnpm format         # prettier
```

## Build Variants（3 个 flavor）

product flavors 维度为 "app"：

| flavor      | 包名                    | 说明                     |
| ----------- | ----------------------- | ------------------------ |
| `appLegacy` | `io.legado.app`         | 与原版 Legado 一致       |
| `appMax`    | `io.legado.app.yuedu`   | 共存包，**主要开发目标** |
| `appS`      | `io.legado.app.yuedu.a` | 另一个共存包             |

- SDK 级别：minSdk 23 / targetSdk 37 / compileSdk 37 / JVM 17 toolchain。
- `coreLibraryDesugaring` 开启 —— JVM 17 语法（records、text blocks、List.of）可兼容到 API 23。
- debug/release 两种构建类型均追加 `applicationIdSuffix`（`.debug` / `.release`），所以安装包如 `io.legado.app.yuedu.debug`，不是裸 flavor id。
- Release：`minifyEnabled` + `shrinkResources` + ProGuard（`app/proguard-rules.pro`、`app/cronet-proguard-rules.pro`）；Debug：不混淆。

## 版本与 SDK

- 所有依赖版本统一在 `gradle/libs.versions.toml`，在 `build.gradle.kts` / `build.gradle` 中按 `libs.xxx` 引用，禁止硬编码版本号。
- 主版本速览：Kotlin 2.3.10、Hilt 2.59、OkHttp 5.3.2、Room 2.8.4、Coroutines 1.10.2、Compose BOM 2026.08.00。
- 新增依赖时同步更新本目录与相关规则文档（见 project-rules README 索引）。
