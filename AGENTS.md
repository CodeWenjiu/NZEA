# 仓库指南

## 项目结构与模块组织

| 模块 | 用途 |
|--------|---------|
| `nzea_rtl/src` | 共享 RTL 工具（FabricBus、LiteBus、crossbar、arbiter、Pipe、MuxTree） |
| `nzea_device/src` | 可复用设备 IP（UART、定时器等），不依赖任何业务模块 |
| `nzea_core/src` | 核心流水线：前端（IFU/IDU/ISU/RAT/PRF/CSR/BP）、后端（integer/V/NNU/LSU）、退休（ROB/Commit/WBU） |
| `nzea_config/src` | 共享配置模型：`NzeaConfig`（全局生成选项）和 `CoreConfig`（微架构/ISA） |
| `nzea_tile/src` | Tile 级 SoC 封装（NzeaTile + FabricBus crossbar + 平台设备） |
| `nzea_fpga/src` | FPGA 板级包装：板级顶层、引脚约束、综合后处理 |
| `nzea_cli/src` | CLI 入口；解析参数并分发到 `CoreElaborate`、`TileElaborate` 或 `FpgaElaborate` |
| `nzea_sim/src` | 仿真层模块，为 tile/fpga 生成独立仿真 RTL + TB，输出到 `build/sim/` |
| `wave_tracker/` | 独立的 Rust CLI，用于 FST/VCD 波形分析和 RTL 级调试 |

### 依赖方向
```mermaid
flowchart TD
    rtl[nzea_rtl] --> device[nzea_device]
    rtl --> core[nzea_core]
    core --> config[nzea_config]
    core --> tile[nzea_tile]
    config --> tile
    rtl --> tile
    device --> tile
    device --> fpga[nzea_fpga]
    tile --> fpga
    core --> cli[nzea_cli]
    tile --> cli
    config --> cli
    fpga --> cli
    core --> sim[nzea_sim]
    tile --> sim
    config --> sim
    fpga --> sim
```
`nzea_cli` 依赖 `nzea_core`、`nzea_tile`、`nzea_config`、`nzea_fpga` 用于参数解析和目标分发。
`nzea_config` 仅依赖 `nzea_core` 中的 `CoreConfig`。

### 设计原则
1. 配置集中在 `nzea_config` 中，避免重复的 CLI 解析逻辑。
2. 保持作用域明确：将 `config.core` 传入 core/tile 硬件模块；将非核心流程选项保留在顶层 `NzeaConfig` 中。
3. 将 CLI 关注点与硬件生成分离，使生成过程能被测试和工具复用。
4. 优先使用职责明确的小模块，而非多功能大文件。
5. 版本常量（`scalaV`、`chiselV` 等）在 `build.mill` 中统一定义，各模块复用。

## 工作基线
构建或验证前先执行 `nix develop`。该 flake 锁定 `mill`、`scalafmt`、`yosys`、`ieda`、JDK 和 Rust nightly，并导出 `PDK_PATH`。

## 构建、测试和开发命令
优先使用 `justfile` 而非临时命令。

- `just init`：安装 BSP 元数据。
- `just dump <args>`：生成 RTL 到 `build/<target>/<platform>/<isa>/<sim|sta>/`。
- `just synth <args>`：生成综合就绪 RTL 并运行综合。
- `just sta <args>`：综合 + STA；需要 `PDK_PATH`。
- `just clean-all`：清理 `build/` 和 Mill 缓存。
- `mill nzea_core.compile` / `mill nzea_tile.compile`：快速编译检查。

### 运行测试
- `just test <module>`：运行模块全部测试（默认 `nzea_rtl`）。
- `just test-match <module> <pattern>`：类名匹配 pattern 的套件，支持通配符或精确类名。
- `mill nzea_core.test` / `mill nzea_rtl.test`：直接通过 Mill 运行。

测试源码位于 `nzea_core/test/src` 和 `nzea_rtl/test/src`，命名应具有描述性（如 `VectorBackendTest.scala`）。修改综合或 STA 流程后附上具体命令和报告路径。

### Wave Tracker
- `cd wave_tracker && cargo run --release -- --help`
- `cd wave_tracker && cargo test` / `cargo clippy` / `cargo fmt --check`

### 仿真（iverilog）
- `just iv tile <platform> <isa> [hex] [boot] [wave]`：tile 四态仿真。
- `just iv fpga <platform> <isa>`：FPGA 仿真。
- 输出：`build/sim/<target>/<platform>/<isa>/hw/iverilog/tb.{vvp,fst}`
- 测试平台位于 `nzea_sim/sim/`。使用 `--sim false` 生成的 RTL（暴露总线 IO，行为总线模型通过 `$readmemh` 加载内存）。

**Chisel TB 规范**：必须有 `io.result` 锚定输出 + `dontTouch` 关键信号防 DCE；companion object 提供 `emitWrapper(outDir)` 写 `initial`/`timescale` wrapper；`BlackBoxInline` 不能做顶层；tile TB 保持 Verilog（CIRCT 重命名 RAM 路径不兼容 direct boot）。

调试死锁仿真 → 加载 skill `iverilog-debug`。

## 编码风格与命名规范
遵循文件现有风格，不要重新格式化无关代码。Scala：类/对象/模块 `PascalCase`，val/方法 `camelCase`。Rust：`snake_case` 函数，`CamelCase` 类型。注释和文档仅英文。优先小模块，注释解释意图或风险。

**格式化**：Scala 用 `scalafmt`（`.scalafmt.conf`），Rust 用 `cargo fmt` + `cargo clippy`。

## 仓库特定规则
- 不要解压 JAR/归档到仓库目录；用 `jar tf` 或解压到 `/tmp`。
- 注释和文档仅英文。
- 禁止重新引入已删除的命令（如 `just run`）。
- 修改综合或 STA 脚本时，保持命令示例与 `justfile` 一致。

### 解码与 Chisel 注意事项
- `DecodeTable.decode(inst)` 的 Espresso 失败回退 QMC 是预期行为，生成 RTL 有效。
- 非字面量 `UInt` → `ChiselEnum` 警告：将字段定义为 `UInt(enum.getWidth.W)`，使用 `EnumType.safe(...)` 转换。
- Chisel 多路选择规范 → 加载 skill `chisel-mux-select`。

## 提交与 Pull Request 指南
优先 `类型: 简洁摘要`（如 `feat: nnu`、`fix: DIV pre path`）。PR 应说明受影响范围、列出验证命令、链接相关问题。修改影响 RTL/时序/调试输出时附报告片段或截图。

## Agent 使用规范
- 调用 `just`/`mill`/`yosys`/`nextpnr-*`/`nu` 等依赖 Nix 的命令时，必须通过 `nix develop --command bash -c '...'` 启动。用户在自己终端不受此限制。
- 修改 Scala/Chisel 代码后，必须运行 `nix develop --command bash -c 'just dump --target tile --platform hellofpga --isa riscv32im --sim false'` 验证编译和生成通过。
- Mill 命令始终添加 `--no-server`。
- 大输出命令（`just dump`、`mill *.run`）设置 `timeout_ms`（推荐 300000 ms）。
- 构建验证一次通过即足够，不要重复运行"确认"。
- 向现有系统添加参数时，只需在调用链中追加参数；超过 3 处编辑说明方向错了。不要修改无关代码或重写运行器。
