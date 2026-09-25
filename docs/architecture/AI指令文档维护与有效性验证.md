# AI 指令文档的维护与有效性验证

> 适用范围：CLAUDE.md、AGENTS.md 与 `docs/project-rules/` 等 AI 指令文件的维护方法与效果验证。**有效**（2026-09-12 随规范库清理整理，属方法论笔记，随实践演进更新）。
> 背景：2026-09-12 规范库做过一轮「修过时 / 消矛盾 / 瘦身 / 虚账标注」清理（`919ff7f8f..cc3ecbba3`），本文回答清理时引出的两个问题：直接改规范文件是否符合业内做法？改完之后如何确认真的更有效？

## 一、直接修改是业内常态

- CLAUDE.md / AGENTS.md 就是仓库里的普通受管文件，走和代码一样的提交 + 评审流程，没有额外仪式。Anthropic 官方对 CLAUDE.md 的定位就是「像调 prompt 一样持续迭代」：发现 AI 犯了某类错就补一条，某条从不起作用就删掉。
- AGENTS.md 已成为跨工具的开放标准（Codex、Cursor、Gemini CLI 等都识别），趋势是把各家指令文件收敛到一处。本仓库用 AGENTS.md 薄壳指向 CLAUDE.md，方向一致。
- 真正拉开项目差距的不是「敢不敢直接改」，而是**有没有机制让文档和代码不脱节**：help-doc-sync 门禁、commitlint、「规范跟着代码走」约定已超过多数开源项目。
- 批量改 vs 小步改：删除错误事实、消除矛盾近乎纯收益（矛盾等于逼 AI 抛硬币）；**新增**规则才需要谨慎验证、宜小步。

## 二、失败归因四分法（日常纪律）

每次 code-review 或调试发现 AI 违规时，先归因再决定动不动文档：

1. **规则不存在** → 补文档（规范性条目要写到可照抄：格式示例 + 适用范围）。
2. **规则有歧义 / 矛盾** → 修文档；多份文档讲同一事实时指定唯一权威出处。
3. **规则在但 AI 无视** → 文档救不了，上机器门禁（lint / CI / git hook）。
4. **机器已强制** → 不需要测，也不需要重复写。

只有前两种才动文档。「每次失败都加一条规则」会让 CLAUDE.md 膨胀回刚减掉的样子。另注意区分**描述现状**与**规范要求**两类条目：规范性条目（如日志 tag `AppTag.<类名>`）与代码不符 ≠ 文档过时，可能只是规范超前于代码。

## 三、有效性验证的四个层级

业内目前没有公认的基准指标，可按成本从低到高逐层做：

### 第 0 层：事实回归审计

把审计发现清单对着当前 HEAD 重跑，确认全部消失、无新引入。文档版回归测试；局限是自审自改，独立视角更有说服力。

### 第 1 层：探针任务 A/B（最有信息量）

用 `git worktree` 检出改动前后的 commit，同一组探针任务各跑数遍（LLM 有随机性，单次不作数），对比行为差异。本仓库探针示例：

| 探针任务                    | 验证的规则                              | 期望行为                                                                    |
| --------------------------- | --------------------------------------- | --------------------------------------------------------------------------- |
| 给某个类加一行日志          | CLAUDE.md 日志 tag 规范                 | `private const val TAG = "AppTag.<类名>"`                                   |
| 新增 Repository 查询        | repository-rules / state-events §4.1.1  | 暴露 `Flow`，而非散装 suspend                                               |
| 跨组件发一次性事件          | live-event-bus-rules §3 + README 速查表 | View 侧 `LiveEventBus` / Compose 侧 `Channel<Event>`（UNLIMITED/CONFLATED） |
| View 系屏幕一次性任务       | coroutine-rules 规则 1                  | `execute` 链，而非裸 `viewModelScope.launch`                                |
| 提交一条格式不合规的 commit | commitlint + spotless 口径              | 知道 commitlint 硬拦、spotless 可选不阻塞、lint 是硬门禁                    |

### 第 2 层：用 code-review 当测量仪

code-review 的 Standards 轴（是否符合项目规范）报出的违规条数是滞后指标：清理前后各跑若干次真实 review 对比。同类违规减少 → 规范被读到并执行；没变 → 规范没进上下文或被无视，走四分法的第 3 种。

### 第 3 层：规则从散文迁移成钩子（终极形态）

能用机器管的规则别靠文档。commitlint、help-doc-sync、spotless、单测 CI 已是机器门禁；compose 规范的「机器项」若落成 `tools/lint-rules/`，那批规则的有效性就从「靠测」变成「保证」。**衡量文档有效性的终极方式，是持续把规则从散文迁移成钩子——文档里只留机器管不了的事。**

## 四、参考

- Claude Code 官方文档《Best practices for Claude Code》：<https://code.claude.com/docs/en/best-practices> —— CLAUDE.md 调优思路的现行权威出处（2025 年工程博客《Claude Code: Best practices for agentic coding》已 308 重定向至此）。要点：每行自问"删掉会不会导致犯错"、屡教不改多半是文件太长、只对反复被忽略的一条加 IMPORTANT、默认做对的事删掉或改写成 hook。
- 同站 Memory 章节《How Claude remembers your project》：<https://code.claude.com/docs/en/memory> —— CLAUDE.md 存放位置与加载顺序、@import 语法、单个文件建议 ≤200 行、`.claude/rules/` 按主题拆分并可用 paths 限定生效范围。
- AGENTS.md 开放标准：<https://agents.md>
- 相关工程文章《Effective context engineering for AI agents》：<https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents> —— 讲指令文件如何进入上下文、上下文预算与"注意力预算"问题，是 200 行建议的原理出处。
