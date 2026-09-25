# Git Commit 规范与 Hook 配置 — 详细参考

> AI 在需要写 commit message、排查 hook 不生效或回答 commit 规范相关问题时读此文件。
> 日常开发只需记住 CLAUDE.md 里那段摘要即可。本文件由原 commit-spec.md 与 setup-guide.md、《新人快速使用git-hook.md》合并而成。

---

## 1. 格式

```
<type>(<scope>): <subject>

<body>

<footer>
```

---

## 2. type 类型表

| type       | 含义                     | 示例场景                 |
| ---------- | ------------------------ | ------------------------ |
| `feat`     | 新功能                   | 添加用户注册模块         |
| `fix`      | 缺陷修复                 | 修复登录页白屏问题       |
| `docs`     | 文档变更                 | 更新 API 接口文档        |
| `style`    | 代码格式（不影响逻辑）   | 调整缩进、补充分号       |
| `refactor` | 重构（非新功能、非修复） | 拆分过长的服务类         |
| `perf`     | 性能优化                 | 优化首页列表查询速度     |
| `test`     | 测试相关                 | 补充用户模块单元测试     |
| `chore`    | 构建/工具/依赖变更       | 升级 Gradle 到 8.5       |
| `ci`       | 持续集成配置             | 修改 GitHub Actions 流程 |
| `revert`   | 回滚提交                 | 回滚 v1.2.0 的登录重构   |

---

## 3. 各字段规则

### type

- 必填，从上表中选取；全小写（`Feat` 会被拦截）

### scope

- 选填，表示影响范围，用中文模块名（commitlint 已关闭 `subject-case`，中文不会报错）
- 示例：`用户模块`、`支付`、`列表页`

### subject

- 必填，中文简述，动宾短语，≤ 100 字符，不加句号结尾
- 不要写「修改了代码」这种无意义描述

### body

- 选填，说明变更动机、技术方案、影响范围
- 每行不超过 200 字符（警告级），与标题之间空一行

### footer

- 关联 Issue：`Closes #128`、`Refs #129`
- Breaking Change：见 §4

---

## 4. Breaking Change 标注

两种方式：

**方式一：footer 标注**

```
feat(接口): 重构用户信息返回结构

将用户接口返回的扁平结构改为嵌套结构，前端需同步调整字段取值路径。

BREAKING CHANGE: /api/user/info 返回结构变更
- avatar 字段移入 profile 对象
- 移除已废弃的 nickname 字段
```

**方式二：type 后加 `!`**

```
feat(接口)!: 重构用户信息返回结构
```

必须标注的场景：数据库表结构变更、公共 API 参数/返回值变更、配置文件格式变更。

---

## 5. 好示例

```
feat(用户模块): 添加手机号一键登录功能
fix(支付): 修复微信支付回调签名验证失败的问题
perf(列表页): 优化大数据量表格的虚拟滚动渲染
refactor(网关): 将单体网关拆分为独立微服务
docs(api): 更新用户接口说明
chore(构建): 升级 Gradle 到 8.5
ci: 修改 GitHub Actions matrix strategy
```

---

## 6. 避免的写法

| 错误写法                                    | 问题                        |
| ------------------------------------------- | --------------------------- |
| `fix: 修了一个 bug`                         | 太笼统，无意义              |
| `feat: 更新代码`                            | 无意义                      |
| `chore: 改了点东西`                         | 无意义                      |
| `bugfix(订单): ...`                         | type 不在白名单，应用 `fix` |
| `Feat(用户模块): ...`                       | 首字母大写，应用 `feat`     |
| `feat 用户模块 xxx`                         | 缺冒号分隔符                |
| `fix():`                                    | subject 为空                |
| `fix(用户模块): [超过100字符的超长描述...]` | subject 超长                |

真实被拦案例见 [invalid-commit-examples.md](./invalid-commit-examples.md)。

---

## 7. 中英文混排

中文与英文/数字之间加一个空格：

```
✅ 添加 Redis 缓存
✅ 升级 Gradle 到 8.5
✅ 使用 OkHttp 5.3.2
❌ 添加Redis缓存
❌ 升级Gradle到8.5
```

---

## 8. 交互式提交

```bash
npm run commit
```

等同于 `npx cz`，弹出问答引导（type → scope → subject → body → breaking → issue），自动拼出合规消息，不容易出错。

---

## 9. 组件分工与钩子机制

| 组件                       | 角色                                                                         |
| -------------------------- | ---------------------------------------------------------------------------- |
| **husky**                  | git 钩子管理器。把 `.husky/` 下的脚本注册到 `.git/hooks/`，git 操作自动触发  |
| **commitlint**             | 提交消息校验器（规则在 `.commitlintrc.js`）                                  |
| **cz / commitizen**        | 交互式提交引导（`npm run commit`）                                           |
| **lint-staged + prettier** | pre-commit 对暂存的 js/ts/jsx/tsx/vue/md 跑格式化                            |
| **help-doc-sync**          | pre-commit 校验受管代码区域与帮助文档的同步（`docs/help-doc-sync/map.json`） |
| **conventional-changelog** | 从 git 历史自动生成 CHANGELOG.md                                             |

