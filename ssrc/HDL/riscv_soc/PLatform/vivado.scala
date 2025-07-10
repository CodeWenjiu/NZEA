package riscv_soc.platform.vivado

import chisel3._
import chisel3.util._

class BasicAXI extends Bundle {
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
}
