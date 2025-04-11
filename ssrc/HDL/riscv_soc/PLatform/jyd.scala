package riscv_soc.platform.jyd

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.amba.axi4.AXI4SlaveNode
import freechips.rocketchip.amba.axi4.AXI4SlavePortParameters
import freechips.rocketchip.amba.axi4.AXI4SlaveParameters

class apb_peripheral extends BlackBox {
    val io = IO(new Bundle {
        val Pclk = Input(Clock())
        val Prst = Input(Bool())
        val Paddr = Input(UInt(32.W))
        val Pwrite = Input(Bool())
        val Psel = Input(Bool())
        val Penable = Input(Bool())
        val Pwdata = Input(UInt(32.W))
        
        val Prdata = Output(UInt(32.W))
        val Pready = Output(Bool())
        val Pslverr = Output(Bool())

        val LED = Output(UInt(32.W))
        val SW1 = Input(UInt(32.W))
        val SW2 = Input(UInt(32.W))
        val SEG = Output(UInt(32.W))
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
        val s_axi_awsize    = Output    (UInt(3.W))
        val s_axi_awburst   = Input     (UInt(2.W))
        val s_axi_awvalid   = Output    (Bool())
        val s_axi_awready   = Output    (Bool())

        val s_axi_wdata     = Output    (UInt(32.W))
        val s_axi_wstrb     = Output    (UInt(4.W))
        val s_axi_wlast     = Output    (Bool())
        val s_axi_wvalid    = Input     (Bool())
        val s_axi_wready    = Output    (Bool())

        val s_axi_bid       = Output    (UInt(4.W))
        val s_axi_bresp     = Input     (UInt(2.W))
        val s_axi_bvalid    = Input     (Bool())
        val s_axi_bready    = Input     (Bool())

        val s_axi_arid      = Input     (UInt(4.W))
        val s_axi_araddr    = Input     (UInt(32.W))
        val s_axi_arlen     = Input     (UInt(8.W))
        val s_axi_arsize    = Input     (UInt(3.W))
        val s_axi_arburst   = Input     (UInt(2.W))
        val s_axi_arvalid   = Output    (Bool())
        val s_axi_arready   = Input     (Bool())

        val s_axi_rid       = Input     (UInt(4.W))
        val s_axi_rdata     = Input     (UInt(32.W))
        val s_axi_rresp     = Output    (UInt(2.W))
        val s_axi_rlast     = Input     (Bool())
        val s_axi_rvalid    = Output    (Bool())
        val s_axi_rready    = Input     (Bool())
    })
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

        system_ram_0.io.s_axi_awid      <> AXI.aw.bits.id
        system_ram_0.io.s_axi_awaddr    <> AXI.aw.bits.addr
        system_ram_0.io.s_axi_awlen     <> AXI.aw.bits.len
        system_ram_0.io.s_axi_awsize    <> AXI.aw.bits.size
        system_ram_0.io.s_axi_awburst   <> AXI.aw.bits.burst
        system_ram_0.io.s_axi_awvalid   <> AXI.aw.valid
        system_ram_0.io.s_axi_awready   <> AXI.aw.ready

        system_ram_0.io.s_axi_wdata     <> AXI.w.bits.data
        system_ram_0.io.s_axi_wstrb     <> AXI.w.bits.strb
        system_ram_0.io.s_axi_wlast     <> AXI.w.bits.last
        system_ram_0.io.s_axi_wvalid    <> AXI.w.valid
        system_ram_0.io.s_axi_wready    <> AXI.w.ready

        system_ram_0.io.s_axi_bid       <> AXI.b.bits.id
        system_ram_0.io.s_axi_bresp     <> AXI.b.bits.resp
        system_ram_0.io.s_axi_bvalid    <> AXI.b.valid
        system_ram_0.io.s_axi_bready    <> AXI.b.ready

        system_ram_0.io.s_axi_arid      <> AXI.ar.bits.id
        system_ram_0.io.s_axi_araddr    <> AXI.ar.bits.addr
        system_ram_0.io.s_axi_arlen     <> AXI.ar.bits.len
        system_ram_0.io.s_axi_arsize    <> AXI.ar.bits.size
        system_ram_0.io.s_axi_arburst   <> AXI.ar.bits.burst
        system_ram_0.io.s_axi_arvalid   <> AXI.ar.valid
        system_ram_0.io.s_axi_arready   <> AXI.ar.ready

        system_ram_0.io.s_axi_rid       <> AXI.r.bits.id
        system_ram_0.io.s_axi_rdata     <> AXI.r.bits.data
        system_ram_0.io.s_axi_rresp     <> AXI.r.bits.resp
        system_ram_0.io.s_axi_rlast     <> AXI.r.bits.last
        system_ram_0.io.s_axi_rvalid    <> AXI.r.valid
        system_ram_0.io.s_axi_rready    <> AXI.r.ready
    }
}
