下面将对各个执行器接口方向和定义分别进行详细说明。其中若无特殊说明，每个模块均隐含一个输出总线Pipeline_ctrl，用于传递给流水线控制器必要的数据

我们会在最后一个子项中集中描述各个总线的具体定义

=== 取指模块(IFU)<IFU>
#include "IFU_port.typ"
=== 译码模块(IDU)<IDU>
#include "IDU_port.typ"
=== 算数逻辑模块（ALU）<ALU>
#include "ALU_port.typ"
=== 指令派发模块（ISU）<ISU>
#include "ISU_port.typ"
=== 访存模块（LSU）<LSU>
#include "LSU_port.typ"
=== 写回模块（WBU）<WBU>
#include "WBU_port.typ"