# DDR3 上板调试记录（过程与教训）

这份文档记的是**过程**：为了把这颗 DDR3 做到板上正确读写，我们走过哪些路、哪些判断后来被推翻、以及现在凭什么说它是对的。

数字本身——寄存器值、器件的要求、每条的出处——在英文的 [`README.md`](README.md) 里。那份是"结论与出处"，这份是"怎么走到这里的"。两份都放在控制器旁边（`nzea_fpga/src/device/ddr3/`）。

> **关于语言**：仓库约定是"注释与文档仅英文"（见 `AGENTS.md`，`justfile` 注释除外）。这份中文文档是应维护者要求加的**例外**；如果打算长期保留，建议把这条例外补进 `AGENTS.md`，否则下一个人会以为它违规。

---

## 一、硬件与目标

| 项 | 值 |
|---|---|
| 板子 / FPGA | `lxb_artix7` / `xc7a200tfbg484-2`（Artix-7，**只有 HR bank**） |
| 颗粒 | 单颗 MT41K256M16TW-107（板标 D9SHG；x16，4 Gb DDR3L，本板跑 1.5 V） |
| 速率 | tCK = 2.5 ns，存储时钟 400 MHz，DDR3-800；CL 6 / CWL 5 / WR 6 / BL8 |
| 目标 | 板上各种读写方式 100% 正确，不留"偶尔错一次"的余地 |

引脚映射与金标准 MIG 参考（厂商例程 `21_ddr3_test`，跑在**同一块板**上，时序收敛 WNS +0.982/WHS +0.041）的 xdc 逐条一致（这份 xdc 现在随 dump 生成：文本在 `Ddr3Xdc.scala`，dump 落到 `build/fpga/<board>/<isa>/ddr3.xdc`）。但有一件事值得单独记：**这条总线不在同一颗 bank 里**——下字节、CK、命令侧在 bank 35，**上字节整组在 bank 34**（它的选通是 `IO_L3P_T0_DQS_34`，数据 pin 是 bank 34 的 `IO_L1*`）。这条事实后来解释了、也否掉了好几个想法。

---

## 二、现在的结论

- **读**：固定时钟捕获 + 每条 DQ lane 一个 `IDELAYE2`，步进约 78 ps（`Ddr3PadNames.tapLanes`：**每条字节 lane 一个 tap**，共 10 bit）。
  扫描按"两条 lane 的相对差"分三个平面——同 tap / 上字节 +8 步（≈625 ps）/ 上字节 −8 步——每平面 32 步，每块 96 轮；跑满 64 个 (地址, 模式) 组合后**自动锁定**：取 miss 最少的平面，再取该平面干净窗口的**中点**作为两条 lane 的 tap（`Ddr3.scala` 里的 `planeMiss*` / `first/last` / `locked*`）。
- **写**：两条选通都从与数据相差四分之一周期的那套网格启动——参考设计 `OCLK`/`OCLK_DELAYED` 的关系（UG586 原文："`OCLK_DELAYED` … to achieve the required 90° phase offset between the write DQS and its associated DQ bits"）。**不做逐字节写调平**，因为本器件没有可用的硬件手段（见第四节第 3、4 条）。
- **判据**（2026-10-16 第十三节起）：设备是 **LiteBus 从端**，本层测试（`Ddr3Soak`）是主端；结论看 soak 的 `errors`——**0 错**才算通过（不对读回的重试/刷新/时序都不算数），`done=1` 且 `calibrated=1` 同时成立。旧口径（按轮计的 `rounds`/`misses` 增量）已随 `camp*` 端口一起退役。
  历史板级读数（旧口径，12/13 节之前的实现）：累计通过 **1,820,188,946 轮**，`misses` 停在 **2,144**（全部发生在校准阶段），此后两次隔时抓取分别为 **+562,863,849 轮 / +0 miss** 与 **+991,254,132 轮 / +0 miss**。锁定值稳定在 `plane 0 / tap 15`。
- **顶层重构之后重测过一次**（2026-10-15，第八节：设计自己成为 `Top`、焊盘进 `Analog` 端口、IO 原语变成模块）：
  xsim 的 `Ddr3Bringup` PASS（`plane 0` 全 32 步干净、锁定 `plane 0 / tap 15`、锁定后 768 轮 0 miss）；板上两次隔时抓取
  **+635,525,751 轮 / +0 miss**，`lockedPlane=0` / `lockedTap=15`，与重构前一致。

---

## 三、被测到的场景（以及没覆盖的）

**每轮**（一轮 ≈ 230 ns）：`ACT` →（tRCD）→ `WR` 一整个 BL8 突发 →（tWR）→ `PRE` →（tRP）→ 重新 `ACT` 同一行 → `RD` 同一突发 → 与写下去的期望**逐位**比对（捕获窗口内做偏移搜索；整 128 bit 或任一个 64 bit 半段命中即算通过）。

- 一个突发 = 每条 DQ 8 拍、16 条 lane = **128 bit = 16 字节**；`DM` 恒 0（16 个字节全写）。
- 写完**紧接着**读回同一地址（不是"隔很久再读"）。
- 地址 8 种：bank 0（基准）、bank 1 / 7 / 4（打 bank 地址各 bit）、row 0x0001（行地址最低位）、row 0x0400（A10，兼作 precharge-all 标志位）、row 0x7fff（整行宽）、col 0x008、col 0x3f8（页边）。
- 模式 8 种：`0xa000+k` 递增、低字节 walking-1、高字节 walking-1、低字节 walking-0、高字节 walking-0、`0xaaaa/0x5555` 交替（两种相位）、以及**每轮换新状态**的 16 位 LFSR。
- 8 × 8 = **64 个 (地址, 模式) 组合**，稳态里循环遍历；累计 18 亿轮里每个组合跑了约 2800 万次。
- 初始化序列本身也全程在跑：MR2/MR3/MR1/MR0 + DLL reset + ZQCL，每 24 轮一次刷新（在 tREFI 内）。

**没覆盖**（本节记的是 2026-10-16 之前的旧口径；现在的计划见第十三节）：字节屏蔽写已由第十三节的 mask 步覆盖；仍未覆盖：BC4 / on-the-fly burst、背靠背无空档的流式读写（现在是"访问一个突发，中间有空档"）、写完隔久再读（保持特性）、刷新边界压力、**全地址空间**（只用了 16 个地址）、温度/电压角落、其它板子/其它颗粒；以及**读侧不是 DQS 捕获**——现在的余量是标定出来的固定值，不随 tDQSCK 跟踪。

> 上面这些表（地址、模式）"写什么"属于**测试**，从 2026-10-16 起在 `nzea_fpga/src/logic/Ddr3Soak.scala`（第十二节）；`Ddr3.scala` 只负责把交给它的拍子写下去、读回来、判对错。

---

## 四、被推翻的想法（这部分是这份文档存在的理由）

按时间顺序，**当时怎么判、依据是什么、后来被什么推翻**：

### 1. "ODT 是坏的：写突发期间器件没有端接"

- **当时依据**：代码里 `odt := writeState` 看上去只在写命令的那一个周期拉高；而真实写入的数据在 CWL 之后才上总线，那时候器件侧应当有端接才有意义——看起来正好错过。
- **推翻**：拿 Micron 数据手册算公式比对：`ODTLon = ODTLoff = CWL + AL − 2 = 3 tCK`、`ODTH8 = 6 tCK`、读前须提前 ≥0.5 tCK 关闭。我们的 ODT 引脚在整个写状态（6 个 fabric 周期 = **12 tCK**）里为高，于是 RTT 在命令后 3 tCK 开、在 15 tCK 关，而数据在 5…9 tCK 之间——**完整覆盖**；读期间为低 ✓；`MR1 = 0x0044` 选的 `RTT,nom = RZQ/6` 也在"写期间允许"的 {RZQ/2, 4, 6} 里 ✓。**合规，不是问题。**
- **教训**：`odt := writeState` 这种一行赋值，要按"状态持续多久"去读，不能按"看起来像脉冲"去读。

