# DDR3 接入调试复盘 —— 48 小时的困难与反思

Status: **复盘记录** — 记录 DDR3 从"无法工作"到"稳定读写"的完整调试历程，提炼项目可改进点。作为后续优化的设计参考。

## 背景

目标：把 lxb_artix7 板载 DDR3（通过 MIG 7-series IP）作为 fabric 从设备（0x88000000）接入 nzea，写 memtest 验证读写。用户提供了 `21_ddr3_test/` Vivado 参考工程作为金标准。

最终结果：`Ddr3Adapter`（32-bit LiteBus ⇄ 128-bit MIG native UI）在连续 10 次 memtest、5 轮 load/reset 压力测试下全部通过。

**但整个过程耗时约 48 小时**（含多次上下文压缩、十几轮"dump→build→烧录→测试"硬件迭代循环），最终定位到两个叠加的根因。这篇文档记录困难与教训。

---

## 一、遇到的核心困难

### 困难 1：硬件迭代循环极慢（最致命的瓶颈）

每个"改代码→验证"周期需要：

```
just dump (15s) → just vivado-build (~7-15min) → just vivado-program (~1min) → probe load (几秒) → 看结果
```

**一次完整迭代 ≈ 10-20 分钟**。整个调试过程估计跑了 **15+ 轮硬件迭代**，仅 build 就耗去数小时。而且：

- build 被 `timeout_ms` 杀掉过（10 分钟不够，中途只完成 synth+place）
- 每次 dump 会删掉 `vivado/ila_capture_*.tcl`，需要重建
- ILA depth 16384 与 `ila_capture.tcl` 里 TRIGGER_POSITION 32768 不匹配（旧模板），直接跑会报错

**反思**：硬件迭代应该"仿真先行"（项目宪法第 4 条），但这次大量时间花在板级盲试上。**根本原因**：仿真环境（stub）与真实 MIG 行为差异太大，导致"仿真全绿、上板全红"，逼着回板级迭代。

### 困难 2：仿真 stub 与真实 MIG 的语义鸿沟（最大的认知陷阱）

这是**最深的坑**。我们的 Chisel testbench stub 是"理想 MIG"：

1. **单行存储 vs 多行 DDR**：最初 stub 只有一个 128-bit 寄存器。写 row1 会回绕覆盖 row0，导致测试"看起来"行错位——**误导了整个诊断方向**（一度以为 app_addr 编码错）。改成多行存储后才真相大白。

2. **wdf 拍数模型错误**：stub 最初假设"nCK_PER_CLK=2 → 128bit 分 2 拍送（每拍 64bit）"。这个假设来自交接摘要的早期实验，但**真实 MIG 的 `app_wdf_data` 外部接口是 128bit，每拍就是一个完整字**（`APP_DATA_WIDTH=256` 是 MIG 内部值，`nCK_PER_CLK=2` 的拆分发生在 MIG 内部）。stub 按错误模型实现，导致"仿真验证了错误的 2 拍协议"。

3. **mask 语义**：板级观察到"部分写不可靠"，stub 却按标准 mask 语义实现。最终选择 mask=0 全行写绕开，但早期被 stub 的"正确 mask"误导。

**教训**：**仿真 stub 必须严格对齐真实硬件的接口语义**，否则仿真通过反而给你虚假信心（false confidence），比没有仿真更危险——它会让你跳过应有的怀疑。

### 困难 3：跨时钟域握手（CDC）的隐蔽竞态

`Ddr3Adapter` 跨越两个时钟域：
- tile 域（CPU 时钟，100MHz）
- ui 域（MIG `ui_clk`，MMCM 生成，与 tile 时钟同频不同源）

问题：
1. **reqFire 单周期脉冲跨域会丢**（2-flop 同步器采样相位差）→ 改为同步 reqPending **电平** + 检测上升沿
2. **doneReq 回程**：1 拍自动清除的 done 脉冲会被返回同步器丢弃 → 改为电平握手（tile 域确认 reqPending 回落后才清）
3. **back-to-back 请求竞态**：上一请求的 done 回程同步链还没回落，下一请求就 fire → 新的 reqPending 上升沿丢失 → ui FSM 永停 sIdle。用 `handshakeInProg` 闩锁解决（doneSeen 后保持 reqReady=0，直到 ui 域确认 reqPending 回落）

**教训**：CDC 握手是"看起来对、时序上错"的重灾区。Chisel 仿真（同源时钟同相位）**测不出**真实的 MMCM 相位差。这类 bug 只能用 ILA 抓真实波形或靠严谨的时序推理。

### 困难 4：复位域分裂导致的"状态累积污染"（最隐蔽的根因之一）

