package riscv_soc.cpu.frontend

import chisel3._
import chisel3.util._

import config._
import utility.ReplacementPolicy

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.util.Annotated.srams
import org.chipsalliance.diplomacy.lazymodule._
import riscv_soc.bus._
import signal_value._
import scala.collection.Parallel
import utility.CacheTemplate
import utility.HoldUnless
import utility.UpEdge

class IFU_catch extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle{
        val clock = Input(Clock())
        val valid = Input(Bool())
        val pc    = Input(UInt(32.W))
        val inst  = Input(UInt(32.W))
    })
    val code = 
    s"""
    |module IFU_catch(
    |    input clock,
    |    input valid,
    |    input [31:0] pc,
    |    input [31:0] inst
    |);
    |
    |   import "DPI-C" function void IFU_catch(input bit [31:0] pc, input bit [31:0] inst);
    |   always @(posedge clock) begin
    |       if(valid) begin
    |           IFU_catch(pc, inst);
    |       end
    |   end
    |
    |endmodule
    """

    setInline("IFU_catch.v", code.stripMargin)
}

object IFU_state extends ChiselEnum{
  val s_idle,
      s_send_addr,
      s_get_data
      = Value
}

class IFU(idBits: Int)(implicit p: Parameters) extends LazyModule {
    val masterNode = AXI4MasterNode(p(ExtIn).map(params =>
        AXI4MasterPortParameters(
        masters = Seq(AXI4MasterParameters(
            name = "ifu",
            id   = IdRange(0, 1 << idBits))))).toSeq)
    lazy val module = new Impl
    class Impl extends LazyModuleImp(this) {
        val io = IO(new Bundle{
            val WBU_2_IFU = Flipped(Decoupled(Input(new WBU_2_IFU)))
            val IFU_2_IDU = Decoupled(Output(new IFU_2_IDU))

            // val IFU_2_IDU_async = Output(UInt(32.W))

            val Pipeline_ctrl = Flipped(new Pipeline_ctrl)
        })
        val (master, _) = masterNode.out(0)

        io.WBU_2_IFU.ready := true.B
        val pc = RegInit(Config.Reset_Vector)
        val snpc = pc + 4.U
        val dnpc = io.WBU_2_IFU.bits.npc
        
        val pc_flush = io.Pipeline_ctrl.flush
        
        val pc_update = io.IFU_2_IDU.fire | pc_flush

        val BPU = Module(new BPU)
        BPU.io.pc := pc
        BPU.io.flush_pc.valid := pc_flush
        BPU.io.flush_pc.bits.pc := io.WBU_2_IFU.bits.pc
        BPU.io.flush_pc.bits.target := io.WBU_2_IFU.bits.npc

        val npc = MuxCase(snpc, Seq(
            (pc_flush) -> dnpc,
            (BPU.io.hit) -> BPU.io.npc,
        ))

        when(pc_update) {
            pc := npc
        }

        Config.Icache_Param match {
            case Some((address, set, way, block_size)) => {
                val state = RegInit(IFU_state.s_idle)

                val burst_transfer_time = 4

                val Icache = Module(new CacheTemplate(
                    set = set,
                    way = way,
                    block_num = burst_transfer_time,
                    name = "icache",
                    with_valid = true,
                    with_fence = true,
                ))

                Icache.io.areq.ren := true.B
                Icache.io.areq.addr := pc

                val cache_hit = Icache.io.areq.hit

                val next_state = MuxLookup(state, IFU_state.s_idle)(
                    Seq(
                        IFU_state.s_idle -> Mux(
                            !cache_hit && !pc_flush,
                            IFU_state.s_send_addr,
                            state
                        ),

                        IFU_state.s_send_addr -> MuxCase(state, Seq( 
                            master.ar.fire -> IFU_state.s_get_data,
                            pc_flush -> IFU_state.s_idle
                        )),

                        IFU_state.s_get_data -> Mux(
                            master.r.fire && master.r.bits.last,
                            IFU_state.s_idle,
                            state
                        )
                    )
                )
                state := next_state

                val flat_addr = pc & ~((burst_transfer_time << 2) - 1).U(32.W)
                Icache.io.rreq.bits.addr := RegEnable(flat_addr, master.ar.fire)
                Icache.io.rreq.bits.data := master.r.bits.data
                Icache.io.rreq.valid := master.r.fire

                io.IFU_2_IDU.valid := (state === IFU_state.s_idle) && cache_hit

                io.IFU_2_IDU.bits.inst := Icache.io.areq.rdata
                io.IFU_2_IDU.bits.pc := pc
                io.IFU_2_IDU.bits.npc := npc

                master.ar.valid := (state === IFU_state.s_send_addr)
                master.ar.bits.len := (burst_transfer_time - 1).U
                master.ar.bits.addr := flat_addr

                master.r.ready := state === IFU_state.s_get_data
            }
            case None => {
                // TODO: Need to fix for master.ar.ready always false

                io.WBU_2_IFU.ready := master.ar.ready && io.IFU_2_IDU.ready

                io.IFU_2_IDU.valid := master.r.valid
                io.IFU_2_IDU.bits.pc := pc
                io.IFU_2_IDU.bits.npc := io.WBU_2_IFU.bits.npc

                master.ar.valid := io.WBU_2_IFU.valid
                master.r.ready := io.IFU_2_IDU.ready

                master.ar.bits.len := 0.U
                master.ar.bits.addr := pc

                io.IFU_2_IDU.bits.inst := master.r.bits.data
            }
        }

        if(Config.Simulate){
            val Catch = Module(new IFU_catch)
            Catch.io.clock := clock
            Catch.io.valid := io.IFU_2_IDU.fire && !reset.asBool
            Catch.io.inst := io.IFU_2_IDU.bits.inst
            Catch.io.pc := io.IFU_2_IDU.bits.pc
        }
        master.aw.bits.size  := 0.U
        master.ar.bits.size  := 2.U

        master.aw.valid := false.B
        master.aw.bits.addr := 0.U
        master.aw.bits.id    := 0.U
        master.aw.bits.len   := 0.U
        master.aw.bits.burst := 0.U
        master.aw.bits.lock  := 0.U
        master.aw.bits.cache := 0.U
        master.aw.bits.prot  := 0.U
        master.aw.bits.qos   := 0.U
        master.w.valid := false.B
        master.w.bits.data := 0.U
        master.w.bits.strb := 0.U
        master.w.bits.last  := 1.U
        master.b.ready := false.B

        master.ar.bits.id    := 0.U
        master.ar.bits.burst := 1.U // INCR
        master.ar.bits.lock  := 0.U // Normal access
        master.ar.bits.cache := 0.U // Cacheable
        master.ar.bits.prot  := 0.U // Normal memory
        master.ar.bits.qos   := 0.U // Quality of Service
    }
}
