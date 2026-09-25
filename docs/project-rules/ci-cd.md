# CI/CD 配置参考（GitHub Actions）

> 本文档记录 `.github/workflows/` 下各 workflow 的职责与触发条件，属**工程配置参考**。
> **什么时候读**：CI 报错、要调整 workflow、想确认发版 / 构建流程时。
> 注意：CI 中 lint 与编译通过视为"完成"的一部分（本地 `:app:lintAppMaxDebug`、`compileDebugSources` 也要能过）；格式检查（spotless）为可选，不阻塞 CI，见下方清单。

## 当前 workflows

| 文件                   | 名称                   | 触发                                                   | 作用                                                                                       |
| ---------------------- | ---------------------- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------ |
| `test.yml`             | Test Build             | push / pull_request / workflow_run / workflow_dispatch | 构建全部 3 个 release flavor；自动创建 GitHub/Gitee 发布并从 `updateLog.md` 生成 changelog |
| `web.yml`              | Build Web              | push / pull_request / workflow_dispatch                | `modules/web/` 有过改动时构建 Vue 前端，并把产物提交到 `app/src/main/assets/web/vue/`      |
| `cronet.yml`           | Update Cronet          | schedule / workflow_dispatch                           | 更新 Cronet 原生库                                                                         |
| `lint.yaml`            | Quick Lint and Compile | push / pull_request                                    | commit 规范(commitlint) + 编译 + spotless 格式检查(可选) + Android lint + 帮助文档映射校验 |
| `unit-test.yaml`       | Unit Test              | push / pull_request                                    | 单元测试                                                                                   |
| `build-onlyDebug.yaml` | Build Debug APK        | push / pull_request                                    | 构建 Debug APK                                                                             |
| `release.yml`          | Release Build 双包打包 | workflow_dispatch                                      | 手动触发，打双包发布                                                                       |
| `stale.yml`            | closeStaleIssue        | schedule / workflow_dispatch                           | 清理 stale issue                                                                           |

## 检查项与工具链清单

CI 里用到的检查工具分散在各 workflow 中，这里集中登记，方便对照「配置在哪、谁在用、是否必过」。

| 工具 / 检查项                  | 作用                                                                                                             | 配置位置                                                                                                                                           | 在 CI 中的使用                                                                                          | 状态                                                                                     |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| commitlint                     | 提交消息规范（Conventional Commits，中文）                                                                       | `.commitlintrc.js`（含 `ignores` 放行历史遗留裸「更新日志」）                                                                                      | lint.yaml「Check Commit Messages」，`commitlint --from origin/main --to HEAD`                           | 硬性必过                                                                                 |
| husky + lint-staged + prettier | 本地 git 钩子：commit-msg 调 commitlint、pre-commit 对 js/ts/jsx/tsx/vue/md 跑 prettier + help-doc-sync 映射校验 | `.husky/`、`package.json` 的 `lint-staged` 段                                                                                                      | 仅本地生效（CI 不跑 prettier，`npm ci --ignore-scripts` 是为了跑 commitlint）                           | 硬性（本地）                                                                             |
| spotless + ktlint              | Kotlin 格式检查（尾随逗号、导入、换行等）                                                                        | 根 `build.gradle` 的 `spotless` 块（`ratchetFrom origin/main`；`editorConfigOverride` 关闭的多项冲突规则清单见 build-commands.md Kotlin 格式一节） | lint.yaml「Kotlin Format Check」，`./gradlew spotlessCheck`                                             | **可选**（`continue-on-error`，只警示不阻塞；本地可 `./gradlew spotlessApply` 自动修复） |
| Android Lint                   | 静态分析（可用性 / 性能 / API 兼容等）                                                                           | 模块 `build.gradle`（`lint` 选项）                                                                                                                 | lint.yaml「Android Lint」，`:app:lintAppMaxDebug`                                                       | 硬性必过                                                                                 |
| help-doc-sync                  | 校验 `docs/help-doc-sync/map.json` 映射的有效性                                                                  | `docs/help-doc-sync/`（机制说明见该目录 `pre-commit-hook自动运行机制.md`）                                                                         | lint.yaml「Help Doc Map Verify」，`node scripts/help-doc-sync.mjs --verify`；同时为本地 pre-commit 钩子 | 硬性必过                                                                                 |
| JVM 单元测试                   | 纯函数与 ViewModel 单测                                                                                          | app 模块 `src/test/java/`                                                                                                                          | unit-test.yaml（Gradle test 任务）                                                                      | 硬性必过                                                                                 |

配套说明：

- CI 中 commitlint 与 help-doc-sync 依赖 Node 环境：lint.yaml 先 `npm ci --ignore-scripts` 安装（`package-lock.json` 已入库，勿忽略）。
- Gradle 相关 job 通过 `actions/cache` 缓存 `~/.gradle`，加速重复构建。
- workflow 中用到 JDK 17（temurin），JDK、Gradle 版本以 `.github/workflows/` 与 `gradle/wrapper` 为准。

## 使用注意

- workflow 文件以推送分支为准，本地改动后推到对应分支才会触发。
- 涉及签名密钥的关键文件在 workflows 目录内，发布相关改动要核对 `release.yml` 与密钥引用。
- 新加 workflow 后在本表登记，保持索引与实际文件一致（与 project-rules 维护约定一致）。
