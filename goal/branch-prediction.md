# 分支预测：档位、结构与验收

Status: **M2a 已落地并验证，时序已完成二轮整形（2026-09-30）** — `BpuLevel.Bimodal`（PHT + BTB）实现完成，
`Off` 行为逐拍不变（不建表、不增 flop）；RAS 与 `Tage` 留待 M2b / M3。取指侧的携带契约（`Ifu` 产出
`(pc, next_pc, fall_through)`，经 `IbusUser` 请求携带、响应回送，一路带到 BRU）已在前一步落地——BP 就是"替换 `Ifu`
里那一个预测值"。首轮落地把三个大锥塞进了核内回路（后端的 `pc+4` 重建、一般化误预测比较、纯组合的
`bp_update` 训练总线），32i 最差路径 1.185 → 1.330；二轮把三者分别改为**取指携带顺延值、写回级比较、寄存的训练
总线**后，行为逐拍不变，专项锥全部消失（见§四）。**三：两张表迁入 `nzea_rtl.stdlib.SyncRam`**（2026-09-30）——
同步读、写更新、方向计数随取指携带回来（`BpUpdate.pht_count`），预测档行为逐拍不变，STA 见§四。**四：顺延值
改为调用方活值 `pcPlus4(pc)`**（2026-10-01）——`Bpu` 的去寄存化，+4 与 select 并行，行为逐位不变，32i 1.379 →
1.202、64i 1.495 → 1.436，见§四。

## 一、为什么是这套形状

- **档位 = 配置数据**。TAGE 结构里"表数"本身就是数据：0 张 tagged 表就是纯 base（即 bimodal），1 张就是带标签的
  gshare，N 张才是 TAGE。所以档位不需要多套实现，一套引擎按数据建表；`Off` 时表根本不存在，逻辑随之折叠。
  预测的"产生"全部在 `Bpu` 里：取指只把"pc 的下一值"递进去、把答案寄存——`Off` 的 `Bpu` 就是一条顺延加法器，
  取指回路对档位一无所知。
- **尺寸随档位显式携带**（`case Bimodal(phtEntries, btbEntries)`），选择写死在 `Main.scala` 一行（与 `bypass` 同规）。
  配置的定义随消费它的模块走：`BpuLevel` 放在 `nzea_core.config`（`BypassLevel` 同址），结构与表放在 `front/ifu/bp/`。
- **预测是提前一拍查的**（predict-ahead）：每拍拿"pc 的下一值"去查表（它同时就是 pc 的更新值）——表的读在
  该拍发出，答案下一拍从表自己的输出寄存器里出来，正好是该 pc（不变：`readAddr ≡ pc 的下一值`，无需 staleness
  处理）——答案既更新 pc、又随请求发给总线。表内部（SyncRam）的读寄存器就是这里的"寄存一拍"，取指回路里因此
  没有额外的预测寄存器，组合深度也不落在 pc→pc 的环上。
- **训练来自解析拍**（BRU，`uopFirst & isBru`）：in-order 下解析拍的操作数与 pc 都是架构正确的；flush 会清 `isex`，
  错路径指令不会被呈现给后端，因此解析事件天然干净（比 legacy 的"无投机污染"论断更强）。

## 二、档位（`BpuLevel`）与推荐数据

```scala
enum BpuLevel(val name: String):
  case Off extends BpuLevel("off")
  case Bimodal(phtEntries: Int, btbEntries: Int) extends BpuLevel("bimodal")
  // M3: case Tage(histories: Seq[Int], entries: Int, baseEntries: Int, btbEntries: Int)
```

| 档 | 结构 | flops 增量 | 面积增量（估） | 定位 |
|---|---|---|---|---|
| `Off` | 无 | 0 | 0 | A/B 基准；误预测 ≈ taken 率 |
| `Bimodal(128, 8)` | 128×2b PHT + BTB8 | ~0.7k | ~+4.5k（+21%） | PPA 优先；循环分支基本吃下 |
| `Tage(Seq(4,12,28), 32, 128, 8)` | base128 + 3×32×14b + BTB8 | ~2.1k | ~+13k（+60%） | 上线候选（M3 扫参定尺寸） |
| `Tage(Seq(4,10,22,46,94), 128, 128, 16)` | base128 + 5×128×15b + BTB16 | ~11k | ~+68k | 准确率上限参考 |

