# 板级专用构建钩子（开放问题，暂缓）

Status: **已暂缓 — 只记录问题**。

在 DDR3 接入过程中提出：`vivado-project.nu` 长出了 lxb_artix7 专用的内联逻辑。这份文档有意只记录**问题和选项空间，不做决定**。特意不写建议，是为了让后来接手的人（大概率手上有第二块板）不被"样本量为 1 的结论"锚定。

## 背景：当前分层

Vivado 工程生成流水线横跨四层，每层都持有一些板级知识：

| 层 | 文件 | 当前持有的板级知识 |
|---|---|---|
| 芯片数据库 | `nzea_fpga/scripts/chips.nu` | 纯数据：vendor、板段、顶层模块、基础 XDC（`cst`）、vivado part |
| 根构建脚本 | `nzea_fpga/scripts/vivado-project.nu` | **内联特判**：DDR3 处理（47–75 行），以 `<board>/ddr3_ref/` 文件存在为开关，含 5 个硬编码 XDC 端口改名替换；还有一个硬编码的 `ip_clk_wiz.tcl` 路径 |
| Edalize 包装 | `nzea_fpga/tools/vivado/vivado-project.py` | 只有通用 flag：`--xdc`（可重复）、`--ip-tcl`（可重复）、`--mig-prj`（可选单值） |
| Scala 生成 | `nzea_fpga/src/FpgaElaborate.scala` | 对 `FpgaBoard` 枚举做 `match`；仅 Gowin 的 `renameAluForGowin` 后处理；`debugIla` 时写 `ila_config.json` / `ila_capture.tcl` / `ila_probes.map` |

## 观察到的问题

### P1 — DDR3 是唯一需要*变换*的特化

另外两个板级新增是纯"文件在场"约定：

- clk_wiz：板目录里有 `ip_clk_wiz.tcl` → 透传。
- ILA：RTL 里例化了 `IlaProbes` → firtool 生成 `IlaProbes.sv`，Scala 写 `ila_config.json` → 文件在场就是开关，IP 永远和 RTL 匹配，无需额外 flag。

只有 DDR3 需要脚本期*派生*一个构建输入（重写的 XDC）。这个不对称就是它要 29 行、而其他只要 3 行的原因。

开放问题：目标应该是让 DDR3 变得和其他两个一样，还是"变换"本身就是一类不可避免的板级特化、值得有自己的机制？

### P2 — RTL 位宽知识泄漏进构建脚本

5 个替换里有 4 个是剥离 `[0]` 下标（`{DDR3_ck_p[0]}` → `{DDR3_ck_p}`，`ck_n`/`cke`/`odt` 同理）。对照一次生成产物验证：`DDR3_dq[0]` 和 `DDR3_addr[14]` 保留下标，因为它们是真正的向量；恰好那 4 个引脚塌成标量——**因为它们在 `Ddr3CtrlIo` 里声明为 `UInt(1.W)`，而 firtool 把 1 位 `UInt` 生成成标量端口**。

所以这套规则是 RTL 声明细节的函数。把 `ck_p` 改成 `Bool()`，或加宽，会静默地让规则失效；构建里没有任何东西会报出来。

开放问题：应该动哪边——RTL 端口形状去适配 vendor 约束模板（MIG 的 example design 把 `ddr3_ck_p` 声明为 `output [0:0]`，所以 1 元素向量才是贴合 vendor 的形状，能把整个变换降成一个统一的前缀改名），还是模板去适配 RTL？两者在项目生命周期里的成本未知；无论哪个，板上重新验证都很贵。

### P3 — 变换是钝器，且不跟踪改名

模板里有 167 处 `ddr3_`，其中 3 处在被注释掉的 `ddr3_cs_n[0]` 行里；盲目的 `str replace -a "ddr3_" "DDR3_"` 把那些也改了。更重要的是它不跟随字段改名：在 `Ddr3CtrlIo` 里把 `ck_p` 改成 `clk_p`，XDC 仍然约束 `DDR3_ck_p`，Vivado 只报一个"端口不匹配"的警告。

开放问题：构建期变换到底能不能跟踪 RTL，还是跟踪只能靠从 Scala 的 bundle 定义派生规则（或整个 XDC）——就像 `ila_config.json` 从 `IlaProbes.widths` 派生那样？

### P4 — 开关是隐式的、单向的

DDR3 的开关条件是 `ddr3_ref/srcs/.../mig_b.prj` 和 `ddr3_ref/mig_ddr3.xdc` 两个文件都存在。`chips.nu` 里没有任何东西表明 lxb_artix7 有 DDR3；关掉它的唯一办法是删除或移动参考文件。

开放问题：文件在场驱动是特性（无需同步的 flag，和 ILA 同一哲学）还是负债（不可发现、无法关闭）？注意两个现有先例精神上互相矛盾：ILA 是"文件在场"*且*上游还有一个显式的 `debugIla` 开关。

### P5 — 约束/RTL 不一致暴露得太晚

