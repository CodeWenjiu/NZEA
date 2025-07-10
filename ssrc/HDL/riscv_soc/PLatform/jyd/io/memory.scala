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
import riscv_soc.HasCoreModules
import riscv_soc.CoreConnect
import freechips.rocketchip.amba.axi4.AXI4Bundle
import riscv_soc.CPUAXI4BundleParameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import riscv_soc.bus
import _root_.peripheral.UART
import utility.HoldUnless
import riscv_soc.platform.vivado

class System_RAM extends BlackBox {
  class self_bundle extends vivado.BasicAXI {
    val rsta_busy       = Output(Bool())
    val rstb_busy       = Output(Bool())
  }

  val io = IO(new self_bundle)
}

class SystemRAMWrapper(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val beatBytes = 4
  val node = AXI4SlaveNode(
    Seq(
      AXI4SlavePortParameters(
        Seq(
          AXI4SlaveParameters(
            address       = address,
            executable    = true,
            supportsWrite = TransferSizes(1, beatBytes),
            supportsRead  = TransferSizes(1, beatBytes),
            interleavedId = Some(0)
          )
        ),

        beatBytes  = beatBytes
      )
    )
  )
      
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

class IROM_AXI extends BlackBox {
  class self_bundle extends vivado.BasicAXI {
    val rsta_busy       = Output(Bool())
    val rstb_busy       = Output(Bool())
  }

  val io = IO(new self_bundle)
}

class IROM_AXI_Wrap(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val beatBytes = 4
  val node = AXI4SlaveNode(
    Seq(
      AXI4SlavePortParameters(
        Seq(
          AXI4SlaveParameters(
            address       = address,
            executable    = true,
            supportsRead  = TransferSizes(1, beatBytes),
            interleavedId = Some(0)
          )
        ),
        
        beatBytes  = beatBytes
      )
    )
  )
  
  lazy val module = new Impl

  class Impl extends LazyModuleImp(this) {
    // very dumb, but I have no time to implement an elegant solution
    val irom = Module(new IROM())
    val AXI = node.in(0)._1

    AXI.r.bits.id := RegEnable(AXI.ar.bits.id, AXI.ar.fire)
      
    val s_wait_addr :: s_burst :: Nil = Enum(2)
      
    val state_r = RegInit(s_wait_addr)
      
    val read_burst_counter  = RegEnable(AXI.ar.bits.len, AXI.ar.fire)

    val read_addr = RegEnable(AXI.ar.bits.addr, AXI.ar.fire)

    when(AXI.r.fire) {
      read_burst_counter := read_burst_counter - 1.U
      read_addr := read_addr + 4.U
    }
      
    AXI.r.bits.last := read_burst_counter === 0.U

    state_r := MuxLookup(state_r, s_wait_addr)(
      Seq(
        s_wait_addr -> Mux(AXI.ar.fire, s_burst, s_wait_addr),
        s_burst     -> Mux(read_burst_counter === 0.U, s_wait_addr, s_burst),
      )
    )

    AXI.ar.ready := state_r === s_wait_addr
    AXI.r.valid  := state_r === s_burst

    AXI.r.bits.resp := "b0".U

    irom.io.addr := read_addr
    AXI.r.bits.data := irom.io.data
  }
}


class IROM_bus extends Bundle {
  val addr = Input(UInt(32.W))
  val data = Output(UInt(32.W))
}

class IROM extends BlackBox with HasBlackBoxInline {
  val io = IO(new IROM_bus)

  val code = 
  s"""
  |module IROM(
  |    input [31:0] addr,
  |    output [31:0] data
  |);
  |
  |   import "DPI-C" function void IROM_read(input bit [31:0] addr, output bit [31:0] data);
  |   always @(*) begin
  |       IROM_read(addr, data);
  |   end
  |
  |endmodule
  """

  setInline("IROM.v", code.stripMargin)
}

class IROM_Wrap2AXI(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val beatBytes = 4
  val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
    Seq(AXI4SlaveParameters(
      address       = address,
      executable    = true,
      // supportsWrite = TransferSizes(1, beatBytes),
      supportsRead  = TransferSizes(1, beatBytes),
      interleavedId = Some(0))
    ),
    beatBytes  = beatBytes))
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(Flipped(new IROM_bus))

    val AXI = node.in(0)._1

    AXI.r.bits.id := RegEnable(AXI.ar.bits.id, AXI.ar.fire)
        
    val s_wait_addr :: s_burst :: Nil = Enum(2)
        
    val state_r = RegInit(s_wait_addr)
        
    val read_burst_counter  = RegEnable(AXI.ar.bits.len, AXI.ar.fire)

    val read_addr = RegEnable(AXI.ar.bits.addr, AXI.ar.fire)

    when(AXI.r.fire) {
      read_burst_counter := read_burst_counter - 1.U
      read_addr := read_addr + 4.U
    }
        
    AXI.r.bits.last := read_burst_counter === 0.U

    state_r := MuxLookup(state_r, s_wait_addr)(
      Seq(
        s_wait_addr -> Mux(AXI.ar.fire, s_burst, s_wait_addr),
        s_burst     -> Mux(read_burst_counter === 0.U, s_wait_addr, s_burst),
      )
    )

    AXI.ar.ready := state_r === s_wait_addr
    AXI.r.valid  := state_r === s_burst

    AXI.r.bits.resp := "b0".U

    io.addr := read_addr
    AXI.r.bits.data := io.data
  }
}

