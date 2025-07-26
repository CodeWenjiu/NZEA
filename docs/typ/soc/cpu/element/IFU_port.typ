#import "../port.typ"

取值模块的任务是根据PC提供指令给IDU，一开始会尝试在Icache中取指，如果没有命中则通过AXI总线请求从IROM中取指，以lru替换算法替换Icache中的指令后，再次从Icache中取指并传递给IDU

取指模块同时还集成了简单的分支预测器，在当前PC的取指完成之后，会通过分支预测更新PC并不间断的进行下一次取指，明确的Next PC会在WB阶段得到，因此IFU还需要接受WBU发出的信号来判断是否需要更新分支预测历史和进行下一次取指。

我们的ICache目前参数为8set 4way 32byte，支持通过AXI Burst transfer进行Cache替换，不过参数可调因此并不固定，目前使用的Cache替换策略为lru

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
    [IF_AXI],         port.Bundle,    [IFU发出的AXI总线请求],
    [WBU_2_IFU],      port.Flipped,   [WBU到IFU的总线信号],
    [IFU_2_IDU],      port.Decoupled, [IFU到IDU的总线信号],
  ),
  "取指模块的接口信号",
  "table_zh",
)
