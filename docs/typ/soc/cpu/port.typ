#let port_type_colors = (
  Input: rgb("#2ecc40").lighten(60%),
  Output: rgb("#7fdbff").lighten(60%),
  Decoupled: rgb("#e098eb").lighten(60%),
  Flipped: rgb("#c7c952").lighten(60%),
  Bundle: rgb("#f6a4a4").lighten(60%),
)

#let Input = table.cell(
  fill: port_type_colors.Input,
)[Input]

#let Output = table.cell(
  fill: port_type_colors.Output,
)[Output]

#let Decoupled = table.cell(
  fill: port_type_colors.Decoupled,
)[Decoupled]

#let Flipped = table.cell(
  fill: port_type_colors.Flipped,
)[Flipped]

#let Bundle = table.cell(
  fill: port_type_colors.Bundle,
)[Bundle]

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

- #port-type("Input", port_type_colors.Input)[
    输入信号，数据流向模块内部
  ]
- #port-type("Output", port_type_colors.Output)[
    输出信号，数据从模块流出
  ]
- #port-type("Bundle", port_type_colors.Bundle)[
    信号捆绑，包含多个子信号的复合类型
  ]
- #port-type("Decoupled", port_type_colors.Decoupled)[
    解耦总线，包含valid、ready握手协议的数据传输总线
  ]
- #port-type("Flipped", port_type_colors.Flipped)[
    翻转总线，Decoupled的镜像，通常用于接收端
  ]
