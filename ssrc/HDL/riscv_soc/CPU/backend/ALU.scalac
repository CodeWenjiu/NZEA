package riscv_soc.cpu.backend

import chisel3._
import chisel3.util._

import riscv_soc.bus._
import signal_value._
import config._

class ALU_catch extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle{
    val clock = Input(Clock())
    val valid = Input(Bool())
    val pc    = Input(UInt(32.W))
  })
  val code = 
  s"""module ALU_catch(
  |    input clock,
  |    input valid,
  |    input [31:0] pc
  |);
  |  import "DPI-C" function void ALU_catch(input bit [31:0] pc);
  |  always @(posedge clock) begin
  |     if(valid) begin
  |       ALU_catch(pc);
  |     end
  |  end
  |endmodule
  """

  setInline("ALU_catch.v", code.stripMargin)
}

class ALU extends Module {
    val io = IO(new Bundle {
        val ISU_2_ALU = Flipped(Decoupled(Input(new ISU_2_ALU)))

        val ALU_2_WBU = Decoupled(Output(new EXU_2_WBU))
    })

    io.ALU_2_WBU.valid := io.ISU_2_ALU.valid
    io.ISU_2_ALU.ready  := io.ALU_2_WBU.ready

    io.ALU_2_WBU.bits.basic := io.ISU_2_ALU.bits.basic

    val srca = io.ISU_2_ALU.bits.SRCA
    val srcb = io.ISU_2_ALU.bits.SRCB

    io.ALU_2_WBU.bits.Result := Mux1H(
      io.ISU_2_ALU.bits.al_ctrl.asUInt,
      Seq(
        (srcb),
        
        (srca + srcb),
        (srca - srcb),

        (srca & srcb),
        (srca | srcb),
        (srca ^ srcb),

        (srca << srcb(4, 0))(31, 0),
        (srca >> srcb(4, 0))(31, 0),
        (srca.asSInt >> srcb(4, 0))(31, 0),
      )
    )

    io.ALU_2_WBU.bits.CSR_rdata := io.ISU_2_ALU.bits.SRCA
    
    io.ALU_2_WBU.bits.gpr_waddr := io.ISU_2_ALU.bits.gpr_waddr
    io.ALU_2_WBU.bits.CSR_waddr := io.ISU_2_ALU.bits.csr_waddr

    io.ALU_2_WBU.bits.wbCtrl := io.ISU_2_ALU.bits.wb_ctrl

    if(Config.Simulate) {
        val Catch = Module(new ALU_catch)
        Catch.io.clock := clock
        Catch.io.valid := io.ALU_2_WBU.fire && !reset.asBool
        Catch.io.pc    := io.ISU_2_ALU.bits.basic.pc
    }
}
