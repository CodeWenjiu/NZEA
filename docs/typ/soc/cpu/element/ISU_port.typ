#import "../port.typ"

指令派发模块的任务是根据指令类型将指令派发给不同的处理单元，同时也负责处理单元的一些前期工作

对于ALU，ISU会将指令需要的两个源操作数从(寄存器AB，CSR，立即数，0，PC)中Mux得到，同时还会提前将逻辑数(大于、小于、相等、不相等)的结果计算出来并参与Mux，以减小ALU的工作量

对于LSU，其起到AGU的作用，负责提前将访存地址计算出来

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
    [IDU_2_ISU],      port.Flipped,   [IDU到ISU的总线信号],
    [ISU_2_ALU],      port.Decoupled, [ISU到ALU的总线信号],
    [ISU_2_LSU],      port.Decoupled, [ISU到LSU的总线信号],
  )
,
  "指令派发模块的接口信号",
  "table_zh",
)
