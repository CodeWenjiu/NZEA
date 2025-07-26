#import "../port.typ"

地址生成模块的任务是根据指令计算出内存地址，并将地址通过总线传递给`WBU`模块。

#import "../../../utils.typ"

#utils.bifig(
  table(
    columns: (auto, auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*信号名称*],      [*方向*],       [*功能描述*],
    [clock],          port.Input,     [系统时钟信号],
    [reset],          port.Input,     [高电平有效的同步复位信号],
    [AGU_2_LSU],      port.Flipped,   [AGU到LSU的总线信号],
    [EXU_2_WBU],      port.Decoupled, [LSU到WBU的总线信号],
    [DRAM],           port.Bundle,    [DRAM总线信号],
  )
,
  "地址生成模块的接口信号",
  "table_zh",
)
