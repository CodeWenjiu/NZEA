#import "../port.typ"

寄存器堆的输入输出端口定义如下：

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
    [REG_2_IDU],      port.Bundle,    [REG到IDU的总线信号],
    [WBU_2_REG],      port.Bundle,    [WBU到REG的总线信号],
  )
,
  "寄存器堆的接口信号",
  "table_zh",
)

