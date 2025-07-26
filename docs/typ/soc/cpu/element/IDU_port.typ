#import "../port.typ"

译码模块的任务是将指令转译为各个模块可以直接使用的控制信号，并将指令根据类型分发给`ALU`或者`AGU`，同时，其还会根据指令需要的操作数从`GPR`，`CSR`，`IMM`，`PC`中选择两个作为实际需要的操作数。

为了获取这些数据，`IDU`需要根据指令提取出立即数，并通过总线将需要读取的`GPR`和`CSR`地址传递给`RegFile`，以读取其数据。

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
    [IDU_2_REG],      port.Bundle,    [IDU到REG的总线信号],
    [IFU_2_IDU],      port.Flipped,   [IFU到IDU的总线信号],
    [IDU_2_EXU],      port.Decoupled, [IDU到EXU，也即AGU或ALU的总线信号],
  ),
  "译码模块的接口信号",
  "table_zh",
)