### 2. "上字节的选通要早一个网格步"（本板写入侧并不需要）

- **当时依据**：板上症状非常结构化——高字节翻转的模式（walking-1/0 高字节、AAAA/5555、LFSR）基本全挂；给上选通相移 −625 ps 之后，某个组合的捕获窗口从"8 拍只有 4 拍正确"变成"**7/8 拍逐位精确**"，每轮通过率 26.3% → 63.5%。于是判定"上字节的选通比它的数据晚半拍"，并把逐 lane 的启动相位差**写死**进设计，同时写进仿真模型（lane 0 加 625 ps、lane 1 加 1250 ps 的板级偏斜）。
- **推翻**：把读捕获的 tap **按字节 lane 分开**（本节之后的改动）之后，两条选通回到**同一网格**（= 参考设计的关系）依然全清：锁定后 18 亿轮零错。也就是说，当年看到的"上字节写入晚半拍"其实是**读侧**的假象——16 条 lane 共用一个 IDELAY tap，而两个字节组的捕获窗口本来就不同（它们甚至在两颗 bank 里），于是一个 tap 只能把一个字节组对好，另一个字节组被读成"边界采样"，看起来就像写坏了。
- **教训**：一个共用的读侧旋钮，会把读侧的问题伪装成写侧的问题。**"哪一个字节"这种归因必须靠按字节分开的机构去证。**
- **现状**：仿真里那条按 lane 的写偏斜模型已删除，换成一个**统一、拟合板级实测**的偏移（`nzea_fpga/sim/Ddr3Bringup.sv` 里的 `WrStrobeSkewPs`），并在注释里明确写成"拟合参数、不是推导"。

### 3. "用 `ODELAYE2` 给选通做可标定延迟"（这条路在本器件上不存在）

- **当时依据**：MIG 的写调平就是靠输出延迟线（phinx 的 PHASER_OUT / 输出 delay）把 DQS 推到正确相位；我们照做。
- **推翻**：布线直接否掉——`[DRC UTLZ-1] ODELAYE2 over-utilized … no compatible site`。查证后确认根因不是"引脚不在同一 bank"那么简单：**Artix-7 只有 HR bank，而本器件根本没有 `ODELAYE2`**。更硬的证据是 MIG 自己：为这个器件生成的 PHY RTL 里 `ODELAYE2` 出现 **0** 次、`PHASER` 出现 **0** 次，而且专门有 `..._dqs_found_cal_hr.v` 这种 HR-bank 变体——**参考设计自己也不做逐字节写调平**。
- **教训**：先查"这颗器件有没有这个块"，再决定用什么机制；"别的器件上的解法"不等于"本器件的解法"。

### 4. "用 MMCM 动态移相做写调平"（机制上不可能）

- **当时依据**：UG586 说写侧的 tap 边界"是拿 MMCM 的 phase shift tap 标定的"，那移相应该能做调平。
- **推翻**：MMCM 的移相是**全局**的——CK、DQ、命令、捕获会一起移动，而写调平要改的恰恰是 DQS 与 DQ 的**相对**关系；全局移动改变不了它。UG586 那句话说的是*全局*对齐（对应它的 PHASER_OUT 做逐 lane），在只有 HR bank、没有 PHASER 的器件上两头都落空。
- **结论**：本器件上"逐字节写调平"没有实现手段。参考能长期工作的原因是：这是一块**离散颗粒**的板子（不是 fly-by 的 DIMM 模组），两条 lane 的写侧偏斜本来就在器件的 `tDSS`/`tDSH` 窗口内。若遇到超出窗口的板子，只能改 PCB。

### 5. 验收判据本身错过一次

- **当时**：用"某个 (模式, 地址) 的**整块 32 个 tap 全绿**"当"这个组合是好的"的判据，于是长期看到 `passMask = 0`，一度读成"板子没有干净窗口"。
- **推翻**：tap 扫描**本来就会扫到眼外**（32 步 ≈ 2.5 ns，而一个数据眼只有一个位宽 1.25 ns），所以"整块全绿"几乎不可能成立，`passMask = 0` 是正常现象。
- **现状**：看 `failTaps`（按平面/步的 miss 图）与**锁定后**的回路率；抓取时也不再依赖等值触发去追瞬时值。

### 6. 其它被排除过的方向（当时怀疑过，逐条验证后排除）

引脚映射与金标准 xdc 逐条一致；地址译码（做过地址扫描，最远地址照样过）；I/O 电平与端接（`INTERNAL_VREF 0.750`、DQ 的 `IN_TERM UNTUNED_SPLIT_50` 与参考一致）；"颗粒坏元"这一类（失败与**数据模式**强相关而不是与地址或单元位置相关，且相位移一下错误就整片消失——坏元不长这样）。

---

## 五、工具、仿真与语言里的坑

按"下次会再踩"的顺序：

1. **`pulse` 读 ILA 导出的 VCD 不可靠**（返回全零）——一律自己解析 VCD。
2. **ILA 等值触发不能用"会一直往前跑的值"**：`misses` 这类计数器每秒涨几万，等一个具体数值永远等不到（曾因此让抓取挂死）。要用**状态条件**——例如抓一次 miss 用 `alignedAt=63`（"没有对齐"的哨兵值），触发率正好一次/一个 miss。
3. **设实现策略会重置 run 的 per-step 属性**，于是会丢掉 debug core 的 `STEPS.OPT_DESIGN.TCL.PRE` 钩子，产出一个**没有 ILA** 的比特流（打开硬件管理器只有"0 个 ILA"）。顺序必须是：**先设策略，再挂钩子**——这也正是流程脚本里的写法。
4. **默认实现策略会差十几皮秒**：命令侧那条电平路径要跨 45°→0° 两套网格（需求 4.375 ns），默认布线下单端点 −0.015 ns。流程改用 `Performance_ExplorePostRoutePhysOpt`（`nzea_fpga/tools/vivado/vivado-project.py` 写进 `build_all.tcl`），代价是构建时间 ~11 min → ~20 min。
5. **xsim 里 `ODELAYE2` 没有功能延迟**（模型里那句 `assign #INIT_DELAY DATAOUT` 被注释掉了），所以任何"靠输出延迟做标定"的机制在仿真里**验证不了**；`IDELAYE2` 相反，78 ps/步的延迟链是真实的。
6. **仿真里没有"采样眼"**：xsim 只在采样点恰好落在数据跳变上（皮秒级）才出错，所以"哪个 tap / 哪个平面更好"在仿真里**问不出答案**。对照：同一份设计，仿真里 plane 0 显示 32/32 步全干净，板上是 22/32。**板级实测是唯一权威**，仿真负责验机制（流程、锁定、计数器、协议）而不负责验模拟量。
7. **DSL / 测试台小坑**（都踩过）：
   - `muxTree` 要求条目数是 **2 的幂**；本 DSL **没有 `elsewhen`**（用并列 `when`）。
   - `.bit(i)` 已经返回 `Bool`，取反要用 `!` 而不是 `~`。
   - 生成节点的 helper 必须带 `(using Arena, Context, Block)`，否则在 `when` 区里调用会建到外层 block（FIRRTL 的 "operand does not dominate this use"）。
   - 探针名/网名不能有**前缀包含**关系（`probe_tap` 与 `probe_tapFail` 会让 debug tcl 的 `*probe_tap*` 匹配到一片网而构建失败）。
   - TB 的 `timescale 1ps/1ps` 下**裸数字就是 ps**：曾把 1800 µs 写成 `1800000`（= 1.8 ns），导致看门狗误报。
   - 向量语境下 `1'bz` 会被零扩展（把某一位驱动成 0 与设计的驱动打架，出现 `x`），要写 `2'bzz`。

---

## 六、现在怎么观测（复现口径）

从仓库根、在 `nix develop` 里：

