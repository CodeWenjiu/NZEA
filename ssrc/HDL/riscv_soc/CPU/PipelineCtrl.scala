package riscv_soc

import chisel3._
import chisel3.util._

import bus._
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
        val GPR_read = Flipped(ValidIO((new BUS_IDU_2_REG)))

        val IFU_out = Flipped(ValidIO(new BUS_IFU_2_IDU))
        val IDU_in  = Flipped(ValidIO((new BUS_IFU_2_IDU)))
        val ALU_in  = Flipped(ValidIO((new BUS_IDU_2_EXU)))
        val LSU_in  = Flipped(ValidIO((new BUS_IDU_2_EXU)))
        val WBU_in  = Flipped(ValidIO((new BUS_EXU_2_WBU)))
        val Branch_msg = Flipped(ValidIO((new BUS_WBU_2_IFU)))

        val IFUCtrl = new Pipeline_ctrl
        val IDUCtrl = new Pipeline_ctrl
        val EXUCtrl = new Pipeline_ctrl
    })

    def conflict(rs: UInt, rd: UInt) = (rs === rd)

    def conflict_gpr(rs: UInt, rd:UInt) = (conflict(rs, rd) && (rs =/= 0.U))
    def conflict_gpr_valid(rs: UInt) = 
        (conflict_gpr(rs, io.ALU_in.bits.GPR_waddr) & io.ALU_in.valid) ||
        (conflict_gpr(rs, io.WBU_in.bits.GPR_waddr) & io.WBU_in.valid)

    def is_gpr_RAW = io.GPR_read.valid && 
                     (conflict_gpr_valid(io.GPR_read.bits.GPR_Aaddr) ||
                     conflict_gpr_valid(io.GPR_read.bits.GPR_Baddr))

    def conflict_pc(target: UInt) =
        io.Branch_msg.valid && (target =/= io.Branch_msg.bits.Next_PC)

    def is_bp_error = MuxCase(conflict_pc(io.IFU_out.bits.PC), Seq(
        (io.ALU_in.valid -> conflict_pc(io.ALU_in.bits.PC)),
        (io.LSU_in.valid -> conflict_pc(io.LSU_in.bits.PC)),
        (io.IDU_in.valid -> conflict_pc(io.IDU_in.bits.PC)),
    ))

    def is_ls_hazard = io.LSU_in.valid

    io.IFUCtrl.flush := is_bp_error
    io.IFUCtrl.stall := false.B

    io.IDUCtrl.flush := is_bp_error
    io.IDUCtrl.stall := is_gpr_RAW || is_ls_hazard

    io.EXUCtrl.flush := is_bp_error
    io.EXUCtrl.stall := false.B

    if(Config.Simulate) {
        val pipeline_catch = Module(new Pipeline_catch)
        pipeline_catch.io.clock := clock
        pipeline_catch.io.pipeline_flush := RegNext(io.IFUCtrl.flush)
    }
}
