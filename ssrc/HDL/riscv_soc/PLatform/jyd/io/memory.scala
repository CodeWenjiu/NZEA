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

  val io = IO(new Bundle {
    val s = new self_bundle
  })
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

      system_ram_0.io.s.axi_awid      := AXI.aw.bits.id
      system_ram_0.io.s.axi_awaddr    := AXI.aw.bits.addr
      system_ram_0.io.s.axi_awlen     := AXI.aw.bits.len
      system_ram_0.io.s.axi_awsize    := AXI.aw.bits.size
      system_ram_0.io.s.axi_awburst   := AXI.aw.bits.burst
      system_ram_0.io.s.axi_awvalid   := AXI.aw.valid
      system_ram_0.io.s.axi_awready   <> AXI.aw.ready

      system_ram_0.io.s.axi_wdata     := AXI.w.bits.data
      system_ram_0.io.s.axi_wstrb     := AXI.w.bits.strb
      system_ram_0.io.s.axi_wlast     := AXI.w.bits.last
      system_ram_0.io.s.axi_wvalid    := AXI.w.valid
      system_ram_0.io.s.axi_wready    <> AXI.w.ready

      system_ram_0.io.s.axi_bid       <> AXI.b.bits.id
      system_ram_0.io.s.axi_bresp     <> AXI.b.bits.resp
      system_ram_0.io.s.axi_bvalid    <> AXI.b.valid
      system_ram_0.io.s.axi_bready    := AXI.b.ready

      system_ram_0.io.s.axi_arid      := AXI.ar.bits.id
      system_ram_0.io.s.axi_araddr    := AXI.ar.bits.addr
      system_ram_0.io.s.axi_arlen     := AXI.ar.bits.len
      system_ram_0.io.s.axi_arsize    := AXI.ar.bits.size
      system_ram_0.io.s.axi_arburst   := AXI.ar.bits.burst
      system_ram_0.io.s.axi_arvalid   := AXI.ar.valid
      system_ram_0.io.s.axi_arready   <> AXI.ar.ready

      system_ram_0.io.s.axi_rid       <> AXI.r.bits.id
      system_ram_0.io.s.axi_rdata     <> AXI.r.bits.data
      system_ram_0.io.s.axi_rresp     <> AXI.r.bits.resp
      system_ram_0.io.s.axi_rlast     <> AXI.r.bits.last
      system_ram_0.io.s.axi_rvalid    <> AXI.r.valid
      system_ram_0.io.s.axi_rready    := AXI.r.ready
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

class IROM_WrapFromAXI(
  with_sync_read: Boolean = true
) extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new AXI4Bundle(CPUAXI4BundleParameters()))
    val irom = Flipped(new IROM_bus)
  })

  io.axi.r.bits.id := RegEnable(io.axi.ar.bits.id, io.axi.ar.fire)
      
  val s_wait_addr :: s_burst :: s_wait_sync :: Nil = Enum(3)
      
  val state_r = RegInit(s_wait_addr)
      
  val read_burst_counter  = RegEnable(io.axi.ar.bits.len, io.axi.ar.fire)

  val read_addr = RegEnable(io.axi.ar.bits.addr, io.axi.ar.fire)

  when(io.axi.r.fire) {
    read_burst_counter := read_burst_counter - 1.U
    read_addr := read_addr + 4.U
  }
      
  io.axi.r.bits.last := read_burst_counter === 0.U

  if (with_sync_read) {
    state_r := MuxLookup(state_r, s_wait_addr)(
      Seq(
        s_wait_addr -> Mux(io.axi.ar.fire, s_wait_sync, s_wait_addr),
        s_wait_sync -> s_burst,
        s_burst     -> Mux(read_burst_counter === 0.U, s_wait_addr, Mux(io.axi.r.fire, s_wait_sync, s_burst))
      )
    )
  } else {
    state_r := MuxLookup(state_r, s_wait_addr)(
      Seq(
        s_wait_addr -> Mux(io.axi.ar.fire, s_burst, s_wait_addr),
        s_burst     -> Mux(read_burst_counter === 0.U, s_wait_addr, s_burst),
      )
    )
  }

  io.axi.ar.ready := state_r === s_wait_addr
  

  io.axi.r.bits.resp := "b0".U

  io.axi.r.valid := state_r === s_burst

  io.irom.addr := read_addr
  
  io.axi.r.bits.data := io.irom.data

  io.axi.aw.ready := false.B
  io.axi.w.ready := false.B
  io.axi.b.valid := false.B
  io.axi.b.bits.id := 0.U
  io.axi.b.bits.resp := 0.U
}

