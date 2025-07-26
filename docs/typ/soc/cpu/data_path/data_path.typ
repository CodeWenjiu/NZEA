对于要求实现的RV32I中的37条指令，我们将其分为两类，分别走在不同的流水线上
- 逻辑处理指令(add, and, lui, sll等)

#import "../../../utils.typ"

#utils.bifig(
  image("al_data_path.excalidraw.svg"),
  "逻辑处理指令数据通路",
  "graph_zh",
)

- 分支指令

#utils.bifig(
  image("ls_data_path.excalidraw.svg"),
  "访存指令数据通路",
  "graph_zh",
)

#utils.hrule()

可以观察到，这与教科书上的五级流水线不同，将后端的逻辑处理流水和访存流水分离，从而避免了逻辑处理指令必须在访存阶段空转而浪费周期。

对于riscv的访存地址，我们只需要将两个源数据(两个寄存器或者寄存器与立即数，不过ISU不需要关心来源，这些工作会被IDU完成)相加，因此AGU占用的资源相较于一个完整ALU非常少。

