package riscv_soc.cpu.frontend

import chisel3._
import chisel3.util._
import riscv_soc.bus._
import config._
import utility.CacheTableAddr
import utility.CacheTemplate

class BPU_catch extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle {
        val clock = Input(Clock())
        val valid = Input(Bool())
        val pc = Input(UInt(32.W))
    })

    val code =
    s"""
    |module BPU_catch(
    |   input clock,
    |   input valid,
    |   input [31:0] pc
    |);
    |
    |import "DPI-C" function void BPU_catch(input bit [31:0] pc);
    |always @(posedge clock) begin
    |
    |   if(valid) begin
    |       BPU_catch(pc);
    |   end
    |
    |end
    |
    |endmodule
    """

    setInline("BPU_catch.v", code.stripMargin)
}

class BPU extends Module {
    val io = IO(new Bundle {
        val WBU_2_BPU = Flipped(Decoupled(Input(new WBU_2_BPU)))
        val BPU_2_IFU = Decoupled(Output(new BPU_2_IFU))
    })
    
    val pc = RegInit(Config.Reset_Vector)
    val snpc = pc + 4.U
    
    val btb_depth = 16
    
    val btb = Module(new CacheTemplate(btb_depth, name = "btb"))
    btb.io.addr := pc

    val prediction = btb.io.data
    
    val dnpc = io.WBU_2_BPU.bits.npc
    io.WBU_2_BPU.ready := true.B

    val pc_flush = io.WBU_2_BPU.fire && (io.WBU_2_BPU.bits.wb_ctrlflow =/= WbControlFlow.BPRight)

    btb.io.rreq.valid := pc_flush
    btb.io.rreq.bits.addr := io.WBU_2_BPU.bits.pc
    btb.io.rreq.bits.data := dnpc

    val pc_update = io.BPU_2_IFU.fire | pc_flush

    val npc = MuxCase(snpc, Seq(
        (pc_flush) -> dnpc,
        (prediction.valid) -> prediction.bits,
    ))

    when(pc_update) {
        pc := npc
    }

    io.BPU_2_IFU.bits.pc := pc
    io.BPU_2_IFU.bits.npc := npc

    io.BPU_2_IFU.valid := true.B
    
    if(Config.Simulate) {
        val Catch = Module(new BPU_catch)
        Catch.io.clock := clock
        Catch.io.valid := io.BPU_2_IFU.fire && !reset.asBool
        Catch.io.pc := pc
    }
}
