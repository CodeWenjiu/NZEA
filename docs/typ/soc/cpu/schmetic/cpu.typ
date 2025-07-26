#import "@preview/cetz:0.3.2": canvas
#import "@preview/tidy:0.3.0"

#import "@preview/circuiteria:0.2.0"

#import "../../../utils.typ"

#utils.bifig(
  circuiteria.circuit({
    import circuiteria: *

    let default_element_w = 1.5
    let default_element_h = 1.5

    let Pipeline_ctrl = element.block(id: "Pipeline_ctrl", w: 13, h: 1.5,
      x: 0,
      y: 0,
      name: "Pipeline_ctrl",
      ports: (
        north: (),
        east: (),
        south: ((id: "front_ctrl"), (id: "back_ctrl"), ),
        west: (),
      ),
      fill: util.colors.orange
    )

    let Regfile = element.block(id: "Regfile", w: 13, h: 1.5,
      x: 0,
      y: -8,
      name: "Regfile",
      ports: (
        north: ((id: "to_Front"), (id: "from_Back"), ),
        east: (),
        south: (),
        west: (),
      ),
      fill: util.colors.orange
    )

    let Front = element.group(
      id: "Front", name: "Front                                                  ", name-anchor: "south", stroke: (dash: "dashed"),
      {
        element.block(id: "IFU", w: default_element_w, h: default_element_h,
          x: (rel: 0, to: "Pipeline_ctrl.west"),
          y: -4,
          name: "IFU",
          ports: (
            north: ((id: "north"),),
            east: ((id: "toIDU"),),
            south: ((id: "nextpc"), (id: "gpr_raddr")),
            west: (),
          ),
          fill: util.colors.orange
        )
    
        element.block(id: "IDU", w: default_element_w, h: default_element_h,
          x: (rel: 1.5, to: "IFU.east"),
          y: (from: "IFU-port-nextpc", to: "csr_raddr"),
          name: "IDU",
          ports: (
            north: ((id: "WB_Bypass"),),
            east: ( (id: "toISU"), ),
            south: ((id: "gpr"), (id: "csr_raddr"), ),
            west: ((id: "toIDU"), ),
          ),
          fill: util.colors.blue
        )

        wire.wire("toIDU", ("IFU-port-toIDU", "IDU-port-toIDU"), style: "zigzag", directed: true)

        element.block(id: "ISU", w: default_element_w, h: default_element_h,
          x: (rel: 1.5, to: "IDU.east"),
          y: (from: "IDU-port-gpr", to: "south"),
          name: "ISU",
          ports: (
            north: ((id: "north"), ),
            east: ((id: "toALU"), (id: "toLSU"), ),
            south: ((id: "south"), ),
            west: ((id: "fromIDU"),),
          ),
          fill: util.colors.purple
        )

        wire.wire("toISU", ("IDU-port-toISU", "ISU-port-fromIDU"), style: "zigzag", directed: true)
      }
    )

    let Back = element.group(
      id: "Back", name: "Back                     ", name-anchor: "south", stroke: (dash: "dashed"),
      {
        element.block(id: "ALU", w: default_element_w, h: default_element_h,
          x: (rel: 1.5, to: "ISU.east"),
          y: (from: "ISU-port-toALU", to: "csr"),
          name: "ALU",
          ports: (
            north: (),
            east: ((id: "toWBU"),),
            south: ((id: "csr"),),
            west: ((id: "fromISU"),),
          ),
          fill: util.colors.pink
        )
        
        element.block(id: "LSU", w: default_element_w, h: default_element_h,
          x: (rel: 1.5, to: "ISU.east"),
          y: (from: "ISU-port-toLSU", to: "north"),
          name: "LSU",
          ports: (
            north: ((id: "north"), ),
            east: ((id: "toWBU"), (id: "BUS_DRAM"),),
            south: (),
            west: ((id: "fromISU"), ),
          ),
          fill: util.colors.purple
        )
        
        element.block(id: "WBU", w: default_element_w, h: default_element_h,
          x: (rel: 1.5 * 2 + (default_element_w / 2), to: "ISU.east"),
          y: (from: "IDU.west", to: "east"),
          name: "WBU",
          ports: (
            north: ((id: "WB_Bypass"), ),
            east: ((id: "east"), ),
            south: (),
            west: ((id: "fromALU"), (id: "fromLSU")),
          ),
          fill: util.colors.green
        )

        wire.wire("toWBU", ("ALU-port-toWBU", "WBU-port-fromALU"), style: "zigzag", directed: true)

        wire.wire("toWBU", ("LSU-port-toWBU", "WBU-port-fromLSU"), style: "zigzag", directed: true)
      }
    )

    element.group(
      id: "CPU", name: "CPU",
      { 

        Pipeline_ctrl

        Regfile

        Front
        
        Back

        wire.wire("toLSU", ("ISU-port-toLSU", "LSU-port-fromISU"), style: "zigzag", directed: true)
        wire.wire("toALU", ("ISU-port-toALU", "ALU-port-fromISU"), style: "zigzag", directed: true)

        wire.wire("toFront", ("Pipeline_ctrl-port-front_ctrl", "Front.north-west"), style: "dodge", dodge-y: -.5, dodge-margins: (0%, 0%), directed: true)

        wire.wire("toFront", ("Regfile-port-to_Front", "Front.south-west"), style: "dodge", dodge-y: -5.5, dodge-margins: (0%, 0%), directed: true) 

        wire.wire("toBack", ("Pipeline_ctrl-port-back_ctrl", "Back.north-east"), style: "dodge", dodge-y: -.5, dodge-margins: (0%, 0%), directed: true)

        wire.wire("fromBack", ("Back.south", "Regfile-port-from_Back"), style: "dodge", dodge-y: -5.8, dodge-margins: (0%, 0%), directed: true) 

        wire.wire("WB_Bypass", ("WBU-port-WB_Bypass", "IDU-port-WB_Bypass"), style: "dodge", dodge-y: -.9, dodge-margins: (0%, 0%), directed: true)
      }
    )

    wire.stub("IFU", "west", name: "IF_AXI", length: 2)
    wire.stub("LSU-port-BUS_DRAM", "east", name: "LS_AXI", length: 4.5)

  }),
  "CPU设计框图",
  "graph_zh",
)