`probe reset`/boot-load 只复位 tile 域（`cpuReset`），**不复位 MIG ui 域**（`ui_rst` 只跟随 MIG 内部复位/校准）。

表现：**每次重新 program（彻底复位）后一切正常，连续 load/reset 几次后开始随机 FAIL**。这个"干净→污染"的规律一开始极具迷惑性——看起来像偶发硬件问题，实际上：

- reset 打断在飞事务后，ui FSM 卡在中间态（sRdWait 等被吞的 READ）
- 或产生"过期 done"回程，污染 respValid → reqReady 永久为 0 → 第一个 store 死锁

修复：把 tile reset 2-flop 同步进 ui_clk，与 `ui_rst` OR，**ui 域跟随 tile reset**。

**教训**：多复位域设计必须显式梳理"每个域受哪些复位控制、reset 时在飞事务如何收尾"。平台有 `probe reset`（复位 CPU）、boot-load（复位 tile）、reprogram（复位一切）三种粒度，**适配器必须对每种粒度都正确**。

### 困难 5：MIG 地址编码的"半知半解"

`app_addr` 编码（16B 行号<<4 | 8B 半<<3）反复被怀疑又反复被"基础探针通过"验证。关键认知：

- MIG `BANK_ROW_COLUMN` 映射：col=app_addr[0:9]，row=[10:24]，bank=[25:27]
- 16bit DQ bl8：**一个 app_addr 命令访问 16B burst（128bit）**，col[2:0] 必须为 0，col[3] 选 8B 半
- 21_ddr3_test 例程 `app_addr + 'd8`（8B 粒度）**但它写 `{16{byte}}`（每字节相同）**——**从未暴露不同字节数据的写入**，所以它的"通过"不能证明字节级正确性

**教训**：参考工程的"通过"要问**它测了什么、没测什么**。21_ddr3_test 用重复字节模式 + 全 16B 满写，恰好绕开了我们踩的所有坑（部分写、半字节、mask）。

### 困难 6：ILA 调试的可用性不足（工具链短板）

- **ILA 触发配置反复失败**：`get_hw_probes` 查询语法在 batch 模式查不到 probe、TRIGGER_CONDITION=AND 时旧探针残留配置干扰、`-trigger_now` 需要先设 PROBES.FILE……在 Vivado TCL API 上浪费了大量时间
- **探针布局固定**：probe0-21 焊死在 Core.scala，调试中想加 `app_addr`/`wdf_data[63:0]` 探针必须改 RTL + 重新 build（又一轮 10 分钟）
- **VCD 导出稀疏**：ILA 只在信号变化时写 VCD，静态窗口看起来"只有几 ns"，容易误判

**教训**：ILA 探针应该像 pulse 的"事件文件"一样**按需可配**，而不是焊死在 RTL 里。每次改探针都要 7 分钟 build 是巨大的摩擦。

### 困难 7：UART 观察手段的干扰

- `probe load` 的 "Response:" 只读回极短窗口，慢程序的输出经常错过 → 误判"卡死"
- UART 打印本身会 backpressure（THR 满时总线阻塞），干扰时序
- 串口会换号（ttyUSB0↔ttyUSB1），probe 二进制路径要写绝对路径
- 多程序连续 load，DDR 里残留旧数据，读回"残留"误判为"写失败"

**教训**：板级观察手段（UART/ILA）的可靠性直接影响调试效率。UART 读回窗口短 + DDR 残留 + 串口漂移，三者叠加制造了大量假象。

### 困难 8：调试产物失控

48 小时里产生了 ~20 个临时 `.S` 汇编测试（ddr_a34/ddr_b3/ddr_diag/ddr_one/...）、多个 ILA tcl、`trans.py`、多个 probe 文件。**每个实验一个文件，命名随意，最终靠手工清理**。

**教训**：应该有一个"板级测试程序"的统一管理方式（比如按测试场景归类、自动生成 hex、自动清理），而不是每次手写 `.S` + `asm2hex.sh`。

---

## 二、根因总结（最终定位的两个叠加 bug）

### Bug 1：wdf 写数据拍数错误

- **错误认知**：`nCK_PER_CLK=2` → 128bit 写数据分 2 拍（每拍 64bit）
- **真相**：MIG 外部 `app_wdf_data` 是 128bit，**每拍一个完整字**；`nCK_PER_CLK=2` 的 64bit 拆分发生在 MIG 内部
- **修复**：`wdfWren` 从 2 拍改为 **1 拍**，`app_wdf_end := wdfWren`（每拍高，对齐 21_ddr3_test 的 `ddr3_wr_ctrl.v`）
- **影响**：这是"写不落盘、延迟读回初值"的真凶

### Bug 2：ui 域不跟随 tile reset

