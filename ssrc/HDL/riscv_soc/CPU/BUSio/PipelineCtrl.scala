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
        val GPR_READMSG = Flipped(ValidIO(new IDU_2_REG))

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
        // (conflict_gpr(rs, io.WBU_in.bits.gpr_waddr) & io.WBU_in.valid) || // not need if bypass
        (conflict_gpr(rs, io.ALU_in.bits.gpr_waddr) & io.ALU_in.valid) ||
        (conflict_gpr(rs, io.LSU_in.bits.gpr_waddr) & io.LSU_in.valid) ||
        (conflict_gpr(rs, io.ISU_in.bits.gpr_waddr) & io.ISU_in.valid)

    def data_hazard = 
        io.GPR_READMSG.valid && 
        (conflict_gpr_valid(io.GPR_READMSG.bits.rs1_addr) ||
        conflict_gpr_valid(io.GPR_READMSG.bits.rs2_addr))

    def control_hazard = io.WBU_out.valid && io.WBU_out.bits.wb_ctrlflow =/= WbControlFlow.BPRight

    def loadstore_hazard = io.LSU_in.valid

    io.IFUCtrl.flush := control_hazard
    io.IFUCtrl.stall := false.B

    io.IDUCtrl.flush := control_hazard
    io.IDUCtrl.stall := data_hazard

    io.ISU_2_ALUCtrl.flush := control_hazard
    io.ISU_2_ALUCtrl.stall := loadstore_hazard

    io.ISU_2_LSUCtrl.flush := control_hazard
    io.ISU_2_LSUCtrl.stall := loadstore_hazard

    io.EXUCtrl.flush := control_hazard
    io.EXUCtrl.stall := false.B

    if(Config.Simulate) {
        val pipeline_catch = Module(new Pipeline_catch)
        pipeline_catch.io.clock := clock
        pipeline_catch.io.pipeline_flush := io.IFUCtrl.flush
    }
}
