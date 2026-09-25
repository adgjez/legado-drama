# claude-superpowers（已归档的 superpowers 通用技能集）

本目录是原先挂在仓库根 `.claude/skills/` 的 superpowers 体系技能包（[obra/superpowers](https://github.com/obra/superpowers) 一键安装产物）。

## 现状（2026-09-10 调整后）

- **此处保留 17 个 superpowers 通用工作流技能**：brainstorming、code-review、dispatching-parallel-agents、executing-plans、finishing-a-development-branch、mcp-builder、receiving-code-review、requesting-code-review、subagent-driven-development、systematic-debugging、test-driven-development、using-git-worktrees、using-superpowers、verification-before-completion、workflow-runner、writing-plans、writing-skills。
- **7 个 `legado-*` 项目专属技能已于 2026-09-10 回归 `.claude/skills/`**（git mv 可回溯历史），它们只属于本项目、没有替代来源，归位到活动技能目录。
- **通用技能的职责已由用户级技能注册表（`.agents/skills`）及仓库内 `.claude/skills` 的镜像接管**（grill-with-docs / to-spec / to-tickets / research / tdd / diagnosing-bugs），详见 `.claude/skills/README.md`。

## 迁移信息

- 迁移时间：2026-09-10
- 迁移路径：`.claude/skills/**` → `docs/archive/skills/claude-superpowers/`（git mv，历史可回溯）。
- 需要取出某个技能时：`git mv docs/archive/skills/claude-superpowers/<技能名> <目的路径>` 即可；回归前请先评估其与 `.claude/skills`（镜像）或 `.agents/skills`（注册表）是否重复。