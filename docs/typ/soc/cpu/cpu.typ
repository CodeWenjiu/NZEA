#import "@preview/tidy:0.4.1"
#import "@preview/cetz:0.3.2": draw, canvas
#import "@preview/circuiteria:0.2.0"
#import "../../utils.typ"

#import "@preview/pointless-size:0.1.1": zh, zihao

#import "@preview/cuti:0.2.1": show-cn-fakebold

#set heading(numbering: (..num) => if num.pos().len() < 5 {
  numbering("1.1", ..num)
})
#{
  outline(indent: auto, depth: 5, title: "目录")
}

#show: show-cn-fakebold
#set text(font: ("Times New Roman", "SimSun"), size: zh(-4)) // 小四号宋体（中文），Times New Roman（英文）

#set par(first-line-indent: (amount: 2em, all: true), leading: 1.5em) // 首行缩进2em，1.5倍行距

#show heading: set text(font: ("Times New Roman", "SimHei"))

#show heading.where(level: 1): it => [
  #set align(center)
  #set block(above: 1.5em, below: 1em) // 段前段后0.5行
  #set text(zh(3)) // 三号黑体加粗居中
  #set par(leading: 1em) // 单倍行距
  #block(it)
]

#show heading.where(level: 2): it => [
  #set block(above: 1.5em, below: 1em) // 段前段后0.5行
  #set text(zh(4)) // 四号黑体加粗居左
  #set par(leading: 1em) // 单倍行距
  #block(it)
]

#show heading.where(level: 3): it => [
  #set block(above: 1.5em, below: 1em) // 段前段后0.5行
  #set text(zh(-4)) // 小四号黑体加粗居左
  #set par(leading: 1em) // 单倍行距
  #block(it)
]

#pagebreak()
#align(center)[
  #counter(page).update(0)
  #set par(justify: false)
  #heading(numbering: none)[*快速预览简介*] 
本文面向全国大学生集成电路创新大赛——竞业达杯需求，设计并实现了一款基于RISC-V 32I指令集的五级流水线CPU架构。该处理器采用推测执行策略，包含取指、译码、派发、执行及写回五大核心模块，通过解耦总线与翻转总线架构实现模块间低耦合通信。关键技术突破包括：（1）创新性提出分支状态机与多源仲裁机制，有效解决指令分发冲突与写回竞争问题；（2）构建动态流水线控制器，通过valid-ready握手协议实现stall/flush信号智能生成，提升异常处理效率；（3）设计异步寄存器堆架构（读组合逻辑+写时序逻辑），严格遵循RV32I标准同时支持CSR扩展。功能验证表明：RTL级仿真通过率100%，支持基础指令集全功能运行。本设计为后续扩展浮点单元、定制指令集及硬件加速模块奠定了可扩展架构基础。
]

#pagebreak()

#counter(page).update(1)
#set page(numbering: "1/1", header: align(right)[ #sym.dash.em 设计报告])
#set page(
  header: context {
    let txt = [设计报告 #sym.dash.em]

    grid(
      columns: (auto, 1fr),
      column-gutter: 1em,
      align: horizon,
      txt,
      place(horizon + left)[
        #rect(width: 100%, height: .5em, radius: .25em, stroke: none)
      ]
    )
  },
  footer: context {
    grid(
      columns: (1fr, auto),
      column-gutter: 1em,
      align: horizon,
      place(horizon + left)[
        #rect(width: 100%, height: .5em, radius: .25em, stroke: none)
      ],
      counter(page).display("1/1", both: true)
    )
  },
)

#show figure.where(kind: "graph_zh"): it => {
  let capzh = it.caption.body.value
  let c = it.counter.display("1")
  it.body
  parbreak()
  [图#c\-#capzh]
}

#show figure.where(kind: "table_zh"): it => {
  let capzh = it.caption.body.value
  let c = it.counter.display("1")
  it.body
  parbreak()
  [表#c\-#capzh]
}

= 项目概述
== 项目背景
本文档介绍了*逆舟冰箱小队*的 RISCV 32I 五级流水线处理器设计
== 设计目标
本项目旨在设计一款基于 RISC-V 32I 指令集的五级流水线 CPU，支持基本指令集的全功能运行。该处理器采用推测执行策略，包含取指、译码、派发、执行及写回五个流水阶段，通过解耦总线与翻转总线架构实现模块间低耦合通信。
== 设计平台说明
本项目基于*Vivado 2023.2*进行设计，使用*Verilog HDL*语言进行描述，采用*Xilinx xc7k325tffg900-2* FPGA作为目标平台。设计中ip核使用了*System Verilog*和*VHDL*描述。

= RISC-V SOC架构设计
#include "schmetic/soc.typ"

上述设计图没有具体地规定模块之间的相应接口，但清晰地刻画了各个模块之间的关系和联系，表现了数据流在各个模块之间的大致方向。

根据以上设计图，我们的SOC分成以下主要模块：
- 核心处理器(CPU)
- RISCV内置定时器(Clint)
- AXI仲裁器(AXI_Xbar)
- AXI跨频器(AXI_cdc)
- AXI到IROM/DRAM的总线转换器(AXI_Wrapper)

== CPU设计

#include "schmetic/cpu.typ"

根据以上设计图，我们的CPU分成以下主要模块：

- 顶层模块（CPU）
- 取值单元（#link(<IFU>, [IFU])）
- 指令译码单元（#link(<IDU>, [IDU])）
- 指令派发单元（#link(<ISU>, [ISU])）
- 算数逻辑单元（#link(<ALU>, [ALU])）
- 访存单元（#link(<LSU>, [LSU])）
- 写回单元（#link(<WBU>, [WBU])）
- 寄存器堆（#link(<RegFile>, [RegFile])）
- 流水线控制器（#link(<Pipeline_Ctrl>, [Pipeline_Ctrl])）

其中IFU,IDU,ISU为前端，ALU,LSU和WBU为后端，共同组成了执行单元（#link(<EXECUTOR>, [EXU])）。

#utils.hrule()

接下来我们将对各个模块的信号名称、方向和功能进行详细说明。

其中方向有五种类型
#include "port.typ"

#set table(
  stroke: none,
  gutter: 0.2em,
  fill: (x, y) =>
    if y == 0 { rgb("#8fbdc5") },
  inset: (right: 1.5em),
)

#show table.cell: it => {
  if it.y == 0 {
    set text(white)
    strong(it)
  } else if it.body == [] {
    // Replace empty cells with 'N/A'
    pad(..it.inset)[_N/A_]
  } else {
    it
  }
}

#include "cpu_port.typ"

== 指令集支持
RV32I，或者可选的RV32E

== 执行模块<EXECUTOR>

#include "element/element.typ"

== 数据通路

#include "data_path/data_path.typ"

== 寄存器堆<RegFile>

#include "regfile/regfile.typ"

== 流水线<Pipeline_Ctrl>

#include "pipeline/pipeline.typ"

== 总线信号表

#include "bus_signal.typ"

= 性能优化

#include "performence/main.typ"

= 特色功能
== AXI总线

cpu支持输出标准AXI4Full总线，不过目前要求使用官方的IROM和DRAM总线，因此删除了不需要的总线与仲裁器。

在使用AXI总线的情况下，cpu可配置特定内存区域为Icache缓存区域，此后如果在这个区域内取指，则会尝试从Icache中直接取指。
如果cachemiss了，则会发起AXI总线请求来取指。