- **表现**：重新 program 后正常，连续 load/reset 后随机 FAIL（状态累积）
- **修复**：tile reset 同步进 ui_clk 域，ui 域跟随复位

### 关键实验证据链

1. **写后立即读对、延迟读错** → 排除固定地址/数据线问题，指向"写未落盘"
2. **重新 program 后一切正常** → 排除 RTL 逻辑错误，指向"状态累积/复位域"
3. **多行 stub 仿真全对** → 证明适配器逻辑在理想 MIG 下正确
4. **读 MIG 源码 `ui_wr_data.v` 确认 APP_DATA_WIDTH=256（内部）、外部 128bit** → 推翻 2 拍假设
5. **连续 10 次 memtest + 5 轮 reset 压力全过** → 两个修复叠加生效

---

## 三、项目可改进点（按优先级）

### P0：仿真与真实硬件的语义对齐

1. **MIG 行为模型 stub 必须精确**：数据位宽（128bit 外部/256bit 内部）、wdf 拍数、end 语义、mask 语义、多行存储、复位行为——每一项都要从 MIG 源码/文档确认，不能凭交接摘要或推测。
2. **为"硬件特有行为"建 stub 变体**：如 READ 吞命令（已有 `MigAppStubSwallow`）、写吞、复位打断。每个板级观察到的异常行为都应有对应的仿真复现能力。

### P0：降低硬件迭代成本

3. **ILA 探针按需可配**：参考 pulse 事件文件的做法，探针映射做成配置（比如 per-debug-session 的 probe map），避免改探针就要改 RTL + 重 build。当前 `IlaProbes.widths` 是单一事实源的方向是对的，但**探针接线**（Core.scala 里 probeN := ...）应该可注入。
4. **构建增量复用**：确认 Vivado 从 dcp checkpoint 恢复的机制能稳定复用（中断后重跑从 placed/routed dcp 继续），避免每次全量。
5. **`ila_capture.tcl` 模板参数化**：depth/TRIGGER_POSITION 应从 RTL 配置推导（像 `ila_config.json` 那样），消除手工改 tcl。

### P1：调试观测手段

6. **UART 读回窗口可配置**：`probe load` 支持 `--read-ms` 或持续 monitor，避免错过慢程序输出。
7. **DDR 状态可观测**：加一个"读任意 DDR 地址"的 probe 子命令（或 debug 程序模板），快速区分"残留"vs"写失败"。
8. **probe 串口自动发现**：按 USB VID/PID 或 by-id 软链接，减少 ttyUSB0/1 漂移。

### P1：CDC 与复位域设计规范

9. **CDC 握手模式化**：电平握手 + 上升沿检测 + 回程确认，应该提炼成 `nzea_rtl` 的通用组件（类似 `Pipe`/`Replacement`），而不是每个适配器手写。
10. **复位域矩阵**：每个跨域模块应显式声明"哪些复位控制哪些域、reset 时在飞事务如何收尾"。Platform 已有 uart（bootReset 域）/finisher+clint（cpuReset 域）的先例，DDR 适配器的 ui 域复位应纳入同一张表。

### P2：参考工程与文档

11. **参考工程的"覆盖盲区"要记录**：21_ddr3_test 只测 `{16{byte}}` 满写，从不测部分写/半字节——这类盲区应该写在 README 里，避免后人误以为"参考工程通过 = 一切正常"。
12. **MIG 关键结论存档**：`app_wdf_data` 外部 128bit、`app_addr` 编码、`nCK_PER_CLK` 内部化、mask=0 全行写策略——这些是踩坑换来的权威事实，应该集中存档（比如 `ddr3_ref/README.md` 扩展），而不是散落在代码注释和交接摘要里。

### P2：调试产物管理

13. **板级测试程序统一管理**：一个 `nzea_sim/sim/tile/` 下的测试程序注册表（场景名 → .S → hex 自动生成），支持按场景跑 + 一键清理。

---

## 四、如果重来一次，会怎么做

1. **第一步就读 MIG 源码**（`ui_wr_data.v`/`ui_cmd.v` 在 21_ddr3_test 的 IP 生成产物里就有），确认 `app_wdf_data` 位宽、end 语义、地址映射——**这能直接避免 2 拍假设和地址编码的反复怀疑**。
2. **先写"真实语义"的 stub**（多行 + 128bit 单拍 + 可吞读），再写适配器——仿真一次性到位。
3. **板级验证用"可区分写/读"的测试**（写后立即读 vs 延迟读、fresh 地址 vs 残留地址），而不是盲跑 memtest。
4. **复位域矩阵先画出来**，DDR 适配器第一时间把 ui 域纳入 cpuReset 控制。
5. **ILA 探针一次配全**（app_addr、wdf_data 全 128bit、wdf_end），避免反复 build。
