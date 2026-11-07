// nzea 周报 · 2026-11-06
// 编译：typst compile report/weekly-2026-11-06/main.typ

#let accent = rgb("#2f6f9f")
#let accent-soft = rgb("#eaf1f7")
#let ink = rgb("#1b2430")
#let muted = rgb("#6b7280")
#let rule-c = rgb("#dfe3e9")
#let warn = rgb("#a3562a")
#let good = rgb("#2f7d57")
#let panel = rgb("#f4f7fa")

#set page(
  width: 280mm,
  height: 157.5mm,
  margin: (x: 16mm, y: 13mm),
  fill: rgb("#fcfcfe"),
  footer: context [
    #set text(size: 8.5pt, fill: muted)
    #grid(
      columns: (1fr, auto),
      [nzea · 周报 · 2026-11-06],
      [#counter(page).display()],
    )
  ],
)

#set text(font: ("Noto Sans CJK SC", "DejaVu Sans"), size: 12pt, fill: ink)
#set par(leading: 0.62em)
#show raw: set text(font: ("DejaVu Sans Mono", "Noto Sans CJK SC"), size: 8.8pt)

#show heading.where(level: 1): it => [
  #pagebreak(weak: true)
  #text(size: 20pt, weight: "bold", fill: accent)[#it.body]
  #v(1.6mm)
  #line(length: 100%, stroke: 0.7pt + rule-c)
  #v(4.5mm)
]

#let kpi(value, label, note: none, height: 29mm) = block(
  width: 100%,
  height: height,
  inset: 3.2mm,
  radius: 2mm,
  fill: panel,
  stroke: 0.5pt + rule-c,
)[
  #text(size: 19pt, weight: "bold", fill: accent)[#value]
  #v(1.2mm)
  #text(size: 9.5pt)[#label]
  #if note != none {
    v(0.8mm)
    text(size: 8.2pt, fill: muted)[#note]
  }
]

#let tbody() = (
  inset: (x: 2.2mm, y: 1.9mm),
  stroke: (x, y) => if y == 0 { (bottom: 0.8pt + rule-c) } else { (bottom: 0.35pt + rgb("#eceff3")) },
)

// ---------------------------------------------------------------- 封面

#v(30mm)
#text(size: 11pt, fill: muted)[nzea · 每周汇报]
#v(3mm)
#text(size: 34pt, weight: "bold")[片外访存延迟：校准与瓶颈归因]
#v(4mm)
#line(length: 60mm, stroke: 1.4pt + accent)
#v(5mm)
#text(size: 13pt, fill: muted)[2026-11-06 · 过去一周的工作]

// ---------------------------------------------------------------- 概述

= 本周工作概述

#grid(
  columns: (1fr, 1fr),
  gutter: 6mm,
  [
    #text(weight: "bold", fill: good)[已完成]
    #v(1.5mm)
    - 简化延迟模型：设备与总线的时序改由硬件描述本身表达，模型只保留两处仿真替代硬件的延迟
    - 在板上实测 DDR 访存延迟，并回填到模型中
    - 板级功能验证：烧录、串口输出、DDR 读写测试全部通过
    - 相关研究调研：三类共 17 篇，梳理出与已有工作的差异
  ],
  [
    #text(weight: "bold", fill: warn)[未完成 / 待决策]
    #v(1.5mm)
    - 真实的机械臂负载尚未在板上运行
    - 板级平台缺少外设窗口，机械臂相关设备目前只存在于仿真
    - 系统时间基准与软件假设相差 10 倍
    - 用于与已有工作横向对比的基准尚未确定
  ],
)

#v(8mm)
#text[
  本周的核心变化是：*延迟不再是一组可以手工调整的参数，而是由产生它的硬件决定，并能追溯到板上实测。*
]

// ---------------------------------------------------------------- 校准

= 延迟校准：做法与结果

#grid(
  columns: (1fr, 1.05fr),
  gutter: 8mm,
  [
    #text(size: 10.5pt, fill: muted)[做法]
    #v(2mm)
    - 设备的协议时序（UART 字节、I2C 帧）由硬件状态机实现，仿真与综合共用同一份实现
    - 只有板上不存在、由仿真替代的硬件，才保留一个延迟数值
    - 该数值来自板上实测，而不是经验估计
  ],
  [
    #text(size: 10.5pt, fill: muted)[一次访问的实测成本]
    #v(2mm)
    #table(
      columns: (1fr, auto),
      ..tbody(),
      table.header([*通路*], [*耗时*]),
      [片上 DDR 访存（整条通路）], [230 ns（23 拍），最坏 540 ns],
      [I2C 读一个寄存器], [165 µs（66 bit × 2.5 µs）],
      [I2C 写一个寄存器], [140 µs（56 bit × 2.5 µs）],
      [UART 发送一个字节], [87 µs（10 bit × 8.7 µs）],
      [定时器 / 退出寄存器], [1 拍（无额外延迟）],
    )
  ],
)

#v(4mm)
```scala
// 设备声明自己在仿真中的代价；默认没有
trait HasBridgeDelay[P]:
    def bridgeDelay(p: P): Option[Delay] = None

// 全平台只剩两处这样的声明，这是其中一处：板上 DDR 通路
override def bridgeDelay(p: RamFabricSlaveParameter): Option[Delay] =
    Some(Delay(hz = 100_000_000, cycles = 23))
```

