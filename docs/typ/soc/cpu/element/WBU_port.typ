#import "../port.typ"

写回模块的任务是将运算结果写回寄存器堆中。`WBU`模块的输入信号包括来自`ALU`和`LSU`的运算结果和状态标志位。输出信号则是写回寄存器堆的控制信号和数据。

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
    [EXU_2_WBU],      port.Flipped,   [EXU，即ALU或LSU到WBU的总线信号],
    [WBU_2_IFU],      port.Decoupled, [WBU到IFU的总线信号],
    [WBU_2_REG],      port.Bundle,    [Regfile写入总线信号],
  )
,
  "写回模块的接口信号",
  "table_zh",
)
