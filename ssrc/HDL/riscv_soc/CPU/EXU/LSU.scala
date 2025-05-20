package riscv_soc.cpu

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
            val AGU_2_LSU = Flipped(Decoupled(Input(new BUS_AGU_2_LSU)))

            val EXU_2_WBU = Decoupled(Output(new BUS_EXU_2_WBU))

            val flush = Input(Bool())
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
        
        val addr  = io.AGU_2_LSU.bits.addr
        val fire  = io.AGU_2_LSU.fire
        val wdata = WireDefault((io.AGU_2_LSU.bits.wdata << (addr(1,0) << 3.U))(31, 0))
        val is_st = io.AGU_2_LSU.bits.wen

        val state = RegInit(LS_state.s_idle)

        state := MuxLookup(state, LS_state.s_idle)(Seq(
            LS_state.s_idle -> Mux(fire, LS_state.s_cache_miss, state),

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

            LS_state.s_cache_update -> Mux(io.EXU_2_WBU.fire, LS_state.s_idle, state),
        ))

        io.AGU_2_LSU.ready := io.EXU_2_WBU.ready /*is it needed?*/ && state === (LS_state.s_idle)

        master.ar.bits.addr := addr
        master.ar.valid := ((state === LS_state.s_cache_miss) || (state === LS_state.s_ar_busy)) && !is_st

        io.EXU_2_WBU.valid := master.r.valid || master.b.valid
        master.r.ready := io.EXU_2_WBU.ready
        master.b.ready := io.EXU_2_WBU.ready
        
        master.aw.bits.addr := addr
        master.aw.valid := ((state === LS_state.s_cache_miss) || (state === LS_state.s_aw_busy)) && is_st

        master.w.valid := ((state === LS_state.s_cache_miss) || (state === LS_state.s_aw_busy) || (state === LS_state.s_w_busy)) && is_st

        when(io.AGU_2_LSU.bits.MemOp === MemOp_TypeEnum.MemOp_1BU || io.AGU_2_LSU.bits.MemOp === MemOp_TypeEnum.MemOp_1BS){
            master.w.bits.strb   := MuxLookup(master.aw.bits.addr(1,0), "b0001".U)(Seq(
                "b00".U -> "b0001".U,
                "b01".U -> "b0010".U,
                "b10".U -> "b0100".U,
                "b11".U -> "b1000".U,
            ))
        }.elsewhen(io.AGU_2_LSU.bits.MemOp === MemOp_TypeEnum.MemOp_2BU || io.AGU_2_LSU.bits.MemOp === MemOp_TypeEnum.MemOp_2BS){
            master.w.bits.strb   := MuxLookup(master.aw.bits.addr(1,0), "b0011".U)(Seq(
                "b00".U -> "b0011".U,
                "b01".U -> "b0110".U,
                "b10".U -> "b1100".U,
            ))
        }.otherwise{
            master.w.bits.strb   := "b1111".U
        }
        master.w.bits.data := wdata

        val AXI_rdata = (master.r.bits.data >> (master.ar.bits.addr(1,0) << 3.U))(31, 0)
        val mem_rd = MuxLookup(io.AGU_2_LSU.bits.MemOp, 0.U)(Seq(
            MemOp_TypeEnum.MemOp_1BU -> Cat(Fill(24, 0.U), AXI_rdata(7,0)),
            MemOp_TypeEnum.MemOp_1BS -> Cat(Fill(24, AXI_rdata(7)), AXI_rdata(7,0)),
            MemOp_TypeEnum.MemOp_2BU -> Cat(Fill(16, 0.U), AXI_rdata(15,0)),
            MemOp_TypeEnum.MemOp_2BS -> Cat(Fill(16, AXI_rdata(15)), AXI_rdata(15,0)),
            MemOp_TypeEnum.MemOp_4BU -> AXI_rdata(31,0).asUInt,
        ))
        
        io.EXU_2_WBU.bits.Branch        := Bran_TypeEnum.Bran_NJmp
        io.EXU_2_WBU.bits.Jmp_Pc        := 0.U                   
        io.EXU_2_WBU.bits.MemtoReg      := !io.AGU_2_LSU.bits.wen
        io.EXU_2_WBU.bits.csr_ctr       := CSR_TypeEnum.CSR_N
        io.EXU_2_WBU.bits.CSR_waddr     := 0.U 
        io.EXU_2_WBU.bits.GPR_waddr     := io.AGU_2_LSU.bits.GPR_waddr
        io.EXU_2_WBU.bits.PC            := io.AGU_2_LSU.bits.PC     
        io.EXU_2_WBU.bits.CSR_rdata     := 0.U  
        io.EXU_2_WBU.bits.Result        := 0.U
        io.EXU_2_WBU.bits.Mem_rdata     := mem_rd

        if(Config.Simulate){
            val Catch = Module(new LSU_catch)
            Catch.io.clock := clock
            Catch.io.valid := io.EXU_2_WBU.fire && !reset.asBool
            Catch.io.pc    := io.AGU_2_LSU.bits.PC
            Catch.io.diff_skip := Config.diff_mis_map.map(_.contains(io.AGU_2_LSU.bits.addr)).reduce(_ || _)
        }
    }
}

object HoldBypass {
    def apply[T <: Data](data: T, valid: Bool): T = {
      Mux(valid, data, RegEnable(data, valid))
    }
  
    def apply[T <: Data](data: T, init: T, valid: Bool): T = {
      Mux(valid, data, RegEnable(data, init, valid))
    }
}
