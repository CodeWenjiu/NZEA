#import "../port.typ"

#import "../../../utils.typ"

#utils.bifig(
  table(
    columns: (auto, auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*信号名称*],      [*方向*],       [*功能描述*],
    [GPR_read],       port.Flipped,   [GPR读信号],
    [IFU_out],        port.Flipped,   [IFU输出信号],
    [IDU_in],         port.Flipped,   [IDU输入信号],
    [ALU_in],         port.Flipped,   [ALU输入信号],
    [AGU_in],         port.Flipped,   [AGU输入信号],
    [LSU_in],         port.Flipped,   [LSU输入信号],
    [WBU_in],         port.Flipped,   [WBU输入信号],
    [WBU_out],        port.Flipped,   [WBU输出信号],
    [IFUCtrl],        port.Bundle,    [IFU流水线控制信号],
    [IDUCtrl],        port.Bundle,    [IDU流水线控制信号],
    [AGUCtrl],        port.Bundle,    [AGU流水线控制信号],
    [EXUCtrl],        port.Bundle,    [EXU流水线控制信号],
  )
,
  "流水线控制器的接口信号",
  "table_zh",
)

