package riscv_soc.platform.jyd

import chisel3._
import chisel3.util._

import config.Config
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import riscv_soc.bus.AXI4ToAPB

import riscv_soc.peripheral._
import scopt.platform
import riscv_soc.HasCoreModules
import riscv_soc.CoreConnect
import freechips.rocketchip.amba.axi4.AXI4Bundle
import riscv_soc.CPUAXI4BundleParameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import riscv_soc.bus
import _root_.peripheral.UART
import riscv_soc.cpu.EXUctr_Field

class apb_peripheral extends BlackBox {
  val io = IO(new Bundle {
    val Pclk = Input(Clock())
    val Prst = Input(Bool())
    val Paddr = Input(UInt(32.W))
    val Pwrite = Input(Bool())
    val Psel = Input(Bool())
    val Penable = Input(Bool())
    val Pwdata = Input(UInt(32.W))
    val Pstrb = Input(UInt(4.W))
    
    val Prdata = Output(UInt(32.W))
    val Pready = Output(Bool())
    val Pslverr = Output(Bool())

    val LED = Output(UInt(32.W))
    val SW1 = Input(UInt(32.W))
    val SW2 = Input(UInt(32.W))
    val SEG = Output(UInt(32.W))
  })
}

class display_seg extends BlackBox {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val s = Input(UInt(32.W))
    val seg1 = Input(UInt(7.W))
    val seg2 = Input(UInt(7.W))
    val seg3 = Input(UInt(7.W))
    val seg4 = Input(UInt(7.W))
    val ans = Input(UInt(8.W))
  })
}

class peripheral extends Bundle {
  val LED = Output(UInt(32.W))
  val SW1 = Input(UInt(32.W))
  val SW2 = Input(UInt(32.W))
  val SEG = Output(UInt(32.W))
}

class ApbPeripheralWrapper(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val beatBytes = 4
  val node = APBSlaveNode(Seq(APBSlavePortParameters(
    Seq(APBSlaveParameters(
      address       = address,
      executable    = true,
      supportsRead  = true,
      supportsWrite = true)
    ),
    beatBytes  = beatBytes)))
      
  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val APB = node.in(0)._1

    val apb_peripheral_0 = Module(new apb_peripheral())
    apb_peripheral_0.io.Pclk := clock
    apb_peripheral_0.io.Prst := reset

    apb_peripheral_0.io.Paddr <> APB.paddr
    apb_peripheral_0.io.Pwrite <> APB.pwrite
    apb_peripheral_0.io.Psel <> APB.psel
    apb_peripheral_0.io.Penable <> APB.penable
    apb_peripheral_0.io.Pwdata <> APB.pwdata
    apb_peripheral_0.io.Pstrb <> APB.pstrb
    
    APB.prdata <> apb_peripheral_0.io.Prdata
    APB.pready <> apb_peripheral_0.io.Pready
    APB.pslverr <> apb_peripheral_0.io.Pslverr

    val peripheral = IO(new peripheral())
    peripheral.SW1 <> apb_peripheral_0.io.SW1
    peripheral.SW2 <> apb_peripheral_0.io.SW2
    peripheral.SEG <> apb_peripheral_0.io.SEG
    peripheral.LED <> apb_peripheral_0.io.LED
  }
}

class System_RAM extends BlackBox {
  val io = IO(new Bundle {
    val rsta_busy       = Output    (Bool())
    val rstb_busy       = Output    (Bool())

    val s_aclk          = Input     (Clock())
    val s_aresetn       = Input     (Bool())

    val s_axi_awid      = Input     (UInt(4.W))
    val s_axi_awaddr    = Input     (UInt(32.W))
    val s_axi_awlen     = Input     (UInt(8.W))
    val s_axi_awsize    = Input     (UInt(3.W))
    val s_axi_awburst   = Input     (UInt(2.W))
    val s_axi_awvalid   = Input     (Bool())
    val s_axi_awready   = Output    (Bool())

    val s_axi_wdata     = Input     (UInt(32.W))
    val s_axi_wstrb     = Input     (UInt(4.W))
    val s_axi_wlast     = Input     (Bool())
    val s_axi_wvalid    = Input     (Bool())
    val s_axi_wready    = Output    (Bool())

    val s_axi_bid       = Output    (UInt(4.W))
    val s_axi_bresp     = Output    (UInt(2.W))
    val s_axi_bvalid    = Output    (Bool())
    val s_axi_bready    = Input     (Bool())

    val s_axi_arid      = Input     (UInt(4.W))
    val s_axi_araddr    = Input     (UInt(32.W))
    val s_axi_arlen     = Input     (UInt(8.W))
    val s_axi_arsize    = Input     (UInt(3.W))
    val s_axi_arburst   = Input     (UInt(2.W))
    val s_axi_arvalid   = Input     (Bool())
    val s_axi_arready   = Output    (Bool())

    val s_axi_rid       = Output    (UInt(4.W))
    val s_axi_rdata     = Output    (UInt(32.W))
    val s_axi_rresp     = Output    (UInt(2.W))
    val s_axi_rlast     = Output    (Bool())
    val s_axi_rvalid    = Output    (Bool())
    val s_axi_rready    = Input     (Bool())
  })
}

