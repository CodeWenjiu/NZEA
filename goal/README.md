# goal/ 索引与生命周期规则

Status: **规则已生效（2026-09-14）** — 本文件是本目录的索引和执行规则。2026-10-13 精简（删 `roadmap.md` / `stat-schema-v2.md` / `RV64-ABI.md` / `RV64I-APP-TARGET.md`）后，goal/ 只保留三类文件。

迁移状态（2026-10-13）：重写树已全部落地（`nzea_rtl` / `nzea_core` / `nzea_top` / `nzea_cli` / `nzea_tile` / `nzea_cache` / `nzea_device` / `nzea_fpga`），核心现状见 `core-baseline.md`。旧实现只在 tag `legacy-baseline` 中保留（`legacy/` 目录已删除）。

## 文件分类与生命周期

| 类型 | 目录内位置 | 允许状态 | 状态如何转移 | 过期处理 |
|---|---|---|---|---|
| 设计与状态 | `core-baseline.md`（含路线图节）、`stat-schema.md`、`branch-prediction.md`、`research-plan.md`、`latency-calibration.md` | 与实现一致 | 实现变化时在同一个提交里更新 | 随实现演进，不是历史记录 |
| 复盘/知识归档 | `ddr3-debug-postmortem.md` | 只读历史 | 不再追加新任务；新发现追加为章节 | 永不删除（历史证据） |
| 规则/索引 | `README.md` | 只读 | 随目录实际变化更新 | — |

## 原则

1. **goal/ 不是文档库**，是"接下来要做什么"。不单设任务清单文件：当前/下一步任务写在 `core-baseline.md` 的路线图节；任务一旦不再是"接下来要做什么"，随实现落到提交信息。
2. **每条任务必须可执行**：有位置（文件:行号或模块）、有验收标准。写不清验收标准就写开放问题，不写决定。
3. **状态必须诚实**：暂缓的条目明确写触发条件（如"第二块板"），不写"暂缓"二字就搁置。
4. **禁止引用不存在的文件**：引用先查存在性（历史教训：`config-aggregation.md` 被删后仍被引用，见旧 `debug-tooling-gaps.md` G7/G8）。
5. AGENTS.md 的"用到再说"原则适用于 `.pulse` 事件文件，不适用于本目录；本目录的活性靠每次提交前扫一眼位图（下方表格）维持。

## 当前文件

| 文件 | 类型 | 状态 |
|---|---|---|
| `README.md` | 规则/索引 | 生效中 |
| `core-baseline.md` | 设计与状态 | 基线现状与路线图（随实现同步） |
| `stat-schema.md` | 设计与状态 | 性能数据规范（remu 侧交接；双方已实现） |
| `branch-prediction.md` | 设计与状态 | BP 档位/结构/验收（M2a 已落地，RAS/TAGE 待续） |
| `research-plan.md` | 设计与状态 | 研究方向、任务与验收、延迟参数出处表（草案，待三个开放问题定题） |
| `latency-calibration.md` | 设计与状态 | A 线交付：参数出处、两层模型实测、敏感度扫描、`timeHz` 选项 |
| `ddr3-debug-postmortem.md` | 复盘/知识归档 | 只读历史 |

## 已删除/已合并的历史文件（勿再引用）

| 文件 | 去向 |
|---|---|
| `recent-changes-review.md`（C1-C12） | 全部处理完毕，删除；C2 决策"保留 debug 端口"见提交 `202e31f` |
| `pulse-widths-and-change-stats.md` | 已实现于 `08a2ca9`/`b61976c`，删除 |
| `ddr3-debug-port-removal.md` | C8（`202e31f`）已收缩 ILA；debug 端口因单测依赖保留——决策关闭 |
| `ddr3-intermittent-hunt.md` | 排查已收尾（A/B 组保留、C/D 组删除）；实验事实 F1-F13 归档进 `ddr3-debug-postmortem.md` 附录 |
| `debug-tooling-gaps.md`（G1-G8） | G1/G2/G8 已解决；未解决项 G3-G7 曾移入 `roadmap.md`（2026-10-13 随其删除） |
| `board-specific-build-hooks.md`（P1-P6） | 内容曾移入 `roadmap.md`（2026-10-13 随其删除） |
| `wave-mcp-lessons.md`（W1-W5） | W1-W3/W5 已实现；内容曾移入 `roadmap.md`（2026-10-13 随其删除） |
| `roadmap.md` | 全部搁置且引用全是 legacy 语境；唯一像活的残留（pulse 驱动链追踪）无需求驱动 → 整体删除（历史在 git） |
| `stat-schema-v2.md` | schema v2 提案已落地（`stat-schema.md` §4/§11），删除 |
| `RV64-ABI.md`、`RV64I-APP-TARGET.md` | RV64 跨仓交接；remu 已支持 RV64，删除 |