```sh
just dump --target fpga --isa riscv32i      # 生成 RTL 到 build/fpga/lxb_artix7/riscv32i
just fpga build                             # Vivado 工程 + 比特流（~20 分钟，Explore 策略）
just fpga program                           # JTAG 烧录
just fpga capture lxb_artix7 riscv32i done=1             # 计划跑完那一窗：errors / issued / phase / step / firstBad*
just fpga capture lxb_artix7 riscv32i calibrating=0      # 校准后一窗：failTaps / lockedPlane / lockedTap
nu nzea_fpga/scripts/xsim.nu --name Ddr3Bringup --board lxb_artix7 --isa riscv32i --timeout 5400
```

波形落在 `build/fpga/lxb_artix7/riscv32i/vivado/capture.named.vcd`（探针语义名见同目录的 `ila_probes.map`）。
判读要点：**结论是 `errors == 0`（配 `done=1`、`calibrated=1`）**；`firstBadAddr` / `firstBadExp` / `firstBadGot` 是第一次不符的地址、期望与实收（128 bit 各一个字）；`phase` / `step` 是计划的位置（phase 0=写、1=等写响应、2=屏蔽写、3=发读、4=等读、5=完；step 0..39，40=done）。设备侧：`failTaps` 是 96 bit 的扫掠 miss 图（bit `32*plane + step`）；`lockedPlane` / `lockedTap` 是锁定结果；`checked` 是"比对那一轮实际读到的 704 bit 窗口"（低位=最新）；`respValid` / `respData` / `dmOut` 是前门正在发生什么。

---

## 七、还没做的事

- **读侧不是 DQS 捕获**，也没有 per-bit deskew：现在是"固定时钟 + 逐字节 tap"，无法跟踪 `tDQSCK`（UG586 的读调平是拿读选通做、并且按 bit 各自 centering）。这是当前架构与参考之间最实质的差距。
- **选通没有输入端接**：参考的 xdc 给 DQS 设了 `IN_TERM UNTUNED_SPLIT_50`，而我们的选通路径是纯输出（`OBUFTDS`），读期间器件驱动选通时 FPGA 侧没有端接。这是与参考在**读路径**上的真实差异，**尚未测量**。
- 第三节列的那些未覆盖场景：字节屏蔽写、BC4、背靠背流式、保持特性、刷新边界、全地址空间、温压角落、其它板/颗。
- 本器件上"逐字节写调平"没有硬件手段（无 ODELAY / 无 PHASER）；要覆盖写侧偏斜超窗的板子，只能改板。

---

## 八、把顶层收进设计里（2026-10-15）

**做了什么**：删掉了 `Ddr3BoardTop`——那份手写 Verilog 的"环"曾经是模块 `Top`，把 zaozi 生成的设计当作子模块 `TopCore` 包在里面。原因是它带来的成本是真的：DDR3 的"数字侧"（`DDR3_DQ_OUT` / `DQ_IN` / `RAS_N` 等 20 多个信号）必须作为端口在设计里出现、又在环里同名接一遍；`inout` 焊盘在设计里根本不存在；每个新信号都要改三处。

现在：设计自己的模块就叫 `Top`，**焊盘是它自己的端口**（`DDR3_DQ` / `DQS_P` / `DQS_N` 是 `Analog`，即 `inout`），IO 单元里的原语（`OSERDESE2` / `ISERDESE2` / `IDELAYE2` / `IDELAYCTRL` / `ODDR` / `IOBUF` / `OBUFTDS` / `OBUF` / `OBUFDS`）收拢成 `Ddr3Pads.scala` 里的 `VerilogWrapper` 模块，由设计实例化并把结果接上自己的端口。

**为此要给 zaozi 补的两样东西**（`.zaozi`，用 `just zaozi-publish` 发布）：

- `ConstructorApi` 里新增 `Analog(width)`（此前有 `Analog` 类型却没有构造器）；
- 新增 `attach`：FIRRTL 的模拟网只能靠 `attach` 连接，`:=` 按设计拒绝 Analog。

**踩到的坑（比结果更值得记）**：

1. **`attach` 只能写成成员调用**：`io.pad.attach(buf.io.pad)` 可以，裸写成 `attach(io.pad, buf.io.pad)` 会报"Not found: attach - did you mean Attach?"。原因是它定义在 `given Attach with` 里，这类"集合式扩展"只能通过成员调用被找到。定位靠 `AnalogPadProbeTest`：本地复制一份同型 given 同样解析失败，`summon[Attach]` 却成功——说明不是 jar 陈旧，而是调用形式。
2. **`verilog` 模块必须由设计实例化才能进电路**：`VerilogWrapper` 编译成 FIRRTL `ExtModule`，dump 不会为它写 body——body 与 filelist 条目仍是我们自己的事（`Ddr3Pads.register` 就是这件事：用了哪个写哪个，写一次列一次）。
3. **`-hierarchical` 的 `get_pins` 模式里带 `*/` 前缀等于什么都不匹配**（实测 `*/levelDrive/D1` 匹配 0 个，`*levelDrive/D1` 匹配 3 个）。而**匹配不上的时序例外是沉默的**：综合日志里只有一行 `[Vivado 12-508] No pins matched`，路径回到未放宽的需求（实测 1.875 ns vs 1.5 ns 路由，5 个端点 setup 差最多 0.76 ns，"Timing constraints are not met"）；改成 `*levelDrive/D1` 后同一条路径需求 4.375 ns，全设计 WNS +0.035（约束全满足）。
   从此**每次构建后查两条**：`grep 'No pins matched' nzea_fpga.runs/{synth_1,impl_1}/runme.log` 应当无输出；
   `Top_timing_summary_postroute_physopted.rpt` 应当写着 `All user specified timing constraints are met`。
4. **端口名变了、约束就得跟着变**：模块层次一深，旧的扁平实例路径（`DDR3_ODTDrive/D1`）自然失效；XDC 是唯一按名字绑定的地方，它必须与顶层一起改。

**验证**：`just fpga sim Ddr3Bringup` PASS（对数厂商模型，锁定 `plane 0 / tap 15`，锁定后 0 miss）；上板两次抓取
+635,525,751 轮 / +0 miss；`just fpga build` 时序全满足、无 `No pins matched`。

---

## 九、时钟改成"按需索取"（2026-10-15 同日）

**做了什么**：删掉板级的那张计划表（`BoardClock.plan`）。现在需要什么时钟就在用它的地方写一句：
`Clocks.clock(400.0, 90.0)` 返回一个 `Clock`，同键即同网；设计末尾 `Clocks.materialize(振荡器频率, 输入, 复位)`
把这一组需求一次兑现成一个 wizard（`nzea_fpga/src/Clocks.scala`）。`BoardClock` 只剩“振荡器 50 MHz”这一条板的事实。

**为什么 key 必须带相位，不能只给频率**：这颗设计里 200 MHz 出现了三种相位，且不可互换——`200@0` 是抽头参考与命令侧
的 `CLKDIV`，`200@45` 是控制器自己的时钟与数据网格的 `CLKDIV`（45° 正是让 CLKDIV 落在 400@90 的边沿上），
`200@180` 是反序列化器的输出寄存器。只传频率的话，三个调用点会合并成一张网——就是那种“看起来对、实际是另一个网格”的错。
（同理也符合仓库规矩：关键参数不给默认值，所以不写 `clock(200.0)`。）

**为什么是“先记需求、末尾一次建”而不是“用到就建一个 IP”**：wizard 的端口表就是它的计划（`clk_out1` 起），
实例一旦建出来就无法再长大；所以请求先落在自己的一根 `Wire` 上，等所有需求都到齐再建 wizard 并逐个接上。
这与 `Probes`/`Ila` 的分工同形（用在哪就在哪声明、最后统一兑现）。反过来“每个模块自己生成”是错的：
相位关系只存在于同一个 VCO 的输出之间，拆成两个发生器就把 DDR 的 400@0/400@90、200@45/200@180 变成了未知相位。

