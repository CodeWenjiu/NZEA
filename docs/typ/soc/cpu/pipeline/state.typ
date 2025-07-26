考虑两个模块之间的流水线状态机，分别将其命名为prev和this，数据方向从prev流向this。

定义prev的数据输出端口为prevOut，this的数据输入端口为thisIn，数据输出端口为thisOut

#import "../../../utils.typ"

#utils.hrule()
*1对1流水线*

那么thisIn.valid的状态转移方程如下：

#utils.bifig(
  image("state/valid.excalidraw.svg"),
  "thisIn.valid 的状态转移方程",
  "graph_zh",
)

#grid(
  columns: (auto, 1.4em, auto, 1.4em, auto, 1.4em, auto, 1.4em),
  gutter: 0.5em,
  align: horizon,
  [其中状态转移条件优先级为],
  box(rect(height: 1em, width: 1.4em, fill: rgb("#ff2121").lighten(60%)), baseline: 0.2em),
  [>],
  box(rect(height: 1em, width: 1.4em, fill: rgb("#295bff").lighten(60%)), baseline: 0.2em),
  [>],
  box(rect(height: 1em, width: 1.4em, fill: rgb("#36ff57").lighten(60%)), baseline: 0.2em),
  [>],
  box(rect(height: 1em, width: 1.4em, fill: rgb("#000000").lighten(60%)), baseline: 0.2em),
)

阶段之间的通信使用握手机制，通常涉及valid和ready信号。当前阶段（this）的ready信号表示其接收新数据的能力。其状态由两个因素决定：

1. *下游就绪:* 紧随其后的阶段（next）必须准备好接收数据（即next.ready必须为高）。这实现了反压，防止数据溢出。

2. *流水线控制:* 流水线控制逻辑不得为当前阶段置位stall信号。stall信号通常会暂停流水线阶段的操作。
因此，当且仅当下游阶段就绪 *且* 当前阶段未被暂停时，当前阶段的ready信号才会被置位：
this.ready = next.ready and not stall

*数据锁存:*

数据从前一个阶段（prev）传输到当前阶段（this）发生在握手成功时。当上一个阶段有有效数据（prev.valid为高，隐含）且当前阶段准备好接收数据（this.ready为高）时，握手被认为是成功的。当这个条件（prev.valid and this.ready）在一个时钟周期内满足时，prev阶段输出的数据被捕获并存储在与this阶段相关的流水线寄存器中。这确保了数据同步进行，并且仅在条件允许时进行。

#utils.hrule()
*1对n流水线*

在1对n流水线场景中，数据从单个前驱阶段（prev）根据特定条件分发到n个可能的后继阶段（this_1到this_n）中的一个。一个典型的例子是指令译码单元（IDU）根据指令类型将数据发送到算术逻辑单元（ALU）或地址生成单元（AGU）。

这种分发逻辑的关键在于：
1. *条件路由:* 每个目标分支都有一个关联的布尔条件（cond）。
2. *数据传输:* 数据（prevOut.bits）仅在源阶段有效（prevOut.valid）、源阶段就绪（prevOut.ready）且目标分支的条件（cond）为真时，才锁存到该目标分支的输入寄存器（branchIn.bits）。这对应于 prevOut.fire && cond。
3. *源就绪信号* (prevOut.ready): 源阶段的ready信号取决于下游。它仅在流水线未暂停（ctrl.stall为假）*且*至少有一个满足其条件（cond为真）的目标分支准备好接收数据（branchIn.ready为真）时，才置为高。这确保了反压机制能正确传递回源阶段。
4. *目标有效信号* (branchIn.valid): 每个目标分支输入的valid信号由一个状态机管理。
  - 当数据成功从源传输到该分支时（prevOut.fire && cond），valid置为真。
  - 当流水线被冲刷（ctrl.flush）或该分支的数据被其后续阶段消耗掉（branchOut.fire）时，valid置为假。
  - 在其他情况下，valid保持其先前状态。
  - 复位时，valid默认为假。

#utils.hrule()
*n对1流水线*

在设计多对一（n-to-1）流水线数据通路时，例如当多个功能单元（如 LSU 和 ALU）
的输出需要汇聚到同一个目标单元或流水线阶段时，必须使用仲裁器（Arbiter）。
仲裁器的作用是根据预设的优先级或调度策略，从多个请求源中选择一个，
授予其访问共享资源的权限，确保数据在每个周期内有序且无冲突地传输。

例如，若 LSU 和 ALU 都需要将结果传递给写回（Write Back）阶段或寄存器堆（Register File），
则需要在 LSU 输出、ALU 输出与目标输入之间实例化一个仲裁器。
