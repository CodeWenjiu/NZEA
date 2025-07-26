#import "../../utils.typ"

#let port_type_colors = (
  Bool: rgb("#2ecc40").lighten(60%),
  UInt: rgb("#7fdbff").lighten(60%),
  Enum: rgb("#e098eb").lighten(60%),
)

#let Bool = table.cell(
  fill: port_type_colors.Bool,
)[Bool]

#let UInt = table.cell(
  fill: port_type_colors.UInt,
)[UInt]

#let Enum = table.cell(
  fill: port_type_colors.Enum,
)[Enum]

#let port-type(name, color, content) = {
  grid(
    columns: (auto, 1.4em, 1fr),
    gutter: 0.5em,
    align: horizon,
    strong(name),
    box(rect(height: 1em, width: 1.4em, fill: color), baseline: 0.2em),
    content
  )
}

- #port-type("Bool", port_type_colors.Bool)[
    单wire
  ]

- #port-type("UInt", port_type_colors.UInt)[
    多位wire
  ]

- #port-type("Enum", port_type_colors.Enum)[
    枚举类型
  ]

=== 各个总线的信号详述

#utils.bifig(
  table(
    columns: (auto, auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*信号名称*],      [*方向*],       [*功能描述*],
    [addr],           UInt,           [下一条指令地址的地址],
  )
,
  "WBU_2_IFU总线信号",
  "table_zh",
)

#utils.bifig(
  table(
    columns: (auto, auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*信号名称*],      [*方向*],       [*功能描述*],
    [data],           UInt,           [指令数据],
    [PC],             UInt,           [指令地址],
  )
,
  "IFU_2_IDU总线信号",
  "table_zh",
)

#utils.bifig(
  table(
    columns: (auto, auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*信号名称*],      [*方向*],       [*功能描述*],
    [CSR_rdata],      UInt,           [CSR读数据],
    [GPR_Adata],      UInt,           [GPR_A的数据],
    [GPR_Bdata],      UInt,           [GPR_B的数据],
  )
,
  "REG_2_IDU总线信号",
  "table_zh",
)

#utils.bifig(
  table(
    columns: (auto, auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*信号名称*],      [*方向*],       [*功能描述*],
    [Branch],         Enum,           [分支类型],
    [MemOp],          Enum,           [内存操作类型],
    [EXU_A],          UInt,           [传递给执行单元的源操作数A],
    [EXU_B],          UInt,           [传递给执行单元的源操作数B],
    [EXUctr],         Enum,           [传递给执行单元的操作类型],
    [csr_ctr],        Enum,           [对CSR的操作类型],
    [Imm],            Enum,           [传递给执行单元的立即数],
    [GPR_waddr],      UInt,           [GPR写地址],
    [PC],             UInt,           [指令地址],
  )
,
  "IDU_2_EXU总线信号",
  "table_zh",
)

#utils.bifig(
  table(
    columns: (auto, auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*信号名称*],      [*方向*],       [*功能描述*],
    [GPR_Aaddr],      UInt,           [GPR_A读地址],
    [GPR_Baddr],      UInt,           [GPR_B读地址],
    [CSR_raddr],      UInt,           [CSR读地址],
  )
,
  "IDU_2_REG总线信号",
  "table_zh",
)

#utils.bifig(
  table(
    columns: (auto, auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*信号名称*],      [*方向*],       [*功能描述*],
    [addr],           UInt,           [内存操作的地址],
    [wdata],          UInt,           [写内存数据],
    [wen],            Bool,           [是否写内存],
    [MemOp],          Enum,           [内存操作类型],
    [PC],             UInt,           [指令地址],
    [GPR_waddr],      UInt,           [GPR写地址],
  )
,
  "AGU_2_LSU总线信号",
  "table_zh",
)

#utils.bifig(
  table(
    columns: (auto, auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*信号名称*],      [*方向*],       [*功能描述*],
    [Branch],         Enum,           [分支类型],
    [Jmp_Pc],         Enum,           [跳转地址(若跳转)],
    [MemtoReg],       Bool,           [是否读内存],
    [csr_ctr],        Enum,           [对CSR的操作类型],
    [GPR_waddr],      UInt,           [GPR写地址],
    [PC],             UInt,           [指令地址],
    [CSR_rdata],      UInt,           [CSR读数据],
    [Result],         UInt,           [EXU计算结果],
    [Mem_rdata],      UInt,           [读内存数据],
  )
,
  "EXU_2_WBU总线信号",
  "table_zh",
)

=== 各个枚举类型详述

#utils.bifig(
  table(
    columns: (auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*枚举类型*],         [*描述*],
    [Bran_NJmp],         [非跳转],
    [Bran_Jmp],          [无条件跳转],
    [Bran_Jmpr],         [寄存器基址跳转],
    [Bran_Jeq],          [相等跳转],
    [Bran_Jne],          [不相等跳转],
    [Bran_Jlt],          [小于跳转],
    [Bran_Jge],          [大于等于跳转],
    [Bran_Jcsr],         [CSR跳转],
  )
,
  "Branch枚举类型",
  "table_zh",
)

#utils.bifig(
  table(
    columns: (auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*枚举类型*],         [*描述*],
    [MemOp_1BU],         [无符号1字节],
    [MemOp_1BS],         [有符号1字节],
    [MemOp_2BU],         [无符号2字节],
    [MemOp_2BS],         [有符号2字节],
    [MemOp_4BU],         [无符号4字节],
  )
,
  "MemOp枚举类型",
  "table_zh",
)

#utils.bifig(
  table(
    columns: (auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*枚举类型*],         [*描述*],
    [EXUctr_ADD],        [加法运算],
    [EXUctr_SUB],        [减法运算],
    [EXUctr_Less_U],     [无符号小于运算],
    [EXUctr_Less_S],     [有符号小于运算],
    [EXUctr_A],          [直接输出A],
    [EXUctr_B],          [直接输出B],
    [EXUctr_SLL],        [逻辑左移运算],
    [EXUctr_SRL],        [逻辑右移运算],
    [EXUctr_SRA],        [算数右移运算],
    [EXUctr_XOR],        [异或运算],
    [EXUctr_OR],         [或运算],
    [EXUctr_AND],        [与运算],
    [EXUctr_LD],         [读内存数据],
    [EXUctr_ST],         [写内存数据],
  )
,
  "EXUctr枚举类型",
  "table_zh",
)

#utils.bifig(
  table(
    columns: (auto, 1fr),
    inset: 10pt,
    align: center,
    stroke: 1pt,
    [*枚举类型*],         [*描述*],
    [CSR_N],             [不做任何事情],
    [CSR_R1W0],          [不读写一， 目前只有 mret 符合],
    [CSR_R1W1],          [一读一写],
    [CSR_R1W2],          [一读二写， 目前只有 ecall 符合],
  )
,
  "csr_ctr枚举类型",
  "table_zh",
)