组件成本直觉（32i 标准单元，DFF ≈ 6.2 µm²/位）：PHT 2 flop/项；TAGE 表项 ~14 位/项；
**BTB ~57 位/项（valid 1 + tag 26 + target[31:2] 30）——最贵的单项，起步要小**；RAS ~31 位/项（M2b）。

## 三、Bimodal 档的设计

### 结构（`front/ifu/bp/`）

- `Pht`：pc 索引的 2-bit 饱和计数器表（`pc[indexBits+1:2]`），存储是 `SyncRam`（1R1W、同步读）：预测读随
  "pc 的下一值"发出，计数与预测位下一拍出来；更新是**写更新**——新值由解析时带回的取指计数值饱和推算
  （`BpUpdate.pht_count`），表不做读回来再写。代价是同槽别名分支同时在飞时各写各的取指快照，会丢一次增量
  （学得慢，不会预测错），见§六。
- `Btb`：`{tag, target[31:2]}` 条目存 `SyncRam`（同上），**valid 位留在触发器**（`RegInit(false)`）：它是必须
  从复位开始有定义的那一位，空表命中恒假，读数据里的 X 因此被挡在合成之外。读的命中比较和 valid 读取都用
  "上一拍发出的地址"寻址。写只在 taken 解析时发生。
- 合成预测：`phtTaken && btbHit ? btbTarget : fallThroughOf(addr)`（legacy 同款保守合成）；顺延值是调用方的活值
  `pcPlus4(pc)`（`Ifu` 侧算一次，与请求打包共用），+4 因此挂在 pc 寄存器旁、与重定向/握手控制并行，而不是排在
  选"pc 下一值"的 select 之后。安全性：条目只由 taken 分支写、且 `tag+index = 完整 pc`，
  所以"把非分支重定向走"不会发生——非分支永远 BTB miss，预测即顺延。

### 取指结构（`Ifu`，全档共用）

- 预测整体在 `Bpu` 内：`Ifu` 每拍把"pc 的下一值"递给它，它下一拍从自己的寄存器（表的读寄存器、顺延值寄存器）
  答出该 pc 的预测；`Ifu` 用这个答案更新 pc、填 user——取指回路里只有一个 mux，档位只决定答案是顺延还是表给的。
  顺延值寄存器复位 = `pcReset + 4`（首拍预测按顺延，直到表见过一次解析；表本身不复位，见下）。
- `npc = redirect.valid ? redirect.bits : (fire ? pred : pc)` —— 一个信号两个用途：pc 的更新值、查表的地址。
- `user = pack(pc, pred, pht_count, epoch)`；响应回送的 `next_pc` = `pred`，下游全部照旧。
- `Off` 档：`Bpu` 只把"地址的 +4"寄存输出（不建任何表），取指结构与预测档逐字相同——A/B 隔离的正是"表"本身。
  表不复位：条目随仿真器的初始化而空（两态模型下为零），空表即"不 taken、不命中"，与旧实现的复位态逐位一致。

### 误预测、顺延值与训练

- **顺延值随取指携带**：`Ifu` 在打包请求时用 `pcPlus4(pc)`（`nzea_rtl.stdlib.Arith`：Kogge-Stone AND 前缀，
  O(log n)；通用加法器的常数 +4 会被综合展开成 ~1.2ns 的串行链，实测）算好一条指令自己的 `pc + 4`，经
  `IbusUser` 的 `fall_through` 字段回送进 `FetchedInst`，随后一路带到 IS/EX。**任何后端阶段都不再重建 `pc + 4`**。
- **误预测比较在写回级**：`PathResult` 携带 `is_branch` 与 `pred_next_pc`（取指带下的预测），`Wbu` 的写回寄存器
  用 `is_branch & (next_pc =/= pred_next_pc)` 做比较——两端都是寄存器，比较器整体离开后端组合锥；冲洗周期数与
  首轮相同（完成拍 C 捕获、C+1 冲洗）。`Bru.mispredict` 只剩统计消费者（sim 档保留、yosys 档剪除）。
