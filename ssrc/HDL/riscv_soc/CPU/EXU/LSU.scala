package riscv_cpu

import chisel3._
import chisel3.util._

import signal_value._
import bus_state._
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
  val s_wait_valid,
      s_load,
      s_store,
      s_store_1,
      s_wb,
      s_sd
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
            val IDU_2_EXU = Flipped(Decoupled(Input(new BUS_IDU_2_EXU)))

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

        val state = RegInit(LS_state.s_wait_valid)

        state := MuxLookup(state, LS_state.s_wait_valid)(
            Seq(
                LS_state.s_wait_valid -> Mux(io.IDU_2_EXU.valid && !io.flush, MuxLookup(io.IDU_2_EXU.bits.EXUctr, LS_state.s_wait_valid)(Seq(
                    EXUctr_TypeEnum.EXUctr_LD -> LS_state.s_load,
                    EXUctr_TypeEnum.EXUctr_ST -> LS_state.s_store
                )), LS_state.s_wait_valid),

                LS_state.s_load -> Mux(master.ar.ready, LS_state.s_wb, LS_state.s_load),

                LS_state.s_store -> Mux(master.aw.ready, Mux(master.w.ready, LS_state.s_sd, LS_state.s_store_1), LS_state.s_store),
                LS_state.s_store_1 -> Mux(master.w.ready, LS_state.s_sd, LS_state.s_store_1),

                LS_state.s_wb -> Mux(io.EXU_2_WBU.fire, LS_state.s_wait_valid, LS_state.s_wb),

                LS_state.s_sd -> Mux(io.EXU_2_WBU.fire, LS_state.s_wait_valid, LS_state.s_sd)
            )
        )

        io.IDU_2_EXU.ready := state === LS_state.s_wait_valid && io.EXU_2_WBU.ready

        val addr = WireDefault(io.IDU_2_EXU.bits.EXU_A + io.IDU_2_EXU.bits.Imm)
        val data = WireDefault((io.IDU_2_EXU.bits.EXU_B << (addr(1,0) << 3.U))(31, 0))

        master.ar.bits.addr  := RegEnable(addr, io.IDU_2_EXU.fire)
        master.aw.bits.addr  := RegEnable(addr, io.IDU_2_EXU.fire)
        master.w.bits.data   := RegEnable(data, io.IDU_2_EXU.fire)

        master.ar.valid := state === LS_state.s_load
        master.r.ready := Mux(state === LS_state.s_wb, io.EXU_2_WBU.ready, false.B)

        master.aw.valid := state === LS_state.s_store
        master.w.valid := state === LS_state.s_store || state === LS_state.s_store_1

        master.b.ready := state === LS_state.s_sd
        
        io.EXU_2_WBU.valid := MuxLookup(state, false.B)(
            Seq(
                LS_state.s_wb -> master.r.valid,
                LS_state.s_sd -> master.b.valid
            )
        )
        
        when(io.IDU_2_EXU.bits.MemOp === MemOp_TypeEnum.MemOp_1BU || io.IDU_2_EXU.bits.MemOp === MemOp_TypeEnum.MemOp_1BS){
            master.w.bits.strb   := MuxLookup(master.aw.bits.addr(1,0), "b0001".U)(Seq(
                "b00".U -> "b0001".U,
                "b01".U -> "b0010".U,
                "b10".U -> "b0100".U,
                "b11".U -> "b1000".U,
            ))
        }.elsewhen(io.IDU_2_EXU.bits.MemOp === MemOp_TypeEnum.MemOp_2BU || io.IDU_2_EXU.bits.MemOp === MemOp_TypeEnum.MemOp_2BS){
            master.w.bits.strb   := MuxLookup(master.aw.bits.addr(1,0), "b0011".U)(Seq(
                "b00".U -> "b0011".U,
                "b01".U -> "b0110".U,
                "b10".U -> "b1100".U,
            ))
        }.otherwise{
            master.w.bits.strb   := "b1111".U
        }
        
        master.aw.bits.size  := MuxLookup(io.IDU_2_EXU.bits.MemOp, 0.U)(Seq(
            MemOp_TypeEnum.MemOp_1BU -> 0.U,
            MemOp_TypeEnum.MemOp_1BS -> 0.U,
            MemOp_TypeEnum.MemOp_2BU -> 1.U,
            MemOp_TypeEnum.MemOp_2BS -> 1.U,
            MemOp_TypeEnum.MemOp_4BU -> 2.U,
        ))
        master.ar.bits.size  := MuxLookup(io.IDU_2_EXU.bits.MemOp, 0.U)(Seq(
            MemOp_TypeEnum.MemOp_1BU -> 0.U,
            MemOp_TypeEnum.MemOp_1BS -> 0.U,
            MemOp_TypeEnum.MemOp_2BU -> 1.U,
            MemOp_TypeEnum.MemOp_2BS -> 1.U,
            MemOp_TypeEnum.MemOp_4BU -> 2.U,
        ))

        val AXI_rdata = Wire(UInt(32.W))
        AXI_rdata := (master.r.bits.data >> (master.ar.bits.addr(1,0) << 3.U))(31, 0)

        val mem_rd = Wire(Bits(32.W))

        mem_rd := MuxLookup(io.IDU_2_EXU.bits.MemOp, 0.U)(Seq(
            MemOp_TypeEnum.MemOp_1BU -> Cat(Fill(24, 0.U), AXI_rdata(7,0)),
            MemOp_TypeEnum.MemOp_1BS -> Cat(Fill(24, AXI_rdata(7)), AXI_rdata(7,0)),
            MemOp_TypeEnum.MemOp_2BU -> Cat(Fill(16, 0.U), AXI_rdata(15,0)),
            MemOp_TypeEnum.MemOp_2BS -> Cat(Fill(16, AXI_rdata(15)), AXI_rdata(15,0)),
            MemOp_TypeEnum.MemOp_4BU -> AXI_rdata(31,0).asUInt,
        ))

        io.EXU_2_WBU.bits.Branch        := io.IDU_2_EXU.bits.Branch 
        io.EXU_2_WBU.bits.Jmp_Pc        := 0.U                   
        io.EXU_2_WBU.bits.MemtoReg      := io.IDU_2_EXU.bits.EXUctr === EXUctr_TypeEnum.EXUctr_LD 
        io.EXU_2_WBU.bits.csr_ctr       := io.IDU_2_EXU.bits.csr_ctr
        io.EXU_2_WBU.bits.CSR_waddr     := io.IDU_2_EXU.bits.Imm(11, 0)  
        io.EXU_2_WBU.bits.GPR_waddr     := io.IDU_2_EXU.bits.GPR_waddr
        io.EXU_2_WBU.bits.PC            := io.IDU_2_EXU.bits.PC     
        io.EXU_2_WBU.bits.CSR_rdata     := io.IDU_2_EXU.bits.EXU_B  
        io.EXU_2_WBU.bits.Result        := 0.U
        io.EXU_2_WBU.bits.Mem_rdata     := mem_rd

        if(Config.Simulate){
            val Catch = Module(new LSU_catch)
            Catch.io.clock := clock
            Catch.io.valid := io.EXU_2_WBU.fire && !reset.asBool
            Catch.io.pc    := io.IDU_2_EXU.bits.PC
            Catch.io.diff_skip := Config.diff_mis_map.map(_.contains(RegEnable(addr, io.IDU_2_EXU.fire))).reduce(_ || _)
        }
    }
}
