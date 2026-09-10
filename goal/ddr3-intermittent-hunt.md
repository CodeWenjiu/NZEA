# DDR3 间歇性失败排查 —— 长期任务记录（防上下文压缩遗忘）

Status: **进行中（长期）** — 用户指令：**随意发挥、随便猜想、做实验，不考虑时间，直到成功解决 DDR3 板上间歇性失败问题为止。**

## ⚠️ 用户给的长期授权（最高优先级，上下文压缩后必读）

> "这样吧，接下来你随意发挥，随便猜想然后做实验，不需要考虑时间问题，只管尝试，直到成功为止，为了防止你因为上下文压缩遗忘这条指令，你有什么办法吗，写一个文件？"

- **授权范围**：可自由修改 RTL/脚本/配置做实验，不受"改代码需确认"红线约束（用户明确授权）
- **目标**：解决 DDR3 板上间歇性失败问题
- **不设时限**：可反复硬件迭代
- 但 git 提交仍需用户确认（AGENTS.md 红线未解除）

## 问题现象

板上 memtest 间歇失败（约 1/16 到 2/16 概率）：
```
P2 FAIL @00000003 got=000000FF exp=000000AD
```
- `got=0xFF` = 读到上电初始值（全 1）
- 偶发程序挂死（UART 空响应，monitor 无输出）
- 重新 program（全复位）后通常恢复正常几轮，再累积变差

## 硬件/环境基线（当前已知稳定配置）

- 板：lxb_artix7，DDR3 = MT41K256M16XX-107（-107 = DDR3-1066 等级）
- memclk = 400MHz（TimePeriod=2500），sys_clk=200MHz，ui_clk=100MHz
- PHYRatio 4:1，tRCD/tRP=13.91ns（400MHz 边界值），CL=6
- `Ddr3Adapter`：LiteBus ⇄ MIG native UI，RMW（读 16B 行→合并→全行写回）
- 当前 RTL 状态：**含 rdTimeout 重试**（与 HEAD 一致）
- 板上验证命令：`just dump` → `just vivado-build` → `just vivado-program` → 循环 `just uart-load nzea_sim/sim/tile/ddr_memtest.hex /dev/ttyUSB1 115200`

## 已确认事实（实验证据）

| # | 事实 | 证据 |
|---|---|---|
| F1 | **不是 memclk 频率** | 333MHz（5 FAIL+挂死）、400MHz（15/16 最优）、533MHz（16/16 全 FAIL）。整数倍 400 最优，非整数倍（333=200×1.667、533=200×2.67）更差。400 已是该时钟结构最优档 |
| F2 | **协议握手正确** | cosim 波形：app_en 持续 2 拍、app_rdy 提前拉高、app_addr 在采样窗口稳定。MIG `ui_cmd.v` 的 `app_rdy_r`/`app_en_r2` pipeline 有充足采样时间 |
| F3 | **基础读写基本稳定** | wr_rd_only（4KiB 全字写读）15/16，pwrite/pwrite2（单行部分写）16/16 |
| F4 | **跨多行/循环访问复现** | memtest（4KiB+64 行）、focus（无限循环）失败/挂死 |
| F5 | **重试只兜"读被吞"，不解决"读错数据"** | 重试触发条件=`app_rd_data_valid` 永不返回（FSM 卡死）。但 memtest FAIL 是读**返回了**错误数据（0xFF），程序跑完打印了 FAIL。重试对"读错数据"不触发 |
| F6 | **got=0xFF = 上电初值** | RMW 读回读到了从未被正确写的行/错误行 → 合并后写回 → 永久 0xFF |
| F7 | **时钟结构配置正确** | 50MHz→MMCM→200MHz(sys_clk)+100MHz(tile)→MIG内部MMCM→400MHz memclk。InputClkFreq=200、PHYRatio 4:1、TimePeriod=2500 全对 |
| **F8** | **"P2 FAIL"实为 phase 4 部分写验证失败（标签错位）** | memtest.S：`rd_fail`→`msg_fail1`="P1 FAIL"（phase2 读回），`pv_fail`→`msg_fail2`="P2 FAIL"（phase4 部分写验证），`ov_fail`→`msg_fail3`="P3 FAIL"（phase5 覆盖）。实测"P2 FAIL"= phase 4 = 部分写字节保持验证 |
| **F9** | **失败与行数/地址范围相关** | pwrite_scan 16 行：20/20 全过；64 行：19/20（1 次 PVFAIL） |
| **F10** | **row 5 稳定热点，但实为极低概率偶发** | pwrite_scan 两次失败都 r=5；r5_hammer 单行 hammer 357 万次才失败 1 次（got=0xFFFF 在 half[4:5]，exp=0xBEEF）。失败位置不固定（4:5 或 12:13），got 要么全 1 要么 0 = 读到"未写入"状态 |
| **F11** | **RMW 写回偶发不落盘** | 部分写（sb/sh→RMW）后某半字读到上电默认（全 1 或 0）= 该 RMW 写回没真正写入 DDR。概率极低（357 万次 1 次）但会累积 |
| **F12** | **写握手时序在 cosim 也干净** | 波形：app_wdf_rdy 提前稳定为 1、wdf_wren/wdf_end 同时高 1 拍、app_en 在 wren 后一拍拉高（先数据后命令）。MIG `ui_wr_data.v` 的 `app_wdf_rdy_r_copy2` 采样管线有充足时间 |
| **F13** | **load 后首次访问偶发挂死（比 hammer 失败更频繁）** | 同一 hex（r5_hammer）多次 load：有时打印 R5H 后卡死，有时连 R5H 都不打印直接无输出。挂死发生在程序最早期（首次 DDR 访问前/时）。与长期 hammer 偶发失败（357 万次 1 次）可能是两个独立或同源问题 |