#v(2mm)
#text(fill: muted)[
  对照：修改前的模型把 DDR 访存估为 12 拍，实测为 23 拍，低估约一倍；漏掉的部分来自通道仲裁、跨时钟域和突发适配。
]

// ---------------------------------------------------------------- 基线

= 仿真基线：计算与总线的比例

#grid(
  columns: (1fr, 1fr, 1fr, 1fr),
  gutter: 4mm,
  kpi([3,846,833], [200 万条指令的总周期]),
  kpi([0.52], [每周期指令数]),
  kpi([16.2%], [总线占用], note: [624,968 拍]),
  kpi([88%], [总线时间中扫描设备的份额], note: [552,420 拍]),
)

#v(6mm)
#let total = 160mm
#let used = total * 0.162
#text(size: 9.5pt, fill: muted)[计算 : 总线 = 5.15 : 1]
#v(1.5mm)
#box(width: total, height: 6mm, fill: rgb("#e9edf2"), radius: 1mm)[
  #place(top + left, rect(width: used, height: 6mm, fill: accent, radius: 1mm))
]
#v(1mm)
#grid(
  columns: (1fr, auto),
  text(size: 8.5pt, fill: muted)[每 1,000 条指令约有 312 拍花在总线上],
  text(size: 8.5pt, fill: accent)[扫描设备每次读 = 66 bit × 2.5 µs = 165 µs],
)

#v(4mm)
#text[
  结论：当前负载下总线尚未成为主要瓶颈，但*扫描设备的逐寄存器读取占据了总线时间的 88%*。
  因此研究重点应从「总线是否重要」转向「哪种访问粒度适合放在总线上」。
]

// ---------------------------------------------------------------- 相关工作

= 相关工作：三类已有研究

#v(1mm)
#text(size: 10.5pt)[
  #table(
    columns: (3.3cm, 1.5fr, 1fr),
    ..tbody(),
    table.header([*方向*], [*代表性工作*], [*与本工作的差异*]),
    [片外延迟建模],
    [gem5（2011）、DRAMSim2（2011）、Ramulator（2016/2024）、实时系统片外存储延迟分析（RTSS 2013/2018）、EtherCAT（2004）],
    [把总线视为带宽与延迟参数，或分析最坏上界；本工作关心典型值，并把它与硬件实现绑定],
    [机器人控制芯片],
    [TI C2000 F28P65x（双 C28x + CLA）、Mr. Wolf（ESSCIRC 2018）、安全 nano-UAV SoC（TCSI 2024）、RISC-V EtherCAT 实时基准（Electronics 2024）],
    [多数把电机控制外设集成在片内，或改用工业以太网；本工作把外设放在片外并量化代价],
    [性能模型校准],
    [gem5 精度评估（ReCoSoC 2012）、gem5 x86 验证（PMBS 2019）、FireSim（ISCA 2018）、Roofline（CACM 2009）、CAN 可调度性分析（Davis 等，2007）],
    [校准对象多为处理器与存储层次，指标是 IPC 误差；本工作只校准一条通路，数值可逐项追溯],
  )
]

#v(1.5mm)
#text(size: 8.5pt, fill: muted)[
  以上共 17 条，均核对过 DOI；完整清单见研究笔记。
]

#v(3mm)
#text(size: 12pt)[
  相关研究已覆盖「帧占用」「实测校准」与「计算/带宽比值」；本工作的新意在于*延迟的归属方式*及其归因结论。
]

#v(3mm)
#grid(
  columns: (1fr, 1fr),
  gutter: 6mm,
  block(fill: rgb("#fbf1ec"), radius: 1.5mm, inset: 2.5mm, width: 100%)[
    #text(weight: "bold")[不能主张]
    #v(1mm)
    「首次建模总线延迟」「首次校准仿真器」「计算与带宽的比值决定瓶颈」——均已有成熟工作。
  ],
  block(fill: rgb("#eef6f1"), radius: 1.5mm, inset: 2.5mm, width: 100%)[
    #text(weight: "bold")[可以主张]
    #v(1mm)
    仿真与真板的差异只出现在板上不存在的硬件，因此可以逐项指认；并且能在机械臂负载上把瓶颈归因到访问粒度。
  ],
)

// ---------------------------------------------------------------- 计划

= 下一步计划

#v(1mm)
#table(
  columns: (1fr, 1fr, 1.2fr),
  ..tbody(),
  table.header([*事项*], [*产出*], [*说明*]),
  [在自有核上运行计算基准], [CoreMark/MHz，可与其他 RISC-V 核同口径比较], [仿真即可，不依赖板级改动],
  [建立访存延迟基准], [指针追逐测得的 ns 级延迟曲线], [与已有存储延迟研究同口径],
  [补齐外部对比表], [速度 / 功耗 / 面积 / 集成度 对照], [对标 C2000 与一个 RISC-V 核；部分数据引用公开资料],
  [板上运行真实负载], [100 MHz 下的计算与总线比例], [依赖板级外设窗口补齐],
)