接入过程中，一个 bank 类型冲突（`[DRC BIVB-1]`：`UART_RX` 的 LVCMOS33 落在一个被 MIG 模板约束成 High Performance / SSTL15 的 bank）直到 Vivado DRC 阶段才出现——那已经是综合跑完之后了。这种跨文件约束冲突靠读任何单个文件都检测不出来。

开放问题：XDC↔RTL 一致性（端口名，理想情况下还有 bank/IOSTANDARD 兼容性）应该在哪里验证、由谁验证？在 dump 期而不是 DRC 期就能抓住 P2/P3 这类漂移的最便宜检查是什么？这样的检查是跨板/跨约束文件通用的，还是 DDR 专用的？

### P6 — 不对称完全在 nushell 层

`vivado-project.py` 已经接受可重复的 `--xdc` / `--ip-tcl` 和一个可选的 `--mig-prj`。Edalize 自己的文件集/工具选项模型可能已经覆盖了一部分。无论选什么机制，Python 层很可能除了让 `--mig-prj` 变成可重复（万一某板需要两个内存 IP 实例）之外无需改动。

## 目前观察到的特化类型（描述性清单）

列出来是为了让"什么算板级特化"有个登记册。这是现状的记录，不代表这个划分就是正确的抽象。

1. **约束变换** — 把 vendor/参考 XDC 适配到生成顶层的端口名（DDR3）。形态：替换，或改名映射。
2. **从 vendor 配置生成 IP** — 从 XML `.prj` 生成 MIG 7-series。
3. **从板级 TCL 片段生成 IP** — clk_wiz。
4. **由 RTL 例化驱动的 IP 生成** — 通过 `ila_config.json` 的 ILA。
5. **附加约束文件** — 除板级基础外的 `ddr3_pins.xdc`。
6. **后 RTL 处理** — 综合前对 firtool 输出的编辑（Gowin ALU 模块改名）。
7. **器件/part/时钟解析** — 已经数据驱动（`chips.nu`）；时钟结构（MMCM）在板级 Top RTL 里。

## 机制空间（已枚举，刻意不排序）

- **A. 约定式 nushell 钩子** — 可选的 `<board>/build-hooks.nu` 导出固定契约（如 `prepare [rtl_dir] -> record`）；根脚本在场时 source。Nushell 没有 trait，但 source 后的命令存在性模拟了"有实现 → 调用它"。给足命令式自由；契约是字符串类型的，每块板的钩子是没有编译器检查的定制代码。
- **B. 数据驱动描述符** — `chips.nu` 的板记录增加结构化字段（`extra_xdcs`、`mig_prj`、带封闭 `kind` 值集合的 `ip: [...]` 列表）；根脚本解释已枚举的 kind，对未知 kind 硬报错。声明式、可 diff；真正全新的特化类型仍需要解释器分支。
- **C. Scala 侧板 trait** — `FpgaBoard` 增加返回构建描述符的方法，写成 JSON 放在 RTL 旁；脚本变成哑执行器。类型检查、与 part 名/探针宽度/顶层模块名同处；扩宽了 Scala→脚本数据接口，并有与 `chips.nu` 作为第二个板数据库分叉的风险。
- **D. 消除变换** — 离线跑一次重写，把结果作为板级约束文件提交（紧挨 `A7_lite.xdc`），只把 `mig_b.prj` 留作构建期 vendor 输入。逻辑整个消失；已提交 XDC 与 RTL 之间的漂移成为开放风险（见 P5）。

## 实现前要解决的开放问题

1. 端口名契约归谁：RTL bundle、vendor 模板，还是提交的板级 XDC？（P2, P3, D）
2. 板级钩子允许是代码，还是只能文件+数据条目？如果允许代码，什么机制防止无节制增长？（A vs B）
3. 特化类型登记册放在哪、谁可以扩展？（本文档是初始记录）
4. `chips.nu` 该保持单一板数据库，还是按板惰性 source 记录以保持精简？（B vs C）
5. edalize 的文件集/工具选项抽象是否已经吸收类型 2–4 的一部分，让 `vivado-project.py` 直接消费描述符而不是继续增长 flag？（P6）
6. dump 期该跑什么验证来在 Vivado 之前抓住约束↔RTL 不一致？（P5）
7. `renameAluForGowin`（类型 6）应该并入同一机制，还是保留为 Scala 侧特例——因为它在 `dump` 期操作 firtool 输出、而非工程生成？

## 重新审视的触发条件

第二块板需要上述任一类型，**或者** `Ddr3CtrlIo` 引脚名/宽度变化迫使碰这个变换。在那之前，内联块是工作的、且已在硬件上验证；为重构而重构没有收益，还冒着板上重新验证周期的风险。

## 相关

- `goal/ddr3-debug-port-removal.md` — 同样的姿态（暂缓清理，记录以免遗忘），同样以上板调试收敛为门槛。
- `goal/ddr3-debug-postmortem.md` — 适配器/时序接入史（地址映射、写 drain、ui 域复位）。它**不**覆盖这里记录的约束文件问题；P2/P5 来自 Vivado 工程生成侧，那边还没有自己的复盘。