## 已排除的假设

1. ❌ memclk 频率/时序裕度（F1）
2. ❌ 协议握手 app_en 时长/地址稳定（F2）
3. ❌ 基础读写/RMW 合并逻辑（F3）
4. ❌ 简单物理时序（频率实验否定）
5. ❌ 跨行流量必须（F10：单行 hammer 也复现）
6. ❌ 写数据时序（wdf_wren 与内部 app_wdf_rdy_r_copy2 相位）—— cosim 波形证明 rdy 提前稳定、wren 1 拍可穿过管线（F12）
7. ❌ 部分写合并逻辑（mergedFrom 字节/半字地址判断正确，F3）

## 待验证假设（候选方向，未排序）

- H1: **`clk_200m` 时钟约束缺失** — `mig_b.prj` 里 `<SystemClock>No Buffer</SystemClock>`，MIG 假设 sys_clk 干净。查 Vivado 里 clk_200m 是否有 create_clock 约束，MIG 内部跨域时序是否被正确分析。**零硬件成本，先查**
- H2: ~~memtest.S 打印逻辑是否有 bug~~ — **已确认标签错位（F8），但这不是失败原因，只是报告误导**
- H3: RMW 读回偶发读到错误行（跨 bank/跨行时 READ 返回错误地址的数据）
- H4: 写 drain 不足，写后立即读回读到旧值
- H5: CDC（tile 100MHz vs MIG ui_clk）采样偶发失败
- H6: ODT/驱动强度/信号完整性（emrRTT RZQ/4、emrOutputDriveStrength RZQ/7）
- H7: bank 预充电/激活切换时序
- ~~H8/H9~~（已排除）：wdf_wren 脉冲太短撞 MIG 内部 rdy 延迟（F12 否定）
- **H10（新，最可能）**: 这是真实系统 DDR 偶发错误（refresh 冲突/电源弹/采样一次性错误），需要**容错**而非修复——标准做法 ECC（但 16bit 颗粒不支持）或**写后读回验证+重写**
- **H11（新，更值得查）**: MIG 校准完成后立即访问不稳定（calib_done 刚拉高时首次访问偶发挂死/失败，F13）。查 MIG 校准完成后的稳定时间，及适配器是否在校准后等待足够久
- **H12（新）**: load 流程本身（BootFsm 加载后 cpuReset 释放时机）与 DDR 校准的交互——若 cpuReset 释放太早，程序在 DDR 未就绪时启动。**已部分排除**：`extRamCalibDone := true.B`（SRAM 用，恒 true），BootFsm 不看 DDR 校准；但适配器 `reqReady = ... && calibSync2` 门控了校准（Ddr3Adapter.scala:516），未校准的 DDR 访问会被挡。**矛盾：适配器门控了，为何还挂死？需进一步定位挂死卡在哪**