**顺带收掉一个会静默腐化的东西**：`BoardXdc` 里两条假路径以前写死 `clk_out1_clk_wiz_0` / `clk_out5_clk_wiz_0`——
与计划的**槽位**绑定，往计划中间插一个输出就会悄悄指到别的时钟。现在这两行由 `Clocks.outputName(频率, 相位)` 生成，
名字来自 key 而不是槽位；计划本身（六个 key 的顺序）由 `LxbArtix7ElaborateTest` 钉住。

**验证**：dump 出的 25 个设计/IP 产物（`Top.sv`、各 pads 模块、`ip_clk_wiz_0.tcl`、`filelist.f`、`ila_*.tcl/map` 等）
与重构前**逐字节相同**，`board.xdc` 的约束行相同（只多了一段注释）；重建后时序报告与之前完全一致
（WNS +0.035 / WHS +0.060，端点 7802/7794），无 `No pins matched`；上板两次抓取 **+636,521,270 轮 / +0 miss**，
锁定仍为 `plane 0 / tap 15`；`just fpga sim TopSmoke` 与 `Ddr3Bringup` PASS。

---

## 十、设备自己管自己的时钟（2026-10-15 同日，第三步）

**做了什么**：`Top.scala` 里关于 DDR3 的一切收拢成一件事——`Ddr3Side.instantiate(Ddr3SideParameter(...))`：
交进去的是**它自己声明的引脚**（`TopIO` 的 DDR3 端口），拿回来的是一个 `Ddr3Side`，对它说的话只有一句——
`ddr3Side.reset(resetN, locked)`（本层的策略：按键链 + wizard 锁定 + 抽头校准就绪）。控制器、五个 pad 模块、
`IDELAYCTRL`、总线自己的复位同步器、24 条探针绑定、以及**总线自己用的五个网格**都在
`device/ddr3/Ddr3Side.scala` 里，紧挨着它命名的实例；网格的名字与用途在 `Ddr3Clocks.scala`。

**机制：`Clocks.bind`**。`Clocks.clock(grid)` 交回一根线，连接由调用者自己写；`Clocks.bind(实例端口, grid)`
什么都不交，只说"这个端口跑在那个网格上"，连接等 wizard 建成时由机制代写（`materialize`）。于是声明实例端口的
文件（`Ddr3Side`）可以就地声明自己的时钟，上层一个时钟都不写、也不知道频率。`bind` 不需要 elaboration 上下文
——它只是记一条 `(端口, 网格)`，`materialize` 才去执行。

**代价（有意接受）**：计划槽位随"谁先问"而走。设备先 bind，于是计划顺序从
`200@0, 100@0, 400@0, 400@90, 200@45, 200@180` 变为 `200@0, 200@45, 400@0, 400@90, 200@180, 100@0`——
IP 的端口重排、`Top.sv` 的接线跟着换槽，探针表顺序也变成"先设备后本层"。这不再是逐字节等价，而是**逐网格等价**：
每个网格的频率/相位、谁用哪个网格都没变；约束里引用的名字由 `outputName(key)` 生成、跟着槽位走，不用改源文件。
（`LxbArtix7ElaborateTest` 里的计划顺序与探针表顺序都随之更新。）

**踩到的坑（前两个是本轮的，后两个是被这一轮暴露或顺带记下的）**：

1. **顺序：`materialize` 必须在所有 `bind` 之后跑**。先建 wizard 再 bind，那些 bind 就是 no-op，firtool 报
   `sink "ddr3Side.clk200" not fully initialized`。Top 的顺序因此是：
   `Bufg → ILA → 复位同步 → Ddr3Side → 100 MHz → materialize → ddr3Side.reset → 闪灯 → marks`。
2. **`sourcecode.Name.Machine` 会沿方法参数转发**。`Ddr3Side.instantiate` 一度自己声明了它，结果里面每个实例
   都拿到**调用点**的名字：`Ddr3 ddr3Side_0 (`、`Ddr3DqPads ddr3Side_2 (`…，探针表第一列从
   `ddr3/probe_state` 变成 `ddr3Side/probe_state`，仿真里 `u_top.ddr3.probe_rounds` 也全部失效。
   修法：只有真正要写进 named node 的方法才收它；`instantiate` 这种"替别人建实例"的 helper 不能收
   （`InstanceContext` 可以收，它只带匿名计数器）。
3. **ILA 的网名搜索与探针顺序耦合（老问题，被这轮暴露）**：`connect_debug_port` 会为每条已连接的探针在顶层插一根
   名叫 `<core>_<网名>` 的网；而声明名互为前缀（`probe_locked` 是 `probe_lockedPlane` 的前缀），于是
   `*probe_locked*` 的叶子搜索在"长名先连"时会把 core 自己插的网也算进来，构建直接失败
   （`ILA: '*probe_locked*' matched 8 nets, expected 1`）。修法是让搜索排除 core 自己的网
   （`-filter "NAME !~ u_ila_0*"`）：设计里不存在名字带 core 名的网，core 是实现阶段才被造出来的。
   这个坑以前靠"短名恰好先连"避开了——不该靠运气。
4. **多周期例外的 `{*T1 *T2 *T3 *T4}` 会顺带匹配 `SHIFTOUT1/2`**（包含子串 `T1`），Vivado 对不是端点的对象报
   `[Constraints 18-401]` 警告（100 条，但不影响其余对象生效）。要看的仍是 `No pins matched` 与最终时序结论。

**验证**：把 `Ddr3Xdc.register()` 从 `Top` 挪进 `Ddr3Side` 之后重 dump，28 个产物与挪之前逐字节一致（搬家不改变
内容，只改变"谁声明"）；重建后时序全满足（WNS **+0.166** / WHS **+0.119**，0 failing endpoints），
`synth_1`/`impl_1` 日志里 `No pins matched` 各 0 条，`ILA: u_ila_0 created with 30 probes`；
`Top_clock_utilization_routed.rpt` 里六个槽位周期为 5.000 / 5.000 / 2.500 / 2.500 / 5.000 / 10.000 ns
（即 200@0 / 200@45 / 400@0 / 400@90 / 200@180 / 100@0），单个 MMCME2_ADV；`just fpga sim TopSmoke` PASS，
`mill --no-server nzea_fpga.test` 全绿，`just dump --target core --isa riscv32i --flow yosys` 通过；
上板三次抓取：131,522,840 轮（校准期 miss 2158，锁定 `plane 0 / tap 15`）→ 417,446,233（**+285,923,393 / +0**）
→ 1,099,254,992（再 **+681,808,759 / +0**），`passMask` 全 1（64 个 (pattern, address) 对全部通过）。

---

## 十一、复位改成“原因网络”（2026-10-15 同日，第四步）

**做了什么**：新增 `nzea_rtl/src/stdlib/Resets.scala`，复位从“一根到处传的信号”变成“一组原因 + 每域一个同步器”：

- **term（原因）**：由产生它的那一方声明，`1 = 这个原因没有按住`，声明时就挂上探针。它**就是产生方原封不动的那根电平**
  ——raw 引脚、wizard 的 lock、设备自己的 ready，不要求“干净”：把它弄干净是**消费者自己同步器**的活。今天三个：
  `buttonPin`（按键的引脚）、`pllLocked`（wizard 锁定）、`ddr3TapsReady`（设备自己的抽头校准就绪）；
- **domain（域）**：由消费方在自己实例旁边声明它等哪些原因；机制在该域自己的时钟上造一个 `ResetSync`，
  `rawN = AND(这些原因)`，返回该域的复位。今天三个：`board50`、`board100`、`ddr3`；
- **一个名字就是一个复位**：同域内第二个消费者用同样的名字 + 同样的原因去问，拿回的是同一根 `syncN`
  （一个同步器、一个释放沿）；名字相同但原因不同 → 报错。不是为了省资源：两条独立链在同一拍采样同一个异步电平，
  可能对亚稳态给出**不同的裁定**，于是这个域里的寄存器分批离开复位；
- **`Resets.materialize()`**：Top 末尾一次接线（与 `Clocks.materialize` 同形）。必须等到末尾的原因和时钟一样：
  最后一个产生者（wizard 的 `locked`）只在 wizard 建好之后才存在。

