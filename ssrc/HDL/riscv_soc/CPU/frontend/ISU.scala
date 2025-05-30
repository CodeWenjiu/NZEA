package riscv_soc.cpu.frontend

import chisel3._
import chisel3.util._

import riscv_soc.bus._
import signal_value._
import config._

class ISU_catch extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle {
        val clock = Input(Clock())
        val valid = Input(Bool())
        val pc    = Input(UInt(32.W))
        val inst_type = Input(UInt(1.W))
    })
    val code =
    s"""module ISU_catch(
    |   input clock,
    |   input valid,
    |   input [31:0] pc,
    |   input bit inst_type
    |);
    |import "DPI-C" function void ISU_catch(input bit [31:0] pc, input bit inst_type);
    |
    |always @(posedge clock) begin
    |   if (valid) begin
    |       ISU_catch(pc, inst_type);
    |   end
    |end
    |
    |endmodule
    """

    setInline("ISU_catch.v", code.stripMargin)
}

class ISU extends Module {
    val io = IO(new Bundle {
        val IDU_2_ISU = Flipped(Decoupled(Input(new IDU_2_ISU)))
        val ISU_2_ALU = Decoupled(Output(new ISU_2_ALU))
        val ISU_2_LSU = Decoupled(Output(new ISU_2_LSU))
    })

    val inst_type = io.IDU_2_ISU.bits.is_ctrl.inst_Type

    io.IDU_2_ISU.ready := MuxLookup(inst_type, false.B)(Seq(
        Inst_Type.AL -> io.ISU_2_ALU.ready,
        Inst_Type.LS -> io.ISU_2_LSU.ready,
    ))

    io.ISU_2_ALU.valid := io.IDU_2_ISU.valid && inst_type === Inst_Type.AL
    io.ISU_2_LSU.valid := io.IDU_2_ISU.valid && inst_type === Inst_Type.LS

    io.ISU_2_ALU.bits.PC := io.IDU_2_ISU.bits.PC
    io.ISU_2_ALU.bits.trap := io.IDU_2_ISU.bits.trap

    io.ISU_2_LSU.bits.PC := io.IDU_2_ISU.bits.PC

    val rs1_val = io.IDU_2_ISU.bits.rs1_val
    val rs2_val = io.IDU_2_ISU.bits.rs2_val
    val imm = io.IDU_2_ISU.bits.imm

    val logic = Mux1H(
        io.IDU_2_ISU.bits.is_ctrl.isLogic.asUInt,
        Seq(
            (rs1_val === rs2_val),
            (rs1_val =/= rs2_val),

            (rs1_val.asSInt < rs2_val.asSInt),
            (rs1_val.asSInt >= rs2_val.asSInt),

            (rs1_val.asUInt < rs2_val.asUInt),
            (rs1_val.asUInt >= rs2_val.asUInt),

            (rs1_val.asSInt < imm.asSInt),
            (rs1_val.asUInt < imm.asUInt),
        )
    )

    io.ISU_2_ALU.bits.SRCA := MuxLookup(io.IDU_2_ISU.bits.is_ctrl.srca, 0.U)(Seq(
        SRCA.RS1 -> rs1_val,
        SRCA.ZERO -> 0.U,
        SRCA.PC -> io.IDU_2_ISU.bits.PC
    ))

    io.ISU_2_ALU.bits.SRCB := MuxLookup(io.IDU_2_ISU.bits.is_ctrl.srcb, 0.U)(Seq(
        SRCB.RS2 -> rs2_val,
        SRCB.IMM -> imm,
        SRCB.LogicBranch -> Mux(logic, imm, 4.U),
        SRCB.LogicSet -> Mux(logic, 1.U, 0.U),
    ))

    io.ISU_2_ALU.bits.al_ctrl := io.IDU_2_ISU.bits.al_ctrl
    io.ISU_2_ALU.bits.wb_ctrl := io.IDU_2_ISU.bits.wb_ctrl
    io.ISU_2_ALU.bits.gpr_waddr := io.IDU_2_ISU.bits.gpr_waddr

    io.ISU_2_LSU.bits.Ctrl := io.IDU_2_ISU.bits.ls_ctrl
    io.ISU_2_LSU.bits.gpr_waddr := io.IDU_2_ISU.bits.gpr_waddr
    io.ISU_2_LSU.bits.addr := rs1_val + imm
    io.ISU_2_LSU.bits.data := rs2_val

    if (Config.Simulate) {
        val Catch = Module(new ISU_catch)
        val inst_type_kn = inst_type === Inst_Type.AL 
        Catch.io.clock := clock
        Catch.io.valid := (io.ISU_2_ALU.fire || io.ISU_2_LSU.fire) && !reset.asBool
        Catch.io.pc := io.IDU_2_ISU.bits.PC
        Catch.io.inst_type := inst_type_kn
    }
}