class IROM extends Bundle {
  val addr = Output(UInt(32.W))
  val data = Input(UInt(32.W))
}

class jydIFU extends Module {
    val io = IO(new Bundle {
        val WBU_2_IFU = Flipped(new riscv_soc.bus.BUS_WBU_2_IFU)
        val IFU_2_IDU = Decoupled(Output(new riscv_soc.bus.BUS_IFU_2_IDU))

        val Pipeline_ctrl = Flipped(new riscv_soc.bus.Pipeline_ctrl)
        val IROM = new IROM
    })

    val pc = RegInit(Config.Reset_Vector)
    val snpc = pc + 4.U
    val dnpc = io.WBU_2_IFU.Next_PC
    
    io.IROM.addr := pc
    pc := MuxCase(pc, Seq(
        (io.Pipeline_ctrl.flush) -> dnpc,
        (io.IFU_2_IDU.fire) -> snpc
    ))

    io.IFU_2_IDU.valid := true.B

    io.IFU_2_IDU.bits.PC := pc
    io.IFU_2_IDU.bits.data := io.IROM.data
}

class DRAM extends Bundle {
  val addr = Output(UInt(32.W))
  val wen = Output(Bool())
  val mask = Output(UInt(2.W))
  val wdata = Output(UInt(32.W))
  val rdata = Input(UInt(32.W))
}

class jydLSU extends Module {
  val io = IO(new Bundle{
    val IDU_2_EXU = Flipped(Decoupled(Input(new riscv_soc.bus.BUS_IDU_2_EXU)))
    val EXU_2_WBU = Decoupled(Output(new riscv_soc.bus.BUS_EXU_2_WBU))
    val flush = Input(Bool())
    val DRAM = new DRAM
  })

  val addr = WireDefault(io.IDU_2_EXU.bits.EXU_A + io.IDU_2_EXU.bits.Imm)
  val data = WireDefault(io.IDU_2_EXU.bits.EXU_B)
  val mask = MuxLookup(io.IDU_2_EXU.bits.MemOp, 0.U)(Seq(
    bus.MemOp_TypeEnum.MemOp_1BS -> "b00".U,
    bus.MemOp_TypeEnum.MemOp_1BU -> "b00".U,
    bus.MemOp_TypeEnum.MemOp_2BS -> "b01".U,
    bus.MemOp_TypeEnum.MemOp_2BU -> "b01".U,
    bus.MemOp_TypeEnum.MemOp_4BU -> "b10".U,
  ))

  io.IDU_2_EXU.ready := io.EXU_2_WBU.ready
  io.EXU_2_WBU.valid := io.IDU_2_EXU.valid

  io.DRAM.addr := RegEnable(addr, 0.U, io.IDU_2_EXU.fire)
  io.DRAM.wdata := RegEnable(data, 0.U, io.IDU_2_EXU.fire)
  io.DRAM.wen := RegEnable(io.IDU_2_EXU.bits.EXUctr === riscv_soc.bus.EXUctr_TypeEnum.EXUctr_ST, 0.U, io.IDU_2_EXU.fire)
  io.DRAM.mask := mask

