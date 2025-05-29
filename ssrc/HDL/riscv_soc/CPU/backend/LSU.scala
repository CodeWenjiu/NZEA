package riscv_soc.cpu.backend

import chisel3._
import chisel3.util._

import riscv_soc.bus._
import signal_value._
import config._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import org.chipsalliance.diplomacy.lazymodule._

class LSU_catch extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle{
        val clock = Input(Clock())
        val valid = Input(Bool())
        val pc    = Input(UInt(32.W))
        val diff_skip = Input(Bool())
    })
    val code = 
    s"""module LSU_catch(
    |   input clock,
    |   input valid,
    |    input [31:0] pc,
    |   input diff_skip
    |);
    |  import "DPI-C" function void LSU_catch(input bit [31:0] pc, input bit diff_skip);
    |  always @(posedge clock) begin
    |     if(valid) begin
    |         LSU_catch(pc, diff_skip);
    |     end
    |  end
    |endmodule
    """

    setInline("LSU_catch.v", code.stripMargin)
}

object LS_state extends ChiselEnum{
  val s_idle,
      s_cache_miss,
      s_ar_busy,
      s_aw_busy,
      s_w_busy,
      s_cache_update
      = Value
}

class LSU(idBits: Int)(implicit p: Parameters) extends LazyModule{
    val masterNode = AXI4MasterNode(p(ExtIn).map(params =>
        AXI4MasterPortParameters(
        masters = Seq(AXI4MasterParameters(
            name = "lsu",
            id   = IdRange(0, 1 << idBits))))).toSeq)
    lazy val module = new Impl

    class Impl extends LazyModuleImp(this) {
        val io = IO(new Bundle{
            val ISU_2_LSU = Flipped(Decoupled(Input(new ISU_2_LSU)))
            
            val LSU_2_WBU = Decoupled(Output(new EXU_2_WBU))

            val is_flush = Input(Bool())
        })

        val (master, _) = masterNode.out(0)

        master.ar.bits.id    := 1.U
        master.ar.bits.len   := 0.U
        master.ar.bits.burst := 0.U
        master.ar.bits.lock  := 0.U
        master.ar.bits.cache := 0.U
        master.ar.bits.prot  := 0.U
        master.ar.bits.qos   := 0.U
        master.aw.bits.id    := 1.U
        master.aw.bits.len   := 0.U
        master.aw.bits.burst := 0.U
        master.aw.bits.lock  := 0.U
        master.aw.bits.cache := 0.U
        master.aw.bits.prot  := 0.U
        master.aw.bits.qos   := 0.U
        master.w.bits.last   := 1.U

        val addr = io.ISU_2_LSU.bits.addr
        val fire = io.ISU_2_LSU.fire
        val data = WireDefault((io.ISU_2_LSU.bits.data << (addr(1, 0) << 3.U))(31, 0))
        val is_st = MuxLookup(io.ISU_2_LSU.bits.Ctrl, false.B)(Seq(
            LsCtrl.SB -> true.B,
            LsCtrl.SH -> true.B,
            LsCtrl.SW -> true.B
        ))
        val is_ld = !is_st

        val state = RegInit(LS_state.s_idle)
        state := MuxLookup(state, LS_state.s_idle)(Seq(
            LS_state.s_idle -> Mux(fire && !io.is_flush, LS_state.s_cache_miss, state),

            LS_state.s_cache_miss -> MuxCase(LS_state.s_cache_update, Seq(
                (is_st && !master.aw.fire)  -> LS_state.s_aw_busy,
                (is_st && !master.w.fire)   -> LS_state.s_w_busy,
                (!is_st && !master.ar.fire) -> LS_state.s_ar_busy,
            )),

            LS_state.s_ar_busy -> Mux(master.ar.fire, LS_state.s_cache_update, state),

            LS_state.s_aw_busy -> MuxCase(state, Seq(
                (master.aw.fire && !master.w.fire) -> LS_state.s_w_busy,
                (master.aw.fire) -> LS_state.s_cache_update,
            )),

            LS_state.s_w_busy -> Mux(master.w.fire, LS_state.s_cache_update, state),

            LS_state.s_cache_update -> Mux(io.LSU_2_WBU.fire, LS_state.s_idle, state),
        ))

        io.ISU_2_LSU.ready := io.LSU_2_WBU.ready /*is it needed?*/ && state === (LS_state.s_idle)

        master.ar.bits.addr := addr
        master.ar.valid := ((state === LS_state.s_cache_miss) || (state === LS_state.s_ar_busy)) && !is_st

        io.LSU_2_WBU.valid := master.r.valid || master.b.valid
        master.r.ready := io.LSU_2_WBU.ready
        master.b.ready := io.LSU_2_WBU.ready
        
        master.aw.bits.addr := addr
        master.aw.valid := ((state === LS_state.s_cache_miss) || (state === LS_state.s_aw_busy)) && is_st
        
        master.w.valid := ((state === LS_state.s_cache_miss) || (state === LS_state.s_aw_busy) || (state === LS_state.s_w_busy)) && is_st
        master.w.bits.strb := (MuxLookup(io.ISU_2_LSU.bits.Ctrl, 0.U)(Seq(
            LsCtrl.SB -> "b0001".U,
            LsCtrl.SH -> "b0011".U,
            LsCtrl.SW -> "b1111".U
        )) << addr(1, 0))
        master.w.bits.data := data

        val AXI_rdata = (master.r.bits.data >> (master.ar.bits.addr(1,0) << 3.U))(31, 0)
        val rdata = MuxLookup(io.ISU_2_LSU.bits.Ctrl, 0.U)(Seq(
            LsCtrl.LBU -> Cat(Fill(24, 0.U), AXI_rdata(7,0)),
            LsCtrl.LB  -> Cat(Fill(24, AXI_rdata(7)), AXI_rdata(7,0)),
            LsCtrl.LHU -> Cat(Fill(16, 0.U), AXI_rdata(15,0)),
            LsCtrl.LH -> Cat(Fill(16, AXI_rdata(15)), AXI_rdata(15,0)),
            LsCtrl.LW -> AXI_rdata(31,0).asUInt,
        ))
        
        val gpr_waddr = MuxLookup(io.ISU_2_LSU.bits.Ctrl, io.ISU_2_LSU.bits.gpr_waddr)(Seq(
            LsCtrl.SB -> 0.U,
            LsCtrl.SH -> 0.U,
            LsCtrl.SW -> 0.U
        ))

        io.LSU_2_WBU.bits.PC := io.ISU_2_LSU.bits.PC
        io.LSU_2_WBU.bits.trap.traped := false.B
        io.LSU_2_WBU.bits.trap.trap_type := Trap_type.Ebreak

        io.LSU_2_WBU.bits.Result := rdata
        io.LSU_2_WBU.bits.CSR_rdata := 0.U

        io.LSU_2_WBU.bits.gpr_waddr := gpr_waddr
        io.LSU_2_WBU.bits.CSR_waddr := 0.U
        io.LSU_2_WBU.bits.wbCtrl := WbCtrl.Write_GPR

        if(Config.Simulate) {
            val Catch = Module(new LSU_catch)
            Catch.io.clock := clock
            Catch.io.valid := io.LSU_2_WBU.fire && !reset.asBool
            Catch.io.pc    := io.ISU_2_LSU.bits.PC
            Catch.io.diff_skip := Config.diff_mis_map.map(_.contains(io.ISU_2_LSU.bits.addr)).reduce(_ || _)
        }
    }
}
