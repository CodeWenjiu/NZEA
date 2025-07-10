package riscv_soc.platform.vivado

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4.AXI4Bundle

class BasicAXI extends Bundle {
    val axi_awid      = Input     (UInt(4.W))
    val axi_awaddr    = Input     (UInt(32.W))
    val axi_awlen     = Input     (UInt(8.W))
    val axi_awsize    = Input     (UInt(3.W))
    val axi_awburst   = Input     (UInt(2.W))
    val axi_awvalid   = Input     (Bool())
    val axi_awready   = Output    (Bool())

    val axi_wdata     = Input     (UInt(32.W))
    val axi_wstrb     = Input     (UInt(4.W))
    val axi_wlast     = Input     (Bool())
    val axi_wvalid    = Input     (Bool())
    val axi_wready    = Output    (Bool())

    val axi_bid       = Output    (UInt(4.W))
    val axi_bresp     = Output    (UInt(2.W))
    val axi_bvalid    = Output    (Bool())
    val axi_bready    = Input     (Bool())

    val axi_arid      = Input     (UInt(4.W))
    val axi_araddr    = Input     (UInt(32.W))
    val axi_arlen     = Input     (UInt(8.W))
    val axi_arsize    = Input     (UInt(3.W))
    val axi_arburst   = Input     (UInt(2.W))
    val axi_arvalid   = Input     (Bool())
    val axi_arready   = Output    (Bool())

    val axi_rid       = Output    (UInt(4.W))
    val axi_rdata     = Output    (UInt(32.W))
    val axi_rresp     = Output    (UInt(2.W))
    val axi_rlast     = Output    (Bool())
    val axi_rvalid    = Output    (Bool())
    val axi_rready    = Input     (Bool())

    def connect(AXI: AXI4Bundle): Unit = {
        // Write address channel
        AXI.aw.bits.id    <> axi_awid
        AXI.aw.bits.addr  <> axi_awaddr
        AXI.aw.bits.len   <> axi_awlen
        AXI.aw.bits.size  <> axi_awsize
        AXI.aw.bits.burst <> axi_awburst
        AXI.aw.valid      <> axi_awvalid
        axi_awready       <> AXI.aw.ready

        // Write data channel
        AXI.w.bits.data <> axi_wdata
        AXI.w.bits.strb <> axi_wstrb
        AXI.w.bits.last <> axi_wlast
        AXI.w.valid     <> axi_wvalid
        axi_wready      <> AXI.w.ready

        // Write response channel
        axi_bid    <> AXI.b.bits.id
        axi_bresp  <> AXI.b.bits.resp
        axi_bvalid <> AXI.b.valid
        AXI.b.ready <> axi_bready

        // Read address channel
        AXI.ar.bits.id    <> axi_arid
        AXI.ar.bits.addr  <> axi_araddr
        AXI.ar.bits.len   <> axi_arlen
        AXI.ar.bits.size  <> axi_arsize
        AXI.ar.bits.burst <> axi_arburst
        AXI.ar.valid      <> axi_arvalid
        axi_arready       <> AXI.ar.ready

        // Read data channel
        axi_rid    <> AXI.r.bits.id
        axi_rdata  <> AXI.r.bits.data
        axi_rresp  <> AXI.r.bits.resp
        axi_rlast  <> AXI.r.bits.last
        axi_rvalid <> AXI.r.valid
        AXI.r.ready <> axi_rready
    }
}

class AXI_CDC_io extends Bundle {
    val s_axi_aclk      = Input(Clock())
    val s_axi_aresetn   = Input(Bool())
    val s = new BasicAXI

    val m_axi_aclk      = Input(Clock())
    val m_axi_aresetn   = Input(Bool())
    val m = Flipped(new BasicAXI)
}

class axi_clock_converter extends BlackBox {
    val io = IO(new AXI_CDC_io)
}