  io.EXU_2_WBU.bits.Branch        := io.IDU_2_EXU.bits.Branch 
  io.EXU_2_WBU.bits.Jmp_Pc        := 0.U   
  io.EXU_2_WBU.bits.MemtoReg      := io.IDU_2_EXU.bits.EXUctr === riscv_soc.bus.EXUctr_TypeEnum.EXUctr_LD   
  io.EXU_2_WBU.bits.csr_ctr       := io.IDU_2_EXU.bits.csr_ctr         
  io.EXU_2_WBU.bits.CSR_waddr     := io.IDU_2_EXU.bits.Imm(11, 0)  
  io.EXU_2_WBU.bits.GPR_waddr     := io.IDU_2_EXU.bits.GPR_waddr
  io.EXU_2_WBU.bits.PC            := io.IDU_2_EXU.bits.PC     
  io.EXU_2_WBU.bits.CSR_rdata     := io.IDU_2_EXU.bits.EXU_B  
  io.EXU_2_WBU.bits.Result        := 0.U
  io.EXU_2_WBU.bits.Mem_rdata     := io.DRAM.rdata

  if(Config.Simulate){
    val Catch = Module(new riscv_soc.cpu.LSU_catch)
    Catch.io.clock := clock
    Catch.io.valid := io.EXU_2_WBU.fire && !reset.asBool
    Catch.io.pc    := io.IDU_2_EXU.bits.PC
    Catch.io.diff_skip := Config.diff_mis_map.map(_.contains(RegEnable(addr, io.IDU_2_EXU.fire))).reduce(_ || _)
  }
}

trait jydHasCoreModules extends Module{
  val IFU: jydIFU
  val IDU: riscv_soc.cpu.IDU
  val ALU: riscv_soc.cpu.ALU
  val LSU: jydLSU
  val WBU: riscv_soc.cpu.WBU
  val REG: riscv_soc.cpu.REG
  val PipelineCtrl: riscv_soc.bus.PipelineCtrl
}

object jydCoreConnect {
  def apply(core: jydHasCoreModules): Unit = {
    import core._

    PipelineCtrl.io.GPR_read.valid := IDU.io.IDU_2_EXU.valid
    PipelineCtrl.io.GPR_read.bits := IDU.io.IDU_2_REG

    PipelineCtrl.io.IFU_out := IFU.io.IFU_2_IDU
    PipelineCtrl.io.IDU_in := IDU.io.IFU_2_IDU
    PipelineCtrl.io.ALU_in := ALU.io.IDU_2_EXU
    PipelineCtrl.io.LSU_in := LSU.io.IDU_2_EXU
    PipelineCtrl.io.WBU_in := WBU.io.EXU_2_WBU

    PipelineCtrl.io.Branch_msg := WBU.io.WBU_2_IFU

    val to_LSU = WireDefault(false.B)
    to_LSU := IDU.io.IDU_2_EXU.bits.EXUctr === riscv_soc.bus.EXUctr_TypeEnum.EXUctr_LD || IDU.io.IDU_2_EXU.bits.EXUctr === riscv_soc.bus.EXUctr_TypeEnum.EXUctr_ST

    IFU.io.Pipeline_ctrl := PipelineCtrl.io.IFUCtrl
    riscv_soc.bus.pipelineConnect(
      IFU.io.IFU_2_IDU,
      IDU.io.IFU_2_IDU,
      IDU.io.IDU_2_EXU,
      PipelineCtrl.io.IFUCtrl
    )

    riscv_soc.bus.pipelineConnect(
      IDU.io.IDU_2_EXU,
      Seq(
        (to_LSU, LSU.io.IDU_2_EXU, LSU.io.EXU_2_WBU),
        (!to_LSU, ALU.io.IDU_2_EXU, ALU.io.EXU_2_WBU)
      ),
      PipelineCtrl.io.IDUCtrl
    )

    riscv_soc.bus.pipelineConnect(
      Seq(
        (LSU.io.EXU_2_WBU),
        (ALU.io.EXU_2_WBU)
      ),
      WBU.io.EXU_2_WBU,
      WBU.io.WBU_2_IFU,
      PipelineCtrl.io.EXUCtrl
    )

    WBU.io.WBU_2_IFU.ready := true.B
    REG.io.REG_2_IDU <> IDU.io.REG_2_IDU
    IDU.io.IDU_2_REG <> REG.io.IDU_2_REG
    IFU.io.WBU_2_IFU <> WBU.io.WBU_2_IFU.bits
    WBU.io.WBU_2_REG <> REG.io.WBU_2_REG
    LSU.io.flush := PipelineCtrl.io.EXUCtrl.flush
  }
}