## 建议下一步（按成本低→高）

1. ~~H2~~（已排除）
2. ~~H8/H9~~（已排除，F12）
3. H10（容错方向）：评估"写后读回验证+重写"是否可接受（成本高但能根治"写不落盘"）
4. H1 查 Vivado 时钟约束（仍值得，零成本）
5. 然后 H3（ILA 抓 RMW 读回的实际 app_addr/数据）
6. 最后 H6（物理配置微调）

## 🎯 决定性实验：21_ddr3_test 参考工程一锤定音（2026-09-10 完成）

**烧录参考工程 bit（9 月 6 日综合），ILA 抓 1024 采样窗口：`error` 全程 = 0，自检持续读写循环稳定通过。**

- 参考工程：`nzea_fpga/21_ddr3_test/`（完整 Vivado 工程，**不提交**，仅参考）
- 它的 MIG 配置 `mig_b.prj` 与我们 `nzea_fpga/src/boards/lxb_artix7/ddr3_ref/` **逐字节一致**（diff 无输出）：400MHz、RZQ/4+RZQ/7、CL6/CWL5、16bit、SSTL15 引脚全同
- 它的 `top_pin.xdc`：clk=J19、rst_n=L18（与我们 A7_lite.xdc 一致，引脚确认对板）
- **结论：板子 DDR 硬件 + MIG 配置 + 引脚全部正常，问题 100% 在 nzea 侧 RTL**

### 参考工程 vs nzea Ddr3Adapter 的差异（重点对照）

| 维度 | 参考工程 (ddr3_ctrl.v) | nzea Ddr3Adapter |
|---|---|---|
| app_en | **持续高**直到计数满（`state==WRITE`） | 单周期脉冲（wrAppRdySeen/wrCmdPulse） |
| app_wdf_wren | ack 后**持续高**直到写完 | 单周期脉冲 |
| 掩码 | 恒 0（全行写） | 恒 0（全行写，RMW 合并） |
| 地址 | 组合直驱 ddr3_wr_addr（随采样变） | 锁存 appAddr（capture 时锁定） |
| 复位 | `sys_rst_n = locked`（纯 MMCM locked） | `sys_rst = !coreRst`（RESET 键 + MMCM locked） |
| 访问模式 | 顺序突发 256×128bit 全行写读 | RMW（读-合并-写回） |
| 自检模式 | 写 256 词 → 读 256 词循环，无 UART | memtest：部分写字节保持验证 |

### 由此产生的核心怀疑

1. **nzea 的 app_en/app_wdf_wren 单周期脉冲模式**可能不如参考工程的持续高电平稳健。参考工程证明 MIG 对持续 app_en（每次 `app_en&app_rdy` 采样一个命令）完全稳定。我们为规避"丢命令"加的单周期脉冲逻辑（wrAppRdySeen 等）反而可能是新问题源。
2. **复位链路差异**：参考工程 MIG `sys_rst_n = locked`，nzea 是 `!coreRst`（含 RESET 按键）。记录中的 H11/H12 复位猜测仍未排除。
3. 参考工程无 RMW，无法直接对照部分写——但若把 nzea 的写路径改成"持续 app_en + 持续 wdf_wren（每次采样递增）"风格，可能根治"写不落盘"。

### 参考工程复现设施（已就绪）

- `nzea_fpga/21_ddr3_test/program_ref.tcl`：烧录参考 bit
- `nzea_fpga/21_ddr3_test/ila_ref_capture.tcl`：抓参考 ILA（13 探针，probe12=error）到 `ref_capture.vcd`
- ILA 窗口 1024 采样 @ ui_clk（100MHz），error 全程 0 是强证据

