# 最近 24 小时改动的不优雅/不完备候选清单

Status: **待处理候选** — 从最近 24 小时的提交（`1923b98`→`9f8cebc`）中观察到的、可能不够优雅或尚未完成的设计问题。按价值/风险分级，供后续挑选处理。

## C1. `rdTimeout` 重试逻辑——疑点最大的死代码候选

位置：`Ddr3Adapter.scala:291-299, 362-368`

板上 400/333 MHz 都观察到"吞读"才加的。但 postmortem 最终根因只有两个：Bug1（wdf 拍数）+ Bug2（ui 域不复位）。**Bug2 修好后，"吞读"是否还是独立现象，从未验证。**

现在有 cosim 了，可以做决定性实验：在风暴场景下禁掉重试，看是否仍能恢复。这是"删一段生产逻辑"的候选，收益最高。

- 若能删：简化 `sRdWait` 的转移路径，去掉一条必须被测试覆盖的状态转移
- 若不能删：首次在仿真里拥有可复现的"吞读"，重试从"猜测的补丁"变成"有依据的机制"

## C2. `Ddr3AdapterDebug` 20 个字段，11 个已被 firtool DCE

位置：`Ddr3Adapter.scala:14-50`

在 G3 里实测过，生成的 `.sv` 只有 11 个 `io_debug_*` 端口，另一半被 DCE 掉了——它们在板上**根本没有被 ILA 用**。cosim 之后协议调试不需要它们，ILA 只用看物理层。这正对应 `goal/ddr3-debug-port-removal.md`。

**空接口是最大的不优雅。** 一个被 DCE 掉一半的端口说明它已经不是设计意图的一部分。

## C3. 板级构建脚本的 DDR3 特判

位置：`vivado-project.nu:47-75`

5 个硬编码替换 + 隐式存在性开关 + RTL 位宽知识泄漏。已在 `goal/board-specific-build-hooks.md` 里记录为问题，未解决。**已知、未做**，不重复展开。

## C4. `vivado-sim.nu` 的 MIG 文件发现——`find` 会漏文件

位置：`vivado-sim.nu`

用 `find user_design/rtl -name "*.v"`。**`find` 不递归符号链接**——如果 Vivado 生成物里有符号链接的 RTL 文件会被漏掉。目前跑通了（67 个文件），但这属于"碰巧够用"。

更稳的是直接用 vendor 的 `xsim_files.prj` 当权威列表（它精确列出了该编哪些文件），而不是自己 glob。

## C5. wrapper 判定块在 `initial` 里，但依赖状态机已推进

位置：`nzea_ddr3_sim_top.v:362-370`

`wait(done)` 是**采样-based**，依赖测试 FSM 已经推进到 done。当前时序下碰巧先走到 done 再 `wait`，但如果 FSM 初始化晚或时序变了，会出现竞态——看门狗 40us 可能比 FSM 先触发。

应改成"采样到 done 再判定"（比如时钟沿同步），或让 FSM 在复位后立刻能推进。

## C6. `mergedFrom` 手写字节合并

位置：`Ddr3Adapter.scala:251-266`

16 个字节逐个 `Mux` + 手写 `Cat` 倒序。可读性差，容易错。可以用 Vec 操作重写。但**这是验证过的正确代码**，重写有回归风险——优先级低，不改也行。

## C7. `respWordFrom` / `slotIdx` / `halfSel` 三处地址拆解

位置：`Ddr3Adapter.scala:242-279`

地址位操作散落在三个地方（slotIdx、halfSel、mergedFrom 里的 inSlot），靠注释解释。可以集中成一个地址映射 helper，或像 C6 一样保持现状。

## C8. ILA 22 个探针，很多现在是死配置

位置：`IlaProbes.scala` + `Core.scala`

probe4-11/14/16/17 全来自 `Ddr3AdapterDebug`（C2），cosim 之后只剩 probe0-3（板级 IO）、probe12/13/15/18-21（MIG 物理层）还有用。应该按 C2 一起收缩，ILA 深度还能提上去。

## C9. cosim 的 `Ddr3Subsystem` 时钟/复位被 firtool 消掉

位置：`nzea_ddr3_sim_top.v`

生成 SV 里 `Ddr3Subsystem` 只剩 `io_rst_n/io_clk_200m`，内部时钟复位被优化。目前靠"它是纯连线"这个假设，注释里也写了"don't connect any"。**这个假设没有断言保护**——如果哪天 `Ddr3Subsystem` 内部加了寄存器，这个 cosim 会静默坏掉。

可以加一个顶层 `always @(posedge clock)` 探测它是否真的没被驱动，或对"内部无状态"加断言。

## C10. 测试 FSM 是手工 Verilog 状态机

位置：`nzea_ddr3_sim_top.v:283-351`

8 状态手工 FSM + 手工 `passbits`。可读性还行，但和 Scala 的 `Ddr3TestCore` 重复了（后者刚被删）。可以做成参数化（`NUM_TRANSACTIONS`、地址/数据表），或抽成共享 helper——但**cosim 已经验证过**，重写有风险，优先级低。

## C11. `vivado-sim.nu` 缺超时保护

位置：`vivado-sim.nu`

2.5 分钟仿真如果卡死，整个 recipe 无超时，会一直挂。外层的 `timeout_ms` 是 shell 的，脚本自身没有看门狗。建议脚本里加个 wall-clock 超时（比如 `timeout 600`）。

## C12. 跨仓库依赖未声明

位置：remu

remu 的 dev shell 需要 zlib/lz4，nzea 的 flake 里没有。用 nzea 的 shell 跑 remu 链接会失败。**remu 作为依赖的环境要求没有文档化。** 可在 nzea 侧加个"如何跑 remu difftest"的说明，或写进 goal。

---

## 优先级建议

- **高价值（值得动手）**：C1（rdTimeout 去留实验）、C2+C8（debug 端口 + ILA 收缩）、C4（文件发现改权威列表）、C11（超时保护）
- **中**：C5（wrapper 竞态）、C9（时钟复位假设断言）
- **低/保持**：C3（已有 goal）、C6/C7/C10（验证过的代码，重写有回归风险，除非有具体需求）
