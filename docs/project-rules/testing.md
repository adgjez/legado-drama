# 测试规范参考

> 本文档汇总项目测试策略与命令，属**工程配置参考**（测试相关的强制约定在 CLAUDE.md 核心规则及对应规范文档里）。
> **什么时候读**：写测试前、跑测试命令、决定该不该提交测试文件时。
>
> 强制约定以 CLAUDE.md 为准，此处不再转述：TDD 按环境灵活执行（核心规则 3）；任务没明确要求时不主动提交测试文件（核心规则 7）。

## 1. 测试分层与位置

| 层       | 位置                   | 说明                           |
| -------- | ---------------------- | ------------------------------ |
| 单元测试 | `app/src/test/`        | JVM 单元测试                   |
| 集成测试 | `app/src/androidTest/` | 仪器测试（Instrumented tests） |

## 2. 工具与约定

- **Mock 框架**：Mockk
- **协程测试**：kotlinx-coroutines-test
- **覆盖率**：数字不作验收指标（口径与 compose/testing.md §16.4 一致）；真实约束是新增 ViewModel / 修改状态机的 PR 必须有对应测试
- **内存泄漏检测**：LeakCanary，仅 `debugImplementation`，只开在 debug 构建

## 3. 命令

```bash
./gradlew test                # 单元测试
./gradlew connectedAndroidTest  # 仪器测试
```