class DRAM_WrapFromAXI extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new AXI4Bundle(CPUAXI4BundleParameters()))
    val dram = Flipped(new DRAM_bus)
  })

  io.axi.r.bits.id := RegEnable(io.axi.ar.bits.id, io.axi.ar.fire)
  io.axi.b.bits.id := RegEnable(io.axi.aw.bits.id, io.axi.aw.fire)
      
  val s_wait_addr :: s_burst :: s_wait_resp :: Nil = Enum(3)
      
  val state_r = RegInit(s_wait_addr)
  val state_w = RegInit(s_wait_addr)
      
  val read_burst_counter  = RegEnable(io.axi.ar.bits.len, io.axi.ar.fire)
  val write_burst_counter = RegEnable(io.axi.aw.bits.len, io.axi.aw.fire)

  val access_addr = RegInit(0.U(32.W))

  access_addr := MuxCase(access_addr, Seq(
    io.axi.ar.fire -> io.axi.ar.bits.addr,
    io.axi.aw.fire -> io.axi.aw.bits.addr,

    io.axi.r.fire -> (access_addr + 4.U),
    io.axi.w.fire -> (access_addr + 4.U)
  ))

  when(io.axi.r.fire) {
    read_burst_counter := read_burst_counter - 1.U
  }
  when(io.axi.w.fire) {
    write_burst_counter := write_burst_counter - 1.U
  }
      
  io.axi.r.bits.last := read_burst_counter === 0.U

  state_r := MuxLookup(state_r, s_wait_addr)(
    Seq(
      s_wait_addr -> Mux(io.axi.ar.fire, s_wait_resp, s_wait_addr),
      s_wait_resp -> s_burst,
      s_burst     -> Mux(read_burst_counter === 0.U, s_wait_addr, s_burst),
    )
  )

  state_w := MuxLookup(state_w, s_wait_addr)(
    Seq(
      s_wait_addr -> Mux(io.axi.aw.fire, s_burst, s_wait_addr),
      s_burst     -> Mux(write_burst_counter === 0.U,  s_wait_resp, s_burst),
      s_wait_resp -> Mux(io.axi.b.fire, s_wait_addr, s_wait_resp)
    )
  )

  io.axi.ar.ready := state_r === s_wait_addr
  io.axi.r.valid  := state_r === s_burst

  io.axi.aw.ready := state_w === s_wait_addr
  io.axi.w.ready  := state_w === s_burst
  io.axi.b.valid  := state_w === s_wait_resp

  io.axi.r.bits.resp := "b0".U
  io.axi.b.bits.resp := "b0".U

  val strb = MuxCase("b1111".U, Seq(
    io.axi.w.valid -> io.axi.w.bits.strb,
  ))

  val mask = MuxLookup(strb, 0.U)(Seq(
    "b0001".U -> "b00".U,
    "b0011".U -> "b01".U, 
    "b1111".U -> "b10".U
  ))

  io.dram.addr := access_addr
  io.axi.r.bits.data := RegEnable(io.dram.rdata, (state_r === s_wait_resp) || io.axi.r.fire)

  io.dram.mask := mask

  io.dram.wen := (state_w === s_burst) && io.axi.w.valid
  io.dram.wdata := io.axi.w.bits.data
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