- **重定向目标 = `wb.next_pc`**（实际去向；预测 taken 而实际不 taken 时指回顺延值）。`Bru` 收到 `fallThrough` 输入：
  `nextPc = taken ? target : fallThrough`；`result.next_pc` 用一个 `isBru & taken` 的合并 mux，省一级。
- `Off` 档的语义不变：预测即顺延，`u.fall_through` 与 `u.next_pc` 同值，比较器折叠为"taken 即偏离"。

### 更新通道

- 新契约 `BpUpdate = {pc, taken, target, pht_count}`（`back/BpUpdate.scala`）：back 在 BRU 解析拍产出，**在
  `IntPath` 内寄存（含 valid）后再直连 front**（`Core` 里 `front.io.bp_update :<= back.io.bp_update`；中端不参与）。
  寄存的意义是把 PHT/BTB 整表的写译码从解析锥（尤其目标加法器）上摘下来——首轮纯组合连接时这条扇出把同族路径从
  ~1.17 ns 拉到 1.396 ns、并让 BTB 门控检查停在 1.30 ns；寄存后两者分别回到 ~1.37 以下与 ~0.75 ns。训练因此晚一
  拍落地，只有比 ~2 拍更紧的自环才能感知。
- `pht_count` 是同步读的代价：表在更新时读不回旧值（读口归预测），于是由取指读出、随 `IbusUser` 回送、沿
  `FetchedInst → IduOut → ExecSlot → IssueUop` 一路带进解析侧，作为"这次解析该把计数写成什么"的依据。

## 四、M2a 测量（2026-09-30，二轮整形后）

档位写在 `Main.scala` 一行（`val bpu = BpuLevel.Bimodal(64, 4)`；换 `Off` 即回基线）。表尺寸 A/B 见下。

| 项 | `Off` | `Bimodal(64, 4)`（当前 pin） | `Bimodal(128, 8)`（对照） |
|---|---|---|---|
| `branch_loop` | 391 拍 / 167 条 / 0.4271 / 误预测 31 | **251 / 0.6653 / 误预测 3** | 251 / 0.6653 / 误预测 3（逐位一致） |
| `dep_chain` | 138 拍 / 0.9638 | 138 / 0.9638 | 138 / 0.9638 |
| microbench `test` 32im | — | **0.6120 / 误预测 13.85%** | 0.6249 / 11.22% |
| microbench `test` 64im | — | 0.5301 / 误预测 18.96% | 0.5435 / 15.01% |
| hello_world 64im | — | difftest 通过 | difftest 通过（RV64 首次端到端） |
| STA 32i（2 ns 约束） | — | **1.329–1.338 ns**（三族并齐）/ 27462.40 µm² / 2350 flops | 1.392/1.393 ns / 31461.92 µm² / 2706 flops |
| STA 64i | — | **1.482–1.485 ns**（并齐） | 1.552 ns |

- **表尺寸 A/B（`(128, 8) → (64, 4)`）**：32i 最差 1.393 → 1.338 ns（−0.055），面积 −12.7%、flops −356（PHT/BTB
  各减半）；代价是命中率：microbench 32im IPC 0.6249 → 0.6120（误预测 11.22% → 13.85%）、64im 0.5435 → 0.5301
  （15.01% → 18.96%）。当前 pin 取 `(64, 4)`（频率余量优先）；IPC 优先时回 `(128, 8)` 只改 `Main.scala` 一行。
- **首轮（未整形）对比**：32i 最差 1.330 ns（`src_b` 族，锥内是后端重建的 `pc+4` 加法器）与 1.671 ns 64i（一般化误
  预测比较器）；二轮后这两族整体消失：32i 榜首换成两个长期存在的环（中端 ALU 执行→旁路、前端 PHT/BTB 查表），
  64i 榜首换成 ALU 环且**绝对值改善 0.12 ns**。BTB 门控检查 1.302r → ~0.75r。