### 🎯 决定性实验：ILA 抓到失败瞬间（2026-09-10，48 小时来首次）

**抓到了！** 用 ILA 触发 on `rd_data == 0xAAAA5555`（探针 21），第一次尝试就捕获失败现场 `capture_fail.named.vcd`。

**失败链条（波形证据，tick 3600-4096）**：

1. tick 3600-4070：全 sIdle，无任何请求 —— **这是 program 后首次 load，程序刚启动**
2. tick 4072：`st=001`(sRdCmd)、`appen=1`、`wen=1`、addr=0 —— **第一个 DDR 请求 = 写 0x88000000 的 RMW 读**（读行 0）
3. tick 4096：`rdv=1`、`rd_data=0xAAAA5555` —— **RMW 读行 0 读到高半字 0xAAAA5555（垃圾值）**
4. 合并写回把 0xAAAA5555 固化进行 0 的 word 2 → phase 2 读回 index 2 报 `FAIL @00000002 got=AAAA5555`

**根因（实锤 H11）**：**MIG 校准完成后，首次 RMW 读偶发读到 0xAAAA5555（读通路瞬态错误）**。概率约 20%（program 后首 load 时）。之后读通路稳定不再出错。

- 0xAAAA5555 = 高 16 位 0xAAAA + 低 16 位 0x5555（交替位模式）→ 疑似 DQS 训练残留/读相位未收敛
- **参考工程没有此问题**：它从不 RMW（先读后写），首次访问是直接写（不读），读通路首次访问是顺序全行读且 error=0
- 后续 80 轮连续 load 全过：因为首次读已成功，读通路已稳定

**修复方向（候选）**：
1. calib_done 后加稳定延迟（~100μs）再放行首个请求
2. calib 后首次访问前做一次丢弃结果的"哑读"，让读通路稳定
3. 或首次 RMW 读回后校验（读回值若为 0xAAAA5555 等垃圾则重试）—— 但重试要避免"读到垃圾→污染行"，需先验证再写回

**设施**：`build/.../vivado/ila_fail_capture.tcl`（触发 on 0xAAAA5555）、`ila_hunt2.sh`（循环 program+load 抓失败）

### 🔬 2026-09-10 后续实验：0xAAAA5555 真相 + appEnPulse 修复

**实验 A：calibSettle=100μs（calib 后稳定延迟）→ 无效**
- 板上 10 轮 2 失败（20%），30 轮 5 失败（16.7%）—— 与基线无差异
- cosim 里卡死 watchdog（cosim 40μs watchdog < 100μs settle）→ 被迫回退
- **结论：不是时间问题**（等 calib 后 100μs 也没用）

**实验 B：ILA 触发 on 0xAAAA5555 的 PASS 运行**（关键新证据）
- **PASS 运行也读到 0xAAAA5555**（每次 program 后首次 RMW 读未写行都读到）—— 0xAAAA5555 是 **DDR 上电垃圾**（读未写行的正常结果），不是偶发
- **0xAAAA5555 每次都在**（确定性），但 FAIL 只有 ~10-17% —— **FAIL = 写回没覆盖垃圾**
- **真正的 bug：RMW 写回偶发不落盘**（垃圾没被 phase 1 后续写覆盖）
- PASS 波形显示写回握手正常（wdf_wren 1 拍 + drain 32 拍 + sDone）

**实验 C：appEnPulse 修复（命令/数据同拍）**
- 发现原代码 bug：`appEn` 是寄存器（下拍生效），`wdfWren` 是组合线（当拍生效）——**命令比数据晚 1 拍**，背靠背 RMW 时可能错配
- 修复：新增组合 `appEnPulse`，与 `wdfWren` 同拍断言
- cosim 4 种相位/间隔全部 PASS（协议正确）
- 板上：3/30 → 2/30，累计 ~5/98 ≈ 5%（基线 17%）——**有效但不根治**

**当前结论（2026-09-10）**：
- 硬件 + MIG 配置 OK（参考工程 error=0）
- 协议层干净（cosim 永远 PASS）
- 板上 RMW 写回 ~5% 偶发不落盘，物理层问题（信号完整性/时序裕度）
- appEnPulse 已修一部分；剩余 ~5% 需要"写后读回验证 + 重写"容错（H10），或继续物理层排查