class SystemRAMWrapper(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
    val beatBytes = 4
    val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
        Seq(AXI4SlaveParameters(
            address       = address,
            executable    = true,
            supportsWrite = TransferSizes(1, beatBytes),
            supportsRead  = TransferSizes(1, beatBytes),
            interleavedId = Some(0))
        ),
        beatBytes  = beatBytes)))
        
    lazy val module = new Impl
    class Impl extends LazyModuleImp(this) {
        val AXI = node.in(0)._1

        val system_ram_0 = Module(new System_RAM())

        system_ram_0.io.s_aclk := clock
        system_ram_0.io.s_aresetn := !reset.asBool

        system_ram_0.io.s_axi_awid      := AXI.aw.bits.id
        system_ram_0.io.s_axi_awaddr    := AXI.aw.bits.addr
        system_ram_0.io.s_axi_awlen     := AXI.aw.bits.len
        system_ram_0.io.s_axi_awsize    := AXI.aw.bits.size
        system_ram_0.io.s_axi_awburst   := AXI.aw.bits.burst
        system_ram_0.io.s_axi_awvalid   := AXI.aw.valid
        system_ram_0.io.s_axi_awready   <> AXI.aw.ready

        system_ram_0.io.s_axi_wdata     := AXI.w.bits.data
        system_ram_0.io.s_axi_wstrb     := AXI.w.bits.strb
        system_ram_0.io.s_axi_wlast     := AXI.w.bits.last
        system_ram_0.io.s_axi_wvalid    := AXI.w.valid
        system_ram_0.io.s_axi_wready    <> AXI.w.ready

        system_ram_0.io.s_axi_bid       <> AXI.b.bits.id
        system_ram_0.io.s_axi_bresp     <> AXI.b.bits.resp
        system_ram_0.io.s_axi_bvalid    <> AXI.b.valid
        system_ram_0.io.s_axi_bready    := AXI.b.ready

        system_ram_0.io.s_axi_arid      := AXI.ar.bits.id
        system_ram_0.io.s_axi_araddr    := AXI.ar.bits.addr
        system_ram_0.io.s_axi_arlen     := AXI.ar.bits.len
        system_ram_0.io.s_axi_arsize    := AXI.ar.bits.size
        system_ram_0.io.s_axi_arburst   := AXI.ar.bits.burst
        system_ram_0.io.s_axi_arvalid   := AXI.ar.valid
        system_ram_0.io.s_axi_arready   <> AXI.ar.ready

        system_ram_0.io.s_axi_rid       <> AXI.r.bits.id
        system_ram_0.io.s_axi_rdata     <> AXI.r.bits.data
        system_ram_0.io.s_axi_rresp     <> AXI.r.bits.resp
        system_ram_0.io.s_axi_rlast     <> AXI.r.bits.last
        system_ram_0.io.s_axi_rvalid    <> AXI.r.valid
        system_ram_0.io.s_axi_rready    := AXI.r.ready
    }
}

class jyd_remote extends Module {
  val io = IO(new Bundle{
    val IROM = new IROM
    val DRAM = new DRAM
  })

  val IFU = Module(new jydIFU)
  val IDU = Module(new riscv_soc.cpu.IDU)
  val ALU = Module(new riscv_soc.cpu.ALU)
  val LSU = Module(new jydLSU)
  val WBU = Module(new riscv_soc.cpu.WBU)
  val REG = Module(new riscv_soc.cpu.REG)
  val PipelineCtrl = Module(new bus.PipelineCtrl)

  PipelineCtrl.io.GPR_read.valid := IDU.io.IDU_2_EXU.valid
  PipelineCtrl.io.GPR_read.bits := IDU.io.IDU_2_REG

  PipelineCtrl.io.IFU_out := IFU.io.IFU_2_IDU
  PipelineCtrl.io.IDU_in := IDU.io.IFU_2_IDU
  PipelineCtrl.io.ALU_in := ALU.io.IDU_2_EXU
  PipelineCtrl.io.LSU_in := LSU.io.IDU_2_EXU
  PipelineCtrl.io.WBU_in := WBU.io.EXU_2_WBU

