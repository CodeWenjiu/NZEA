#import "@preview/cetz:0.3.2": canvas
#import "@preview/tidy:0.3.0"

#import "@preview/circuiteria:0.2.0"

#import "../../../utils.typ"

#utils.bifig(
  circuiteria.circuit({
    import circuiteria: *

    let default_element_w = 1.5
    let default_element_h = 1.5

    let CPU = element.block(id: "CPU", w: default_element_w, h: default_element_h,
      x: 0,
      y: 0,
      name: "CPU",
      ports: (
        north: (),
        east: (),
        south: ((id: "IF_AXI"), (id: "LS_AXI"), ),
        west: (),
      ),
      fill: util.colors.blue
    )

    let Clint = element.block(id: "Clint", w: default_element_w, h: default_element_h,
      x: (rel: 1, to: "CPU.east"),
      y: (from: "CPU-port-LS_AXI", to: "Clint_AXI"),
      name: "Clint",
      ports: (
        north: (),
        east: (),
        south: ((id: "Clint_AXI"), ),
        west: (),
      ),
      fill: util.colors.purple
    )

    let AXI_XBar = element.block(id: "AXI_XBar", w: default_element_w * 2 + 1, h: default_element_h,
      x: 0,
      y: -2.5,
      name: "AXI_XBar",
      ports: (
        north: ((id: "IF_AXI"), (id: "LS_AXI"), (id: "Clint_AXI"), ),
        east: (),
        south: ((id: "IROM_AXI"), (id: "DRAM_AXI"), ),
        west: (),
      ),
      fill: util.colors.green
    )

    element.group(
      id: "FullCPU", name: "FullCPU",
      { 
        CPU

        Clint

        AXI_XBar

        wire.wire("IFAXI", ("CPU-port-IF_AXI", "AXI_XBar-port-IF_AXI"), style: "dodge", dodge-y: -.5, directed: true)
        wire.wire("LSAXI", ("CPU-port-LS_AXI", "AXI_XBar-port-LS_AXI"), style: "dodge", dodge-y: -.3, directed: true)

        wire.wire("ClintAXI", ("AXI_XBar-port-Clint_AXI", "Clint-port-Clint_AXI"), style: "dodge", dodge-y: -.5, directed: true)
      }
    )

    wire.stub("AXI_XBar", "east", name: "AXI_Master", length: 2.5)
  }),
  "FullCPU设计框图",
  "graph_zh",
)