class DRAM_bus extends Bundle {
  val addr  = Input(UInt(32.W))
  val wen   = Input(Bool())
  val mask  = Input(UInt(2.W))
  val wdata = Input(UInt(32.W))
  val rdata = Output(UInt(32.W))
}

class DRAM extends BlackBox with HasBlackBoxInline {
  val io = IO(new DRAM_bus)
  val code = 
  s"""
  |module DRAM(
  |    input [31:0] addr,
  |    input wen,
  |    input [1:0] mask,
  |    input [31:0] wdata,
  |    output reg [31:0] rdata
  |);
  |
  |   import "DPI-C" function void DRAM_read(input bit [31:0] addr, input bit [1:0] mask, output bit [31:0] data);
  |   import "DPI-C" function void DRAM_write(input bit [31:0] addr, input bit [1:0] mask, input bit [31:0] data);
  |   always @(*) begin
  |       if(wen) begin
  |           DRAM_write(addr, mask, wdata);
  |           rdata = 0; 
  |       end
  |       else begin
  |           DRAM_read(addr, mask, rdata);
  |       end
  |   end
  |
  |endmodule
  """

  setInline("DRAM.v", code.stripMargin)
}

class DRAM_Wrap(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val beatBytes = 4
  val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
    Seq(AXI4SlaveParameters(
      address       = address,
      executable    = true,
      supportsWrite = TransferSizes(1, beatBytes),
      supportsRead  = TransferSizes(1, beatBytes),
      interleavedId = Some(0))
    ),
    beatBytes  = beatBytes))
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(Flipped(new DRAM_bus))

    val AXI = node.in(0)._1

    AXI.r.bits.id := RegEnable(AXI.ar.bits.id, AXI.ar.fire)
    AXI.b.bits.id := RegEnable(AXI.aw.bits.id, AXI.aw.fire)
        
    val s_wait_addr :: s_burst :: s_wait_resp :: Nil = Enum(3)
        
    val state_r = RegInit(s_wait_addr)
    val state_w = RegInit(s_wait_addr)
        
    val read_burst_counter  = RegEnable(AXI.ar.bits.len, AXI.ar.fire)
    val write_burst_counter = RegEnable(AXI.aw.bits.len, AXI.aw.fire)

    val access_addr = RegInit(0.U(32.W))

    access_addr := MuxCase(access_addr, Seq(
      AXI.ar.fire -> AXI.ar.bits.addr,
      AXI.aw.fire -> AXI.aw.bits.addr,

      AXI.r.fire -> (access_addr + 4.U),
      AXI.w.fire -> (access_addr + 4.U)
    ))

    when(AXI.r.fire) {
      read_burst_counter := read_burst_counter - 1.U
    }
    when(AXI.w.fire) {
      write_burst_counter := write_burst_counter - 1.U
    }
        
    AXI.r.bits.last := read_burst_counter === 0.U

    state_r := MuxLookup(state_r, s_wait_addr)(
      Seq(
        s_wait_addr -> Mux(AXI.ar.fire, s_burst, s_wait_addr),
        s_burst     -> Mux(read_burst_counter === 0.U, s_wait_addr, s_burst),
      )
    )

    state_w := MuxLookup(state_w, s_wait_addr)(
      Seq(
        s_wait_addr -> Mux(AXI.aw.fire, s_burst, s_wait_addr),
        s_burst     -> Mux(write_burst_counter === 0.U,  s_wait_resp, s_burst),
        s_wait_resp -> Mux(AXI.b.fire, s_wait_addr, s_wait_resp)
      )
    )

    AXI.ar.ready := state_r === s_wait_addr
    AXI.r.valid  := state_r === s_burst

    AXI.aw.ready := state_w === s_wait_addr
    AXI.w.ready  := state_w === s_burst
    AXI.b.valid  := state_w === s_wait_resp

    AXI.r.bits.resp := "b0".U
    AXI.b.bits.resp := "b0".U

    val strb = MuxCase("b1111".U, Seq(
      AXI.w.valid -> AXI.w.bits.strb,
    ))

    val mask = MuxLookup(strb, 0.U)(Seq(
      "b0001".U -> "b00".U,
      "b0011".U -> "b01".U, 
      "b1111".U -> "b10".U
    ))

    io.addr := access_addr
    AXI.r.bits.data := io.rdata

    io.mask := mask

    io.wen := (state_w === s_burst) && AXI.w.valid
    io.wdata := AXI.w.bits.data
  }
}