### 钩子文件

```
.husky/
├── commit-msg    # npx --no -- commitlint --edit "$1"（校验提交消息）
└── pre-commit    # npx lint-staged + node scripts/help-doc-sync.mjs（两步）
```

### 触发流程

```
git commit
  ↓
husky 触发 pre-commit（lint-staged 格式化 + help-doc-sync 校验）
  ↓
husky 触发 commit-msg 钩子 → commitlint 校验消息
  ↓
合规 → 提交成功；不合规 → 报错并拒绝提交
```

### 生效条件（新人必读）

**必须跑过 `npm install`**。`package.json` 中 `"prepare": "husky"` 是 npm 生命周期脚本，`npm install` 结束后自动运行，把 `.husky/` 下的钩子注册到 `.git/hooks/`，不需要任何额外配置。

```
clone / fork 仓库 → npm install → prepare 自动执行 → 钩子接上 → git commit 自动拦截
```

clone 之后直接 `git commit`、不装依赖 → 钩子文件在但未注册 → 不会拦截。验证方法：装完依赖后跑一条 `git commit -m "修了个 bug"`，被拦即生效。

`.husky/` 脚本需要可执行权限：Windows clone 一般没问题；个别环境（如 SSH 拉取）可能丢可执行位，husky v9 会自动兼容修复。

### 临时跳过

```bash
git commit --no-verify -m "..."    # 仅限紧急修复
```

---

## 10. commitlint 规则配置要点（`.commitlintrc.js`）

```javascript
'type-enum': [2, 'always', ['feat','fix','docs','style','refactor','perf','test','chore','ci','revert']],
'type-case': [2, 'always', 'lower-case'],        // type 必须全小写
'type-empty': [2, 'never'],                       // type 必填
'subject-empty': [2, 'never'],                    // subject 必填
'subject-max-length': [2, 'always', 100],         // subject ≤100 字符
'subject-case': [0],                              // 关闭大小写限制（允许中文）
'header-max-length': [2, 'always', 120],          // 整行 ≤120 字符
'body-max-line-length': [1, 'always', 200],       // body 每行 ≤200 字符（警告级）
'footer-max-line-length': [1, 'always', 200],     // footer 每行 ≤200 字符（警告级）
```

> `[2, ...]` = error，拒绝提交；`[1, ...]` = warning，仅提示；`[0]` = 关闭。
> `.commitlintrc.js` 的 `ignores` 精确放行了钩子生效前的历史遗留裸「更新日志」提交（CI 按整分支校验时需要）。

---

## 11. CHANGELOG

```bash
npm run changelog       # 最近一次版本
npm run changelog:all   # 所有历史版本
npm run release         # 发版（版本号 + changelog + tag）
```

按 type 分组显示：feat → 新功能、fix → 缺陷修复、perf → 性能优化、refactor → 重构、docs → 文档、test → 测试；`chore` / `ci` / `style` 不显示。

---

## 12. 日常使用与历史改写

```bash
npm run commit                                   # 推荐：交互式
git commit -m "fix(支付): 修复回调签名验证失败"   # 手动
git commit --amend -m "fix(登录): 修复白屏问题"   # 补充/修改上一条（同样过校验）
```

修复更早的历史提交消息需要 `git rebase -i HEAD~N`（把 `pick` 改成 `reword`），改完可 `npm run changelog` 重新生成。

> **AI 注意**：`--amend` 与 `rebase` 属于改写历史的危险操作，遵循 CLAUDE.md「AI 与危险 git 命令」约定——AI 一律不得自动执行，必须先取得用户明确授权。

---

## 13. 常见问题

**Q: 中英文混排时空格怎么处理？**
A: 中文与英文/数字之间加一个空格，如「添加 Redis 缓存」「升级 Gradle 到 8.5」。

**Q: scope 用中文还是英文？**
A: 推荐中文（可读性好）。commitlint 已关闭 `subject-case`，不会因中文报错。

**Q: 多人协作时如何保证规范一致？**
A: 靠工具而非靠自觉。husky + commitlint 配置在仓库中，clone 后跑一次 `npm install` 即自动生效，不合规的提交直接被拦。

**Q: node_modules 会被提交吗？**
A: 不会，`.gitignore` 已排除。

---

## 14. 相关文件

```
docs/git-hook/
├── commit-spec.md               # 本文件 — 规范 + Hook 配置
└── invalid-commit-examples.md   # 真实被拦实例（含被拦原因）

项目根目录：
├── .husky/                      # git 钩子脚本（commit-msg / pre-commit）
├── .commitlintrc.js             # commitlint 规则配置
├── package.json                 # 依赖 + 脚本 + lint-staged 配置
├── scripts/help-doc-sync.mjs    # 帮助文档同步门禁脚本
└── CHANGELOG.md                 # 自动生成的变更日志
```