**为什么原因一定是电平、绝不是 `Reset`**：`Reset` 只在自己域里有意义，跳域递一个 `Reset` 正是同步器要防的错；
而电平可以自由跳域，因为**消费方的同步器**才是让它属于消费方的东西。于是每个域**恰好一个**同步器
（串两个就是“各寄存器分批离开复位”——正是当年 DDR 域那条 **−7 ns** 的成因），且这些跳域路径在约束里是
声明过的例外，不是要做时序的路径。

**为什么不做成全局复位树**：各域的原因集合本来就不同——50 MHz 域只等按键；wizard 喂的域还要等 lock；
设备还要等自己的校准。网络让每个域自己说：加一个原因只是产生者那里多一行，加一个域只是消费方那里多一个列表，
两边互不知道对方。

**顺带消掉一个隐患**：100 MHz 的 blinker 之前吃的是 `(resetN50 & locked)` 这个组合异步复位（两个外来域的电平
直接进异步复位端）——现在它吃 `board100Reset`，即 `board100` 域自己同步器的输出。

**第二轮修正（同日，回答“能不能用注册的组合电平代替同步器”）**：初版里 `buttonReleased`（50 MHz 链的输出）被
其它域消费——那本身就是一次跳域共享，靠假路径压着。现在 **term 就是产生方原封不动的那根电平**：各域直接等
`buttonPin`，各自在自己的域里同步它（这就是教科书做法：异步输入由每个目标域自己取）；`Resets.term("buttonReleased")`
以及那根共享的“干净电平”没了。两个附带结果：Top 里不再需要为按键专门做一条给别人用的链（`board50` 域还在，
因为它自己有 flop——LED 面板和 ILA 的采样时钟）；同一域内同一 seq 的第二次请求现在**共享**同一根复位（见上）。

**一个被提出但**不成立**的想法**：“把多个 reset 源先注册成一拍，再用它当复位，就能切断 reset 的时序路径”。
两处不成立：

- 路径不会消失，只会**搬家**：从（各源 flop → 同步器的异步端）搬到（各源 flop → 那个 reg 的 D 端），仍是跳域路径，
  仍然要么做时序、要么假路径；而单级 `Reg(Bool)` 的 D 就是异步输入，输出可能亚稳态——**用可能亚稳态的信号去复位
  整个域**比数据位亚稳态危险得多（会传到每个 flop）。二级链才是安全的那件事，而那正是 `ResetSync`：
  它的 `stage0` 就是那个 Reg，`stage1` 是让这根信号敢当复位的一级。
- 删不掉的那条：**异步断言**。DDR 域与 100 MHz 域的时钟都来自 wizard，“锁没建立/失去了”必须能在**没有时钟**
  的情况下把域按住；纯同步复位做不到（没时钟就没复位），所以这个形状必须保留。

**而那时真正在失败的也不是复位**（见下）。

**时序插曲（值得记下，因为它长得像复位问题）**：这一轮的前两次构建时序不过：9 个失败端点全部在 DDR3 控制器
**自己的 200 MHz 域内部**，与复位锥无关：

| 路径 | slack |
|---|---|
| `ddr3/timer_reg[10] → ddr3/state_reg[0..4]/CE` | −0.051（5 个端点） |
| `ddr3/expectedReg_reg[33] → ddr3/matched_12_reg/D` | −0.033 |
| `ddr3/captured_reg[132] → ddr3/matched_8_reg/D` | −0.022 |
| `dqPads/dqCapture3/CLKDIVP → ddr3/captured_reg[19]/D` | −0.042 |

关键证据是**端点就在旁边**：`timer_reg[10]` @ `SLICE_X144Y153`，`state_reg[0]` @ `SLICE_X153Y152`，
两点相邻却走了 **3.8 ns 的绕线**（4.75 ns 预算里 79% 是 route）——这是控制器旁边那块“口袋”里的**拥塞绕线**，
不是逻辑深度。诊断能这么快落地靠的是 `open_run` + `get_cells -leaf` 查 `LOC`（不是猜）。

修法落在路由器而不是 RTL：`impl_1` 的 **route 与 post-route phys-opt 两步从 `Explore` 提到 `AggressiveExplore`**
（`tools/vivado/vivado-project.py`，与策略设置放在一起），同一个网表从 **−0.051 变成 +0.065**
（WHS +0.103，0 失败端点），而且 phys-opt 日志里能看到它在处理正是这些网（`ddr3/probe_timer[16]`、`ddr3/matched_*`）。
同一网表两次跑出**逐位相同**的结果——这是确定性回归，不是布局抽签，所以“多跑一次试试”不解决问题。

**踩到/顺手处理的坑**：

1. **探针名的包含关系是真陷阱**：叶子搜索是子串匹配，`probe_button` 会同时匹上 `probe_buttonReleased`——
   构建会在 20 分钟后以“匹配到 2 个网”失败。于是 `Probes.declare`（`Resets.term` 走的就是它）现在在
   elaboration 期就拒绝“一个名字包含另一个”，并把原因改名（`button` → `buttonPin`）：20 分钟后的失败变成
   dump 时的立刻失败。（同一个检查还顺手免了另一种撞法：声明的名字两两不得互为子串。）
2. **相同前缀会被工具折叠（CSE）**：`board100` 的 `(buttonPin & pllLocked)` 与 `ddr3` 域的同一前缀是同一个
   表达式，CIRCT 把它们折成一根网。所以 AND 节点按“它是什么”命名（`buttonPinAndPllLocked`），
   而不是按“谁先问”——否则那根网会带着第一个域的名字喂给第二个域（第一版真的出现了 `ddr3Held` 喂 `board100`）。
   折成一根网本身是好事（少一个 LUT），名字说真话才是关键。
3. **`Reset → Bool` 有 `asBool`，但 `Bool & Bool` 的结果不能再点 `.asBool`**（会撞上 `DynamicSubfield` 的重载）；
   而 `Resets.domain` 收的是 `Referable[Bool]`，所以 `ResetSync` 的输出作 term 用时得显式 `resetN50.asBool`。
4. **小设计的 AND 会被内联、大设计的会被留成网**：同一个 `Resets` 在 `ResetsTest` 的玩具设计里落成
   `.rawN (pwrGood & bReady)`，在板级里落成一根共享网——测试断言的是**语义**（哪个域等哪几个原因），
   不是 firtool 这一次恰好把中间节点怎么排。
5. **测试里别用 `split` 数带括号的字符串**：`sv.split("ResetSync aSync (")` 里那个 `(` 是正则元字符，
   要数行就用 `linesIterator.count(_.contains(...))`。

**验证**：`ResetsTest`（`nzea_rtl/test/src/stdlib/ResetsTest.scala`，8 例：好路径 + 七种拒绝，含“同请求共享同一根复位”）
与 `nzea_fpga.test`（18 例，含“每个域自己的合取、自己时钟”）全绿；`just fpga sim TopSmoke` PASS；
`just dump --target core --isa riscv32i --flow yosys` 通过；重建后时序全满足（WNS **+0.100** / WHS **+0.121**，
0 失败端点，`No pins matched` 0 条，`ILA: u_ila_0 created with 34 probes`）；上板两次抓取：
106,889,161 轮（校准期 miss 2162，锁定 `plane 0 / tap 15`）→ 1,172,807,283（**+1,065,918,122 / +0**），
三个域复位均已释放、三个原因电平全为“不按住”，`passMask` 逐位通过。

**收尾清理**：把 `board.xdc` 里三条按网格点名的例外（`CLK_50M` → taps / loads / 100 MHz）删掉——复位改成
每域各自同步 raw 原因之后，50 MHz 域不再有任何电平流向那些网格，三条已是空转。删后重建的结果与删前**逐位相同**
（WNS +0.100 / WHS +0.121，0 违例，WPWS 端点 18465），换句话说删掉的确实是零：重新烧录后抓取
78,248,649 轮（校准期 miss 2171，锁定 `plane 0 / tap 15`，三个域复位全释放）→ 834,753,636
（**+756,504,987 / +0**）。`board.xdc` 现在只剩两条例外：
`-from [get_ports RESET]`（按键是异步输入）与 `-to [get_clocks -quiet {CLK_50M}]`（振荡器域只观察）。

