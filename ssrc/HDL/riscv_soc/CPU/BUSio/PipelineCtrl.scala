package riscv_soc.bus

import chisel3._
import chisel3.util._

import signal_value._
import config._
import freechips.rocketchip.tilelink.TLMessages.d

class Pipeline_catch extends BlackBox with HasBlackBoxInline{
  val io = IO(new Bundle {
      val clock = Input(Clock())
      val pipeline_flush = Input(Bool())
  })
  setInline("Pipeline_catch.v",
  """module Pipeline_catch(
  |  input clock,
  |  input pipeline_flush
  |);
  |import "DPI-C" function void Pipeline_catch();
  |always @(posedge clock) begin
  |    if(pipeline_flush) begin
  |        Pipeline_catch();
  |    end
  |end
  |endmodule
  """.stripMargin)
}

class PipelineCtrl extends Module {
    val io = IO(new Bundle {
        val IDU_2_REG = Flipped(ValidIO(new IDU_2_REG))

        val IFU_out = Flipped(ValidIO(new IFU_2_IDU))
        val IDU_in  = Flipped(ValidIO(new IFU_2_IDU))

        val ISU_in  = Flipped(ValidIO(new IDU_2_ISU))

        val ALU_in  = Flipped(ValidIO(new ISU_2_ALU))
        val LSU_in  = Flipped(ValidIO(new ISU_2_LSU))

        val WBU_in  = Flipped(ValidIO(new EXU_2_WBU))
        val WBU_out = Flipped(ValidIO(new WBU_2_IFU))

        val IFUCtrl = new Pipeline_ctrl
        val IDUCtrl = new Pipeline_ctrl
        val ISU_2_LSUCtrl = new Pipeline_ctrl
        val ISU_2_ALUCtrl = new Pipeline_ctrl
        val EXUCtrl = new Pipeline_ctrl
    })

    def conflict(rs: UInt, rd: UInt) = (rs === rd)
    def conflict_gpr(rs: UInt, rd:UInt) = (conflict(rs, rd) && (rs =/= 0.U))
    def conflict_gpr_valid(rs: UInt) = 
        (conflict_gpr(rs, io.ALU_in.bits.gpr_waddr) & io.ALU_in.valid) ||
        (conflict_gpr(rs, io.LSU_in.bits.gpr_waddr) & io.LSU_in.valid) ||
        (conflict_gpr(rs, io.ISU_in.bits.gpr_waddr) & io.ISU_in.valid)

    def is_gpr_RAW = 
        io.IDU_2_REG.valid && 
        (conflict_gpr_valid(io.IDU_2_REG.bits.rs1_addr) ||
        conflict_gpr_valid(io.IDU_2_REG.bits.rs2_addr))

    def conflict_pc(target: UInt) =
        io.WBU_out.valid && (target =/= io.WBU_out.bits.next_pc)

    def is_bp_error = 
        MuxCase(conflict_pc(io.IFU_out.bits.PC), Seq(
        (io.ALU_in.valid -> conflict_pc(io.ALU_in.bits.PC)),
        (io.WBU_in.valid -> conflict_pc(io.WBU_in.bits.PC)),
        (io.ISU_in.valid -> conflict_pc(io.ISU_in.bits.PC)),
        (io.IDU_in.valid -> conflict_pc(io.IDU_in.bits.PC)),
    ))

    def is_ls_hazard = io.LSU_in.valid

    io.IFUCtrl.flush := is_bp_error
    io.IFUCtrl.stall := false.B

    io.IDUCtrl.flush := is_bp_error
    io.IDUCtrl.stall := is_gpr_RAW

    io.ISU_2_ALUCtrl.flush := is_bp_error
    io.ISU_2_ALUCtrl.stall := is_ls_hazard

    io.ISU_2_LSUCtrl.flush := is_bp_error
    io.ISU_2_LSUCtrl.stall := is_ls_hazard

    io.EXUCtrl.flush := is_bp_error
    io.EXUCtrl.stall := false.B

    if(Config.Simulate) {
        val pipeline_catch = Module(new Pipeline_catch)
        pipeline_catch.io.clock := clock
        pipeline_catch.io.pipeline_flush := RegNext(io.IFUCtrl.flush)
    }
}