- **同期修复的潜伏 64 位 bug**：`Arith.prefixAdder`/`pcPlus4` 与 `Div` 里 `(1 << width)` 是 Int 移位（宽度 ≥32
  时回绕成 0），64 位实例化的 Kogge-Stone 顶层掩码与除零全一值一直是错的——64i difftest 因此从未能跑通；改 BigInt
  后 64im 才首次全绿。
- 下一步的时序候选（都不影响功能）：中端执行→旁路环的 ALU 深度（加法器/比较器形状、旁路选择）；新出现的
  `bpu.fallThrough` 族见下方迁移节；`goal/core-baseline.md` 的基线口径不变。

### 存储迁移（2026-09-30，`SyncRam`）

PHT/BTB 的存储从触发器阵列迁到 `SyncRam`（1R1W）。行为逐位不变：`branch_loop` 251 拍 / 0.6653 / 误预测 3，
`dep_chain` 138 / 0.9638，microbench 32im 0.6119 / 13.85%、64im 0.5301 / 18.96%（与迁移前同一舍入口径），
hello_world 64im 与四条 difftest 全过。

| 项 | 迁移前 | 迁移后 |
|---|---|---|
| STA 32i 最差 | 1.329–1.338 ns（user 打包 / execute→bypass / commit 三族并齐） | **1.356–1.379 ns**（src_b 1.379 / 新增 `bpu.fallThrough` 1.376 / commit 1.373） |
| STA 64i 最差 | 1.482–1.485 ns（user 打包 / src_b 并齐） | **1.481–1.495 ns**（`bpu.fallThrough` 1.495 / src_a 1.492） |
| 面积 / DFF | 27462.40 µm² / 2350 | 27181.56 µm² / 2448 |

- 新增的 SyncRam 体随 dump 写入并在 `filelist.f` 里（`SyncRam_dataWidth2_depth64_…`、`SyncRam_dataWidth58_depth4_…`）。
- 本流程（yosys-sta）把 `$mem` 映成触发器，所以面积/时序看到的是"触发器版"的存储；SRAM 宏的收益要等宏流程。
- 32i 的 +0.04 ns 里，未改动的族（src_b、commit）也整体同步后移：ABC 全局重映射的效应；专属于迁移的是
  `bpu.fallThrough` 族：`下一值 select 链（≈0.79 ns，含 fire/redirect 的握手锥）→ Kogge-Stone +4（≈0.59 ns）→ 寄存器`，
  候选修法（预计算 target+4 / fallThrough+4 两枚寄存器，或先修 select 链）留在§六。500 MHz 目标 (2 ns) 不受影响。

复现命令：`just dump --target core --isa riscv32i --flow yosys`（+64i）→ `mill nzea_core.test` →
remu 探针与 microbench（命令同 `nzea_core/test/asm/branch_loop.S` 头注）→ `just sta --target core --isa riscv32i`（+64i）。

### 取指环整形（2026-10-01，顺延值的 +4 离开 select 之后）

`Bpu` 的顺延值寄存器删除：合成的默认臂改为调用方传入的活值 `pcPlus4(pc)`——恒等（旧寄存器每拍写的是"读地址的
+4"，而读地址就是 pc 寄存器的下一值，即下一拍的 pc），行为逐位不变；+4 从"选择 pc 下一值"的 select 之后，挪到
pc 寄存器旁与控制锥并行。

| 项 | 改前 | 改后 |
|---|---|---|
| STA 32i 最差 | 1.379 ns（`src_b` / `bpu.fallThrough` / commit 三族） | **1.202 ns**（commit / `src_b`；`bpu.fallThrough` 族退出前 5） |
| STA 64i 最差 | 1.495 ns（`bpu.fallThrough`×3 / `src_a` / `src_b`） | **1.436 ns**（`src_b`×4 / commit） |
| 面积 / DFF（32i） | 27181.56 µm² / 2448 | 27199.48 µm² / 2416 |
| 面积 / DFF（64i） | — | 52143.28 µm² / 4602 |