  PipelineCtrl.io.Branch_msg := WBU.io.WBU_2_IFU
  
  val to_LSU = WireDefault(false.B)
  to_LSU := IDU.io.IDU_2_EXU.bits.EXUctr === riscv_soc.bus.EXUctr_TypeEnum.EXUctr_LD || IDU.io.IDU_2_EXU.bits.EXUctr === riscv_soc.bus.EXUctr_TypeEnum.EXUctr_ST

  IFU.io.Pipeline_ctrl := PipelineCtrl.io.IFUCtrl
  riscv_soc.bus.pipelineConnect(
    IFU.io.IFU_2_IDU,
    IDU.io.IFU_2_IDU,
    IDU.io.IDU_2_EXU,
    PipelineCtrl.io.IFUCtrl
  )

  riscv_soc.bus.pipelineConnect(
    IDU.io.IDU_2_EXU,
    Seq(
      (to_LSU, LSU.io.IDU_2_EXU, LSU.io.EXU_2_WBU),
      (!to_LSU, ALU.io.IDU_2_EXU, ALU.io.EXU_2_WBU)
    ),
    PipelineCtrl.io.IDUCtrl
  )

  riscv_soc.bus.pipelineConnect(
    Seq(
      (LSU.io.EXU_2_WBU),
      (ALU.io.EXU_2_WBU)
    ),
    WBU.io.EXU_2_WBU,
    WBU.io.WBU_2_IFU,
    PipelineCtrl.io.EXUCtrl
  )

  WBU.io.WBU_2_IFU.ready := true.B
  REG.io.REG_2_IDU <> IDU.io.REG_2_IDU
  IDU.io.IDU_2_REG <> REG.io.IDU_2_REG
  IFU.io.WBU_2_IFU <> WBU.io.WBU_2_IFU.bits
  WBU.io.WBU_2_REG <> REG.io.WBU_2_REG
  LSU.io.flush := PipelineCtrl.io.EXUCtrl.flush

  io.IROM <> IFU.io.IROM
  io.DRAM <> LSU.io.DRAM
}

class jyd(idBits: Int)(implicit p: Parameters) extends LazyModule {
  ElaborationArtefacts.add("graphml", graphML)
  val LazyIFU = LazyModule(new riscv_soc.cpu.IFU(idBits = idBits - 1))
  val LazyLSU = LazyModule(new riscv_soc.cpu.LSU(idBits = idBits - 1))

  val xbar = AXI4Xbar(maxFlightPerId = 1, awQueueDepth = 1)
  val apbxbar = LazyModule(new APBFanout).node
  xbar := LazyIFU.masterNode
  xbar := LazyLSU.masterNode

  apbxbar := AXI4ToAPB() := xbar

  if (Config.Simulate) {
    val luart = LazyModule(new UART(AddressSet.misaligned(0x10000000, 0x1000)))
    val lsram = LazyModule(
      new ram.SRAM(AddressSet.misaligned(0x80000000L, 0x8000000))
    )

    luart.node := xbar
    lsram.node := xbar
  } else {
    val lsram = LazyModule(
      new SystemRAMWrapper(
        AddressSet.misaligned(0x80000000L, 0x8000000)
      )
    )
    lsram.node := xbar
  }

  val lclint = LazyModule(
    new CLINT(AddressSet.misaligned(0xa0000048L, 0x10), 985.U)
  )
  lclint.node := xbar

  val lperipheral = LazyModule(
    new ApbPeripheralWrapper(AddressSet.misaligned(0x20000000, 0x2000))
  )
  lperipheral.node := apbxbar

  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with HasCoreModules with DontTouch {
    val peripheral = IO(new peripheral())
    peripheral <> lperipheral.module.peripheral

    // --- 实例化模块 ---
    val IFU = LazyIFU.module
    val IDU = Module(new riscv_soc.cpu.IDU)
    val ALU = Module(new riscv_soc.cpu.ALU)
    val LSU = LazyLSU.module
    val WBU = Module(new riscv_soc.cpu.WBU)
    val REG = Module(new riscv_soc.cpu.REG)
    val PipelineCtrl = Module(new bus.PipelineCtrl)
    
    CoreConnect(this)
  }
}