---

## 十二、设备与测试分家（2026-10-16）

**做了什么**：`Ddr3.scala` 里的"传输"与"测试"分家，分界线就是端口：

- **设备（`device/ddr3/Ddr3.scala`）= 传输 + 读路径自己的两个测量**：JEDEC 上电与模式寄存器序列、一轮的完整时序
  （ACT→WR→PRE→ACT→RD→判）、写 staging（三个字 + 选通串）、捕获窗口与比对，以及**扫掠本身**
  （32 步 × 上字节三个假设 → `failTaps` / `lockedPlane` / `lockedTap`）与捕获对齐。它不再知道自己写的是什么：
  一轮的 8 拍与地址从端口进来（`campBeats` / `campBa` / `campRow` / `campCol`，campaign 的寄存器、整轮保持），
  一轮的裁决从 `campPass` 出去（在 `campRoundEdge` 那一拍有效），扫掠的状态从 `campRoundDone` / `campTap` /
  `campCalibrating` / `campLockedTap` 出去。
- **测试（`logic/Ddr3Soak.scala`）= 与 `Blinkers` 同级的本层逻辑**：8 模式 × 8 地址 = 64 对（表在它的
  `patternRows` / `comboRows` / `comboBas` / `comboCols`，与旧实现逐项一致），一对跑完**一整遍扫掠**
  （96 轮 = 一个 block）才换下一对；`rounds` / `misses` / `blocks`、逐对的 `passMask` / `missMask`、miss 的归属
  （`missPat` / `missAddr` / `missTap`）都在这里；LFSR 那一行也在这里，每轮推进 8 步。
- **接线在 `Ddr3Side.instantiate` 交回的 `Ddr3Bus` 里**：控制器实例 + **总线域的时钟与复位** + `wireCampaign(soak)`。
  `Top` 只写三行——`soak.clock := bus.clock`、`soak.resetN := bus.resetN`、`bus.wireCampaign(soak)`：soak 跑在控制器
  自己的网格上、吃同一个域复位（一轮的裁决因此必然在它被盖章的域里被读到），而 `camp*` 端口的名字是总线的词汇，
  接线写在总线的文件里（与 `Clocks.bind`、`Resets` 同一个形状：声明由拥有者做，接线由边界做）。

**为什么这么分**：这轮迁移一直在做同一件事——把"事实"放回拥有它的文件里。*什么是 DDR3 的传输*（引脚、JEDEC 时序、
扫掠）是器件的事，datasheet / UG586 说了算；*写什么、怎么算通过、通过率多少*是板子的测试的事，它随 bring-up 来来去去。
分家之后 `DEBUG_SCAFFOLDING.md` 的"可删清单"第一次有了**文件边界**：整份 `Ddr3Soak.scala` 是可删的（连同它那 9 条
探针），`Ddr3.scala` 是要留的；`Top` 里少一位实例即可退掉整场测试。

**探针的写法**：这一轮起，单实例模块的探针用 `Probes.mark`（声明+绑定一步做完），名单就在信号旁边（`Ddr3.scala`
15 条、`Ddr3Soak.scala` 9 条、`Resets` 6 条、`Top` 2 条）；只有 `Blink`（一个模块两个实例）保持"声明在体、绑定在实例"。
名称因此回到裸名（`rounds`、`state`……，不再有 `soak_`/`ddr3_` 前缀）——将来真出现第二个实例时，按用户定的规矩给名字
加模块前缀即可（那时两个同名网会让构建响亮地失败，不会静默连错）。

**没有变的**：时序、引脚、模式寄存器、ODT、选通串、扫掠的判据与锁定算法、64 对表、LFSR 多项式与种子、`campPass`
的取样时刻——全部按旧实现的生成 Verilog 逐项对齐（地址表里 `ba[6]` 这种单项也在对齐时被揪出来改正）。ILA 探针总数仍为
**34**：设备 15（`state` … `dqIn`）+ 测试 9（`rounds` … `missMask`）+ 其余不变（都是裸名，见下）。

**代价/风险**：地址译码的锥多了一跳端口（soak 的 row/col/ba 寄存器 → 端口 → `pairNode` → 命令译码），这是分家唯一
可能碰到时序的地方——上板前的时序结论与抓取见下。

**踩到的坑**：生成节点的 helper 必须带 `(using Arena, Context, Block)`（第五节第 7 条）：`lfsrStep` / `tableBeat` 一开始
没带，在 `when(campRoundEdge)` 里调用时节点建到了外层的 module block，`firld` 直接拒绝
（`operand #1 does not dominate this use`）——同一个坑又踩一次，这次把它写进了 `Ddr3Soak.scala` 的注释里。

---

## 十三、设备变成真正的控制器：LiteBus 前门 + 计划式自检（2026-10-16）

**为什么**：`camp*` 端口是"测试把自己的寄存器借给设备"——设备靠别人的端口才知道自己写什么，这不像一个可复用的器件。
这一轮把设备做成了**总线的从端**：谁来读、谁来写都行（今天接的是本层测试，将来是 tile 的核），它不再需要知道测试的存在。

### 接口契约（`Ddr3BusShape`）

一次总线拍 = 一个 **BL8 突发 = 128 bit = 16 字节**；地址是字节地址，低 4 位由主端保持 0；`wstrb` 一字节一位，落到器件的
`DM`；`user` / `id` 各 1 bit；同一时刻只有一个未完成请求。每次访问 = **PRE + ACT + 突发**（没有页状态，行开完就关）；
刷新在请求**之间**插入（3.75 µs 定时器），**永不打断突发**。主端可以"从复位起就把第一个请求摆在那里"——
`req.ready` 在 bring-up 完成前不会被拉高，等设备与等 ready 是同一个等。

### 自检计划（`logic/Ddr3Soak.scala`，判据 = `errors == 0`）

共 40 步，一步一次"写下去、读回来、逐位比对"；mask 步多一次写：

- **walk（16 步）**：16 个地址把设备译码的字段各走一遍（列 `0x10/0x70/0xf0/0x7f0`、bank 1/2/3/7、行 1/2/8/0x400/0x4000、
  顶行 `0x7fff`）；数据 = 步号放在每一拍的高位 + 低位一个一热字节，地址与数据互相可辨。
- **patterns（8 步）**：全 0、全 1、`aaaa`/`5555`、两条混合走线（同一地址）——卡死/串线在这里现形。
- **LFSR（8 步）**：极大长度序列连续 64 个状态，每步 8 拍、换地址——表里放不下的图案。
- **mask（8 步）**：同一地址先全写 base（全 `0x55`）、再带 `wstrb` 屏蔽写（全 `0xaa`）、再读回核对**逐字节**：被屏蔽的
  字节必须是 base 留下的值。期望不是查表而是**按规则长出来**：每次写拍更新期望（strobe 覆盖的字节取写值，其余保留）
  ——这正是设备要遵守的那条规则。
- 账：`errors`（不符的读回数）、`firstBadAddr/Exp/Got`（第一次不符的现场）、`done`；`issued` 是已接受的请求数。

xsim（`Ddr3Bringup`）对厂商模型跑完：**88 次访问（40 写 + 40 读 + 8 次屏蔽写）、40 次读回、0 错**。

### DM 的硬件事实（这轮新学到的）

`wstrb` 是**逐 transfer** 的事实（总线字节 `i` = transfer `i/2` 的 lane `i%2`），而命令侧的 SDR 焊盘模块每个 pin 每周期
只有 **2 个电平**（一拍重复成一整个时钟）——用它就只能"两个 transfer 共用一个 mask 电平"，可那两个 transfer 相隔 4 个
字节，**主端屏蔽的是字节，不是对**。于是新增 `Ddr3DmPads` 模块：每 pin **4 个电平**（`D1(levels[4*bit])`…不再重复）、
跑在**数据自己的两套网格**上（`clk400Dqs`/`clk200Dqs`——mask 必须与它屏蔽的数据同步启动）、`OBUF` 输出、无三态（mask 常驱动）。
这也是第 3 / 第 7 节里"字节屏蔽写未覆盖"那个洞的填法。