- 行为：`branch_loop` 251 / 0.6653 / 误预测 3、`dep_chain` 138 / 0.9638 逐位一致；microbench 32im 0.6120 /
  13.85%（记录口径 0.6119 / 13.85%）全 PASS；hello_world 64im 通过；`nzea_core.test` 全绿。
- 未改动的族也随 ABC 全图重映射移动（32i commit 1.373 → 1.202、64i `src` 1.492 → 1.436），方向有利。

## 五、分步与验收

| 步 | 内容 | 验收 |
|---|---|---|
| M2a | `Pht` + `Btb` + 通道 + BRU 改造 | ✅ 见上：Off 逐拍复现；Bimodal 误预测 31 → 3、microbench +51%、difftest 全过、两 ISA STA 达标 |
| M2b | RAS（decode 分类 `is_call/is_ret`、BTB 带分类位、pop 侧论证） | ret 密集探针 + microbench |
| M3 | `Tage`（多表 + provider/alternate + u 分配） | 四档 A/B（IPC / mispredict_rate / 面积 / WNS） |

每步固定口径：`just dump --target core --isa riscv32i --flow yosys`（+64i）→ `mill nzea_core.test` →
`dep_chain` / `branch_loop` 探针 → microbench difftest → `just sta`（面积与最差路径，报 BP 自身 flops）。

## 六、待定 / 取舍记录

- **同步读表的更新只能写**：`SyncRam` 的读口归预测，更新时读不回旧计数，所以计数随取指携带（`pht_count`）、
  更新写成饱和后的新值。同槽别名分支同时在飞时，各自写自己取指时的快照，会丢一次增量——学得慢，不会预测错；
  microbench 的误预测数与迁移前逐位一致，说明实际代价可忽略。要精确 RMW 就得给 PHT 第二条读口（`1R1RW`）
  加一个更新调度器，收益不值现在这个复杂度，留给 TAGE 时再说。
- **表不复位（4 态仿真）**：条目随仿真器初始化而空（verilator 两态 = 零，与旧复位态逐位一致）；4 态仿真下未写
  过的槽是 X，PHT 计数会一路 X 下去。当前没有 4 态消费者（iverilog 的 tile 未移植）；上 tile 仿真前需给 PHT
  加一次初始化（开机扫零一遍，或在体里加 `initial`），BTB 的 valid 触发器已经把 X 挡在合成之外。
- **迁移的时序账（已结清）**：`bpu.fallThrough` 族已由"顺延值取活值 `pcPlus4(pc)`"结清（2026-10-01，见§四）：
  +4 与 select 并行，行为逐位不变。剩下的取指侧候选是 `nextPc` 的 fire/redirect select 链本身（读地址仍要等
  锁/握手控制），尚未开工。
- 预测经 `IbusUser` 回送（无状态），user 宽度随字段增加（epoch + next_pc + fall_through + pc + pht_count）。
  顺延值在取指端组合算出（`pcPlus4(pc)` 直接进 user 打包，恒等于该请求的 pc+4；响应按同一字段回读），代价只落在
  "pc → 输出口"这条无约束路径上，不进任何寄存器回路。备选（已在二轮试过并回退）：把顺延值另寄存一拍或放中端
  现算——前者加重预测环的负载、后者把 ~1.2ns 的串行链摆进 IS/EX 的 D 锥，实测都不如现在。
- BTB 是成本大头：起步 8 项；命中率不足的代价只是 IPC（BRU 纠正），不伤正确性。
- 长历史边际收益（microbench 循环为主）待 M3 实测；循环预测器可能比多挂两张表更划算。
- **重定向目标不做当拍查表（试验，已回退）**：`lookup_pc` 去掉 redirect 项、`predNow` 兜底 `pcPlus4(redirect.bits)`——
  行为零代价（flush / 误预测逐位不变），但兜底的 `+4`（KS）比被摘掉的查表段还贵（实测 ≈1.0 ns），A 环
  1.337 → 1.40，净亏；重开的前提是先有一个「便宜的 R+4」（KS 重塑或与 BRU 的加法融合）。
- RAS 的 pop 侧：本核"每条架构指令恰好解析一次、恰好退休一次"，解析侧 pop 与提交侧 pop 等价（待 M2b 时论证并定稿）。