class jyd_core(idBits: Int)(implicit p: Parameters) extends LazyModule {
    val mmio = AddressSet.misaligned(0x0f000000, 0x2000) ++ // SRAM
      AddressSet.misaligned(0x10000000, 0x1000) ++ // UART
      AddressSet.misaligned(0x10001000, 0x1000) ++ // SPI
      AddressSet.misaligned(0x10002000, 0x10) ++ // GPIO
      AddressSet.misaligned(0x10011000, 0x8) ++ // PS2
      AddressSet.misaligned(0x21000000, 0x200000) ++ // VGA
      AddressSet.misaligned(0x30000000, 0x10000000) ++ // FLASH
      AddressSet.misaligned(0x80000000L, 0x400000) ++ // PSRAM
      AddressSet.misaligned(0xa0000000L, 0x2000000) ++ // SDRAM
      AddressSet.misaligned(0xc0000000L, 0x40000000L) // ChipLink

    ElaborationArtefacts.add("graphml", graphML)
    val LazyIFU = LazyModule(new riscv_soc.cpu.IFU(idBits = idBits - 1))
    val LazyLSU = LazyModule(new riscv_soc.cpu.LSU(idBits = idBits - 1))

    val xbar_if = AXI4Xbar(maxFlightPerId = 1, awQueueDepth = 1)
    val xbar_ls = AXI4Xbar(maxFlightPerId = 1, awQueueDepth = 1)

    xbar_if := LazyIFU.masterNode
    xbar_ls := LazyLSU.masterNode

    val beatBytes = 4
    val node_if = AXI4SlaveNode(
      Seq(
        AXI4SlavePortParameters(
          Seq(
            AXI4SlaveParameters(
              address = mmio,
              executable = true,
              supportsWrite = TransferSizes(1, beatBytes),
              supportsRead = TransferSizes(1, beatBytes),
              interleavedId = Some(0)
            )
          ),
          beatBytes = beatBytes
        )
      )
    )

    val node_ls = AXI4SlaveNode(
      Seq(
        AXI4SlavePortParameters(
          Seq(
            AXI4SlaveParameters(
              address = mmio,
              executable = true,
              supportsWrite = TransferSizes(1, beatBytes),
              supportsRead = TransferSizes(1, beatBytes),
              interleavedId = Some(0)
            )
          ),
          beatBytes = beatBytes
        )
      )
    )

    node_if := xbar_if
    node_ls := xbar_ls

    override lazy val module = new Impl
    class Impl extends LazyModuleImp(this) with HasCoreModules with DontTouch {
      val io = IO(new Bundle {
        val master_if = AXI4Bundle(CPUAXI4BundleParameters())
        val master_ls = AXI4Bundle(CPUAXI4BundleParameters())
      })

      io.master_if <> node_if.in(0)._1
      io.master_ls <> node_ls.in(0)._1

      val IFU = LazyIFU.module
      val IDU = Module(new riscv_soc.cpu.IDU)
      val ALU = Module(new riscv_soc.cpu.ALU)
      val LSU = LazyLSU.module
      val WBU = Module(new riscv_soc.cpu.WBU)
      val REG = Module(new riscv_soc.cpu.REG)
      val PipelineCtrl = Module(new bus.PipelineCtrl)
      
      CoreConnect(this)
    }
}

import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import riscv_soc.bus._

class top extends Module {
    implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

    val dut = LazyModule(new riscv_soc.platform.jyd.jyd(idBits = ChipLinkParam.idBits))
    val mdut = Module(dut.module)

    val peripheral = IO(new peripheral())
    mdut.peripheral <> peripheral
    mdut.dontTouchPorts()
}

class core extends Module {
    val io = IO(new Bundle {
      val master_if = AXI4Bundle(CPUAXI4BundleParameters())
      val master_ls = AXI4Bundle(CPUAXI4BundleParameters())
    })

    implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

    val dut = LazyModule(new riscv_soc.platform.jyd.jyd_core(idBits = ChipLinkParam.idBits))
    val mdut = Module(dut.module)

    mdut.dontTouchPorts()

    io.master_if <> mdut.io.master_if
    io.master_ls <> mdut.io.master_ls
}