### 两个仿真抓到、否则会上板才暴露的 bug

1. **捕获窗口只在校准轮里移位**：`captured` 的移位语句写在了 `when(state === sRd)`（校准）里面，而窗口使能
   `captureOpen` 明明把 `sBusRd` 也算上了——于是总线读的响应切的是**校准最后一轮留下的旧窗口**。症状极像"写没生效"
   （第一次读回 = 校准图案，且两端差一个采样）。教训：**使能信号和它驱动的动作要对着核**。
2. **对齐位置随 tap 移动，不能在扫掠中途锁死**：`alignLocked` 原本在扫掠**第一次通过**时锁定；但输入延迟（IDELAY）
   会把突发在窗口里的位置挪动最多约 2 个采样，于是总线跑在**锁定 tap**、切片用的是**第一个通过点的 tap** 的对齐，
   响应整体错一个采样。修法：扫掠结束后，在**锁定的 taps** 上再跑**一轮**（`finalRound`，仍用设备自己的图案）：
   **那一轮的整突发判定 = `calibrated`**，**那一轮的对齐 = 读路径切片的依据**。"在你会运行的配置上量"的又一例。
   （`alignedAt` 仍逐轮更新，`alignGot` 仍是扫掠见过完整突发的记录——它们降为诊断。）
3. 一条不是 bug 但要知道：厂商模型的 `DQS ... latching edge required during the preceding clock period` **ERROR 行在每个
   写突发开始时都打**（从第一次校准写就有，与本次改动无关；其后 `WRITE @ DQS=` 打出的 data 全部正确，旧实现也确实在
   板上跑了 18 亿轮零错）。不要据此以为写坏了。

**xsim 结果**（`nu nzea_fpga/scripts/xsim.nu --name Ddr3Bringup --timeout 5400`）：
`Ddr3Bringup: PASS`——`plane 0` 全 32 步干净、锁定 `plane 0 / tap 15`、post-lock 对齐 31、0 错。（板上验证另起任务。）

**探针名单的变化**：设备 15 → **22**（`rounds` 一类不在它那里；新增 `calibrated` / `alignGot` / `alignLocked` /
`accesses` / `respValid` / `respData` / `dmOut`），测试 9 → **8**（`phase` / `step` / `issued` / `errors` / `done` /
`firstBadAddr` / `firstBadExp` / `firstBadGot`）；`mark_debug` 总数 33 → 39（含面板那一条声明绑两个实例，探针共 40）。

---

## 十四、板上才看得到的两个事实：写选通的相位、后锁定轮的 tap（2026-10-16/17）

**为什么**：上一节（十三）把设备变成了 LiteBus 从端、把测试独立成 `Ddr3Soak` 之后，板上从"没有一次读回是对的"
走到"每一拍都错一个 beat"。两处缺陷都在**模型看不见的地方**——厂商模型对它们一律宽容，xsim 全程 PASS，只有板子和 ILA 说得出话。

### 1. 写选通整条比数据早一个 slot（模型不判这个）

**现象**（板上 ILA，校准轮的窗口）：器件回的永远是 `[0, b0..b6]`——它把**填充字**当成了突发的第一拍、把第 7 拍当
最后一拍。同一个 RTL 在 xsim 里"整突发命中"；模型的 `DQS ... latching edge required during the preceding clock
period` 那行 ERROR 就是这种宽容的样子（每个写突发都打，改动前后都打，别据此以为写坏了）。

**根因**：选通与数据从相差四分之一周期的两套网格启动（UG586 要的 90° 就是它们的关系），但两条网格在焊盘上**实际**
差多少，是原语在"加载沿 → 第一个 slot"之间做的事，不是 RTL 能声明的事实。板上量出来：整条训练早一个数据 slot（1.25 ns）。

**修法**：训练不再只有"标称"一种切法。`writeShift` 把同一条 `dqsFlat` 按 **标称 / 晚一个 slot / 早一个 slot** 三种相位切出来
（对 `(stage, shift)` 的常量做两级 mux，字宽不变，不往焊盘路径上加宽），扫掠**按相位逐轮试**，判据仍是"窗口里出现
**完整**的 8 拍突发"；标称排第一，所以模型世界里永远停在第一轮。

### 2. 后锁定轮跑在 tap 0 上（`ownPattern` 一名两用）

**现象**：写对齐修好后，后锁定轮通过（`finalPass=1`、`calibrated=1`），但总线**读回**的数据整体错一个 beat：
walk 的 `0001_0002_0004_0008` 读成 `0000_0001_0002_0004`。

**根因**：`delayTap` 的选择条件写的是 `ownPattern`（= `calibrating | finalRound`），而后锁定轮开始时扫掠索引
`roundIn` 已经回绕到 **0**——于是这一轮**跑在 tap 0**，而总线跑在锁定 tap **15**：15 个 tap ≈ 1.17 ns ≈ 正好一个采样
（1.25 ns）。`alignLocked` 量的是"tap 0 的对齐"，却交给 tap 15 的总线去切片，于是差一个采样。板上 capture 一眼可见：
`finalRound=1` 的那一窗里 `tap=00` 而 `lockedTap=0f`。

**修法**：选择条件改成 `calibrating`——扫掠期间用扫掠的 tap，其余（含后锁定轮和总线）都用锁定的 tap。改后
`alignLocked` 从 **39 变 38**，正是那一个采样。

### 板上结论（两轮都干净）

`errors=0` / `done=1` / `issued=88` / `firstBad*=0`，`calibrated=1` / `finalPass=1` / `alignLocked=38` /
`lockedTap=15` / `writeShift=1`；一次是烧录后的自动运行，一次是按键复位的冷启动（ILA 触发条件 `step=39`，
窗口里能看到 step 24→39、issued 50→88 全程 `errors=0`）。

**教训**：`ownPattern` 这个名字管的是"这一轮写**谁的数据**"，不该顺手决定"这一轮跑在**哪个 tap** 上"——一个信号
两种含义，迟早错一个采样。以及本 campaign 第三次的同一条：**在你会运行的那套配置上量**（第一、二次在上一节的
捕获窗口与对齐随 tap 移动）。

**另一个坑（工具）**：读板上 capture 时 `pulse` 的 `value --changes` / 多信号 `value --at` 出现过一次
一次性误读（同一命令随后 20 次都对），此后所有结论都用原始 VCD 复核；本仓 `pulse/` 的那次修复与 fixture 就是冲这一类去的。

---

## 十五、逻辑自己拥有设备：一条 needs 声明（2026-10-17）

**为什么**：`Top` 里调用 DDR3 要分心两处——一处实例化 `Ddr3Side`、按名字交 15 个引脚，另一处再把 `Ddr3Soak` 接上
（时钟、复位、总线三条）。本质上是同一件事：**逻辑需要一组板级 IO**。于是这一轮把"要什么"做成了逻辑自己的声明：
逻辑说 pins / grids / 复位域，`Top` 按声明长出端口并接线——`Ddr3Side` 由 `Ddr3Soak` 在自己内部实例化，`Top` 只剩两行。

**做了什么**（新增 `src/Pins.scala`、`src/Logic.scala`；改 `src/Clocks.scala`、`device/ddr3/Ddr3Side.scala`、
`logic/Ddr3Soak.scala`、`src/Top.scala`）：

- `Pin(name, kind)` 与 `Pins.declare` / `Pins.connect`：名字就是 XDC 的名字（约束按名绑定）；`BitIn`/`BitOut`/
  `VectorIn`/`VectorOut`/`Pad` 决定端口方向与类型（`Pad` 是 `Analog`，两端 `attach`）。同名请求在一个模块里必须同
  kind，`declare` 负责去重——按键既是本层复位网络的 reason、也是设备抽头校准的复位源，一个端口两处用。