### 🌡️ 2026-09-10 温度实验（用户断电冷却后）

**用户怀疑过热**（FPGA 接线久了发热），断电冷却后重测：

- **冷却后失败率 ~2%（2/100）**，vs 热态 5-17% —— **温度确实放大问题**（时序裕度随温度漂移）
- 但 2% 依然存在 → **物理层裕度不足是根本，温度是放大器**，不是根因
- **失败模式变化**：冷却后 P1（phase1 全字写覆盖垃圾）几乎消失，只剩 **P2（phase3 部分写 sb byte4）~2%**

**P2 瞬态写丢验证**：P2 FAIL 后不重 program 直接再 load → DDR OK。**证明是瞬态写丢（非永久损坏），也排除合并逻辑错误**（若是合并错，第二次也会错）。

**部分写 vs 全字写**：都走 RMW 全行写回（mask=0），唯一区别是合并后数据（部分写保留未写字节）。全字写稳定（P1 消失），部分写 2% 失败 → **偶发写数据丢失，且只有部分写暴露**（未写字节的读回值在写回后若丢，读回验证能发现；全字写全部新值，即使错位也"错得一致"）。

**方向确认**：物理层偶发写数据丢失，温度敏感。
- 试过无效：频率(333/400/533)、ODT(RZQ/2)、驱动(RZQ/6)、时间等待(100us/10s)、calibSettle
- 剩余候选：**写后读回验证+重写容错**（H10，工业标准，重试 4 次可到 2%^4=1.6e-7）、或深挖 MIG 写路径时序（tWR 等，但 cosim 已证协议正确）

### 🔧 2026-09-10/11 write-verify 实现与深挖（重要发现）

**实现了写后读回验证 + 重写**（Ddr3Adapter：sVerifyRd/sVerifyWait 状态），cosim 全 PASS。

**关键发现（ILA 实锤）**：
1. **write-verify 正确检测写丢**：验证读回 ≠ wrLine → 重写（capture_w2.vcd：4664 验证失败回 sWrCmd 重写）
2. **但重写也失败**：重试 4 次都失败（5192 验证读回又 0xFFFFFFFF）→ 重试耗尽 → FAIL
3. **写回数据不是全 1**：触发 on wdf_data==0xFFFFFFFF 从未触发（12 次运行），但 FAIL 时读回 0xFFFFFFFF——写回数据正常但 DDR 没更新
4. **FAIL 后 DDR 大部分正确**：P2 FAIL 后读 word10 = 0xDEADBE58（正确值）——失败是局部/偶发
5. **app_rd_data_end 修复**：MIG 读响应可能 2 拍（cosim 模型 1 拍 end 恒 0），已在 sRdWait/sVerifyWait 用 valid&&end 采样，wrapper 里 end=valid 兼容 cosim
6. **drain 500 拍无效**（3/40 失败）——不是缓冲排空问题
7. **固定失败地址**：P1 word 2/10/12（0x88000028/30 反复）、P2 byte0（行 4）——不是随机

**核心矛盾**：write-verify 检测到写丢并重写同样数据，重写持续失败（4 次耗尽）；但 read_only 里同样的写成功（word10 读回正确）。重试为何失败仍是谜。

**探针变更历史**（重建过多次）：probe21 依次接 app_rd_data(95,64)→(31,0)→wdf_data(31,0)，probe16 接 app_rd_data_end。当前（2026-09-11）probe21=app_wdf_data(31,0)。

## 当前 git 状态

- HEAD = `705dceb`（goal 中文化 + wr_rd_only 提交）
- 工作区有未提交改动（FpgaElaborate.scala、asm2hex、goal、21_ddr3_test 等）
- 提交需用户确认

## 已提交的相关历史

- `566faf3` cosim（vivado-sim）建立
- `9f8cebc` cosim 场景选择 + PHASE/GAP 旋钮
- `705dceb` goal 中文化 + wr_rd_only
