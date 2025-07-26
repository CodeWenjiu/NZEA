#import "port.typ"

顶层模块是整个CPU的最外层封装，用于连接各个功能模块，协调数据流动和控制信号传递。以下是顶层模块的接口信号：

#import "../../utils.typ"

#utils.bifig(
  table(
    columns: (auto, auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*信号名称*],      [*方向*],    [*功能描述*],
    [clock],          port.Input,  [系统时钟信号],
    [reset],          port.Input,  [高电平有效的同步复位信号],
    [IF_AXI],           port.Bundle, [IFU发出的AXI Master总线],
    [LS_AXI],           port.Bundle, [LSU发出的AXI Master总线],
  ),
  "顶层模块的接口信号",
  "table_zh",
)