- `LogicNeeds(pins, grids, produces, reset)` + `trait BoardLogic`：一个逻辑对上层说的全部话。`grids` 用它要的网格
  （端口名由 `Clocks.portName` 生成，如 `clk400_90`）；`produces` 是它替上层产出的复位 reason（这里是
  `ddr3TapsReady`）；`reset = LogicReset(name, clock, terms)` 是它自己的复位域。
- `Logics.declare` / `Logics.connect`：前者在逻辑的 record 体里长端口，后者在上层把 pins、grids、reason、域一次接好
  ——**域在上层落地**（synchronizer 与 conjunction 都建在 reason 被生产的那一层），交进子模块的是复位本身
  （`resetN` 端口）。`Top.architecture` 因此只剩 `Ddr3Soak.instantiate(...)` 与 `Logics.connect(io, soak.io, Ddr3Soak.needs)`；
  `TopIO` 的端口表 = 本层自己的 pins ∪ 逻辑们的 pins。
- `Clocks`：删掉 `bind`（它的前提是"设备实例与 wizard 同层"），改为 `declare`（端口）+ `connect`（上层接线）+
  `ports`（设备文件在子模块里拿到的 `Map[ClockOut, Ref[Clock]]`）。

**为什么是端口，而不是"交进来的线"**（这轮最该记住的一条）：zaozi 在 `instantiate` 的那一刻就 elaborate 子模块、
把它写成 `<module>.mlirbc`，而 `firld` 事后链接的是**文件**。任何在子模块 elaborate 之后才写进它 block 的连接都进不了
文件——**静默丢失**。所以跨模块的一切只能是端口，子模块 body 里该写的必须在自己 body 里写完；`Clocks.bind` /
`Resets.materialize` 那种"事后连接"只对顶层自己成立。这也解释了为什么子模块的时钟/复位走端口、而复位域留在上层。

**部署形态没变**：`ddr3Sync` 仍在顶层，`rawN = buttonPinAndPllLocked & _soak_ddr3TapsReady`（与原来的
`& _delayCtrl_READY` 逻辑等价，只多一层端口）；wizard 槽位（`clk_out1..5` = 五个网格、`clk_out6` = 100 MHz）、
`ddr3.xdc`、`board.xdc` 一字未改；pads 与 `Ddr3` 只是从 `Top` 挪进 `soak`（仿真路径随之变为 `u_top.soak.ddr3.*`、
`u_top.soak.dqPads.*`）。

**探针顺序**：`ddr3TapsReady` / `ddr3Reset` 现在由 `Logics.connect` 声明，晚于 soak 自身的 elaborate，于是排在 soak
的探针之后、`pllLocked` 之前（集合不变，`mark_debug` 总数仍 54，`ila_probes.map` 与 debug/capture tcl 由它生成）。

**验证**：`just dump --target fpga --isa riscv32i` 通过；`mill --no-server nzea_fpga.test` 18/18；
`nu nzea_fpga/scripts/xsim.nu --name Ddr3Bringup --timeout 5400` → `Ddr3Bringup: PASS`（40 步 / 88 次访问 /
0 错读回，锁定 `plane 0 / tap 15`、`writeShift 0`、对齐 39——与第十三节记录逐项一致）。

---

## 十六、把程序送进来：串口加载器与 16550 打印（2026-10-17）

**为什么**：板上一直缺最后一块——往核心的内存里放程序。legacy 的魔法帧加载器（`FabricBusUart` + `probe`）搬进新树，
同时把程序要用的**发送半**（16550）补上：加载器负责收，程序负责打印。

**做了什么**：

- `nzea_device/src/devices/Uart.scala`：16550 的发送半（窗口两个 word：`THR/RBR`、`IER`、`IIR/FCR`、`LCR` 与
  `MCR`、`LSR`、`MSR`、`SCR`；`LCR[7]` 的 DLAB；`LSR` 的 `THRE/TEMT` 恒 1；位周期 = `clockHz/lineHz`，分频
  锁存器只存不用——速率是平台参数）。接收半**没有**搬：`RBR` 读 0、`LSR.DR` 读 0，程序的输入走加载器自己的
  接收器。参数 `require(clockHz/lineHz >= 4)`（一位至少要 4 个 fabric 周期才够移出）。
- `nzea_device/src/devices/UartRx.scala`：加载器用的接收器（普通分频采样，不是 16x 过采样）。
- tile：map 上多一台 `uart16550`（`0x1000_0000`，8 字节），`DeviceKind.Uart(lineHz)` 带速率；`NzeaTileParameter`
  新增 `clockHz`——**线的速率是平台的事实**，两端被显式告知同一个数；`top` 里两份都传 25 MHz / 115200。
- fpga 顶层：`logic/BootDdr.scala`（当时的名字是 `BootSram`；magic `0xB007B007` + addr + size + words 的帧；core
  从 magic 起被挂住，直到帧的最后一个 word 被总线 ack）+ `logic/DdrMemory.scala`（DDR 版；当时的 SRAM 实现是 `CoreMemory`；tile 的
  RAM 变成 **external window**，由本层回答：两个主口——tile 与加载器）；`device/uart/UartXdc.scala` 注册
  `UART_RX` / `UART_TX` 两条约束；`bootHoldN` 以 reason 形式进复位网络（core 域等它），加载器/core/内存共用一个
  25 MHz 网格。
- 当时顶层不实例化 DDR3（soak 与 `device/ddr3/` 留在树里）；此后加载目标已迁到 DDR3：加载器改名 `BootDdr`，
  内存由 `DdrMemory` 回答（设备 + 域交叉 + whole-burst 适配），`device/ddr3/` 因此重新进了顶层。

**这轮踩到的两个坑**（都会以"程序写进去了、读回来不对"的面目出现）：

1. `RamFabricSlave` 的总线侧 ack 条件写反了：`ackSync1 =/= reqToggle` 会**回应答上一笔**——第一次读是 X，之后读到
   的永远是上一次的数据。改成 `===` 之后加载器的回读才第一次对。教训：读写回环"通了"不代表对了，**回读要拿新
   数据比对**。
2. `DdrMemory`（当时的 `CoreMemory`）的两主端口：加载器无条件优先成立的前提是 core 被挂着；但**已经获准、还在等应答**的 core 请求会
   失去主人（core 在复位里不会再接应答），内存端口就永远停在那笔请求上。解法：被挂起时 drain 掉 core 的应答
   （`mem.io.bus.resp.ready := serving ? load.resp.ready : core.resp.ready | coreHeld`），core 复位释放后自己重发。

**契约（不能悄悄变）**：程序对 `0x1000_0000` 的一次 `sw` 就是一个字节，**不轮询、不配分频**；cache 关闭时 25 MHz
的核心慢于线速（115200 下每字节 87 µs），不会丢字节；将来开 cache 就要先查 `LSR.THRE`。finisher 仍 `0x0010_0000`，
程序链在 `0x8000_0000`（`just asm2hex --test print_only <out.hex>`，`--list` 列内建程序）。

**验证**：三套测试全绿（device 10 / tile 9 / fpga 19）；`just dump --target core|tile|fpga` 全过；fpga dump 的顶层
端口 = `CLK_50M / RESET / LED1 / LED2 / UART_RX / UART_TX`，`uart.xdc` 两条约束齐。

**上板已验证（2026-10-17）**：`just fpga build`（时序全过，`All user specified timing constraints are met`）→
`just fpga program` → `just asm2hex --test print_only /tmp/print_only.hex` →
`just probe load /tmp/print_only.hex --addr 0x80000000` 的回读窗口里出现 `HELLO`；`probe monitor --reset` 再复位重跑，
再出一次 `HELLO`。ILA 抓一窗（触发 `finished=1`）：`boot/bootState=0`、`bootHoldN=1`、`coreReset=0`、`pllLocked=1`、
`finished=1`、`bootWritten=36`（与 hex 的 36 个 word 一致）——加载器写完、core 释放、程序跑到 finisher，全链闭合。
