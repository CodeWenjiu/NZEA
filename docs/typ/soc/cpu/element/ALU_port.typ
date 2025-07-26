#import "../port.typ"

算数逻辑模块的任务时根据需要执行加法，减法，移位等操作，并将结果通过总线传递给`WBU`模块。`ALU`模块的输入信号包括来自`IDU`的指令和操作数。输出信号则是运算结果和状态标志位。

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
    [ISU_2_EXU],      port.Flipped,   [ISU到ALU的总线信号],
    [EXU_2_WBU],      port.Decoupled, [ALU到WBU的总线信号],
  ),
  "算数逻辑模块的接口信号",
  "table_zh",
)
