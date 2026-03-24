# wave_tracker 使用说明

## 依赖与默认路径

- **wellen**：FST/VCD 解析  
- **clap**：CLI  

未指定 `-f/--file` 时，使用 `crate::core::default_wave_path()`：从 `wave_tracker` 的 manifest 目录向上两级到 `chip-dev`，再 `remu/target/trace.fst`。

## 命令行（摘要）

以下为常用项；**完整列表以 `wave_tracker --help` 为准**（随代码演进）。

| 类别 | 选项 | 作用 |
|------|------|------|
| 输入 | `-f`, `--file` | 波形文件 |
| 浏览 | `-l`, `--list-signals` | 列出信号名 |
| | `-g`, `--grep` | 名字子串过滤 |
| 单点 | `-t`, `--time` | 该时刻各匹配信号的值 |
| 扫描 | `--scan`, `--scan-end` | 时间窗内信号变化 |
| | `--filter-value`, `--filter-rd-index` | 与 `--scan` 联用的值过滤 |
| 专项 | `--bug-scan`, `--timeline`, `--prf-iq-mismatch`, `--deadlock`, `--deadlock-tail` | 各类 RTL 调试子命令 |
| 追踪 | `--trace-rob-id`, `--trace-p-rd`, `--trace-pc`, `--enq-match`, `--dispatch-lsq` 等 | rob_id / p_rd / PC / LSQ 等轨迹 |

## Crate 布局（`src/`）

- **`main`**：入口，解析参数并调用 `cli::run`  
- **`cli`**：`Args` + 调度逻辑  
- **`core`**：时间索引、默认路径、二进制/十六进制 PC 辅助、`snapshot_at` / `val_by_substring`、按时间窗迭代采样等  
- **`analysis`**：各分析函数（scan、deadlock、trace_* 等），偏 nzea 信号命名，可视为该项目的「调试配置」层  

Chisel 核心里 **PRF** 为独立模块 `frontend.Prf`（多口读、WBU 写、rename 分配时清 ready）；**bypass 合并**在 `PrfBypass`，于 `Core` 连接 ISU/IQ/commit 时组合，与波形工具无关。

库 crate 可被测试或其它工具 `use wave_tracker::...` 引用。

## 信号过滤提示

波形里层次名较长，用 `-g` 缩小范围即可，例如：

- `freeList`、`rmt`、`rob`、`iq`、`flush`、`commit`

具体信号名以 FST 为准；不同 RTL 版本命名可能略有差异。
