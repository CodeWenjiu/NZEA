本处理器流水线采用valid-ready握手机制，流水线控制器对每一个流水线寄存器都传入一个PipeCtrl总线

Pipectrl的定义如下：

#import "../port.typ"

#import "../../../utils.typ"

#utils.bifig(
  table(
    columns: (auto, auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*信号名称*],      [*方向*],       [*功能描述*],
    [stall],          port.Output,    [流水线阻塞信号],
    [flush],          port.Output,    [流水线冲刷信号],
  )
,
  "寄存器堆的接口信号",
  "table_zh",
)
