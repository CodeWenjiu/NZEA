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
import utility.CacheTemplate
import freechips.rocketchip.rocket.DCache
import riscv_soc.cpu.backend.LS_state.s_mmio_write1
import riscv_soc.cpu.backend.LS_state.s_mmio_write2
import riscv_soc.cpu.frontend.IFU_state.s_idle
import freechips.rocketchip.rocket.DCSR
import riscv_soc.cpu.backend.LS_state.s_cache_update_2

class LSU_catch extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle{
        val clock = Input(Clock())
        val valid = Input(Bool())
        val pc    = Input(UInt(32.W))
        val diff_skip = Input(Bool())
        val skip_val = Input(UInt(32.W))
    })
    val code = 
    s"""module LSU_catch(
    |   input clock,
    |   input valid,
    |   input [31:0] pc,
    |   input diff_skip,
    |   input [31:0] skip_val
    |);
    |  import "DPI-C" function void LSU_catch(input bit [31:0] pc, input bit diff_skip, input bit [31:0] skip_val);
    |  always @(posedge clock) begin
    |     if(valid) begin
    |         LSU_catch(pc, diff_skip, skip_val);
    |     end
    |  end
    |endmodule
    """

    setInline("LSU_catch.v", code.stripMargin)
}

object LS_state extends ChiselEnum{
  val s_idle,

      s_memWriteBack_1,
      s_memWriteBack_2,
      s_memWriteBack_3,

      s_cache_update_1,
      s_cache_update_2,

      s_mmio_read1,
      s_mmio_read2,

      s_mmio_write1,
      s_mmio_write2,
      s_mmio_write3

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
        val state = RegInit(LS_state.s_idle)

        master.ar.bits.id    := 1.U
        master.ar.bits.burst := 1.U
        master.ar.bits.lock  := 0.U
        master.ar.bits.cache := 0.U
        master.ar.bits.prot  := 0.U
        master.ar.bits.qos   := 0.U

        master.aw.bits.id    := 1.U
        master.aw.bits.burst := 0.U
        master.aw.bits.lock  := 0.U
        master.aw.bits.cache := 0.U
        master.aw.bits.prot  := 0.U
        master.aw.bits.qos   := 0.U

        val addr = WireDefault(io.ISU_2_LSU.bits.addr)
        val fire = io.ISU_2_LSU.fire
        val data = WireDefault(io.ISU_2_LSU.bits.data)
        
        val (mmio_address, set, way, block_size) = Config.Dcache_Param.get

        val burst_transfer_time = block_size / 4
        val block_bits = if (burst_transfer_time > 1) log2Up(burst_transfer_time) else 0
                   
        val Dcache = Module(new CacheTemplate(
            set = set,
            way = way,
            mmio_range = Some(mmio_address),
            block_num = burst_transfer_time,
            name = "dcache",
            with_valid = true,
            with_fence = true,
        ))

        val is_st = MuxLookup(io.ISU_2_LSU.bits.Ctrl, false.B)(Seq(
            LsCtrl.SB -> true.B,
            LsCtrl.SH -> true.B,
            LsCtrl.SW -> true.B
        ))
        val is_ld = !is_st

        val en = fire && !io.is_flush

        Dcache.io.areq.ren := en && is_ld
        val wb = Dcache.io.areq.wb.get
        wb.wen := en && is_st
        wb.wdata := data
        wb.wmask := io.ISU_2_LSU.bits.mask

        Dcache.io.areq.addr := addr

        Dcache.io.rreq.valid := state === s_cache_update_2
        Dcache.io.rreq.bits.addr := addr
        Dcache.io.rreq.bits.data := master.r.bits.data

        val cache_hit = Dcache.io.areq.hit
        state := MuxLookup(state, LS_state.s_idle)(Seq(
            LS_state.s_idle -> MuxCase(state, Seq(
                io.is_flush -> state,
                cache_hit -> state,
                !fire -> state,
                wb.mmio -> Mux(is_ld, LS_state.s_mmio_read1, LS_state.s_mmio_write1),
                wb.is_dirty -> LS_state.s_memWriteBack_1,
                !wb.is_dirty -> LS_state.s_cache_update_1
            )),

            LS_state.s_memWriteBack_1 -> Mux(master.aw.fire, LS_state.s_memWriteBack_2, Mux(io.is_flush, LS_state.s_idle, state)),
            LS_state.s_memWriteBack_2 -> Mux(master.w.fire && master.w.bits.last, LS_state.s_memWriteBack_3, state),
            LS_state.s_memWriteBack_3 -> Mux(master.b.fire, LS_state.s_cache_update_1, state),

            LS_state.s_cache_update_1 -> Mux(master.ar.fire, LS_state.s_cache_update_2, state),
            LS_state.s_cache_update_2 -> Mux(master.r.fire && master.r.bits.last, LS_state.s_idle, state),

            LS_state.s_mmio_read1 -> Mux(master.ar.fire, LS_state.s_mmio_read2, Mux(io.is_flush, LS_state.s_idle, state)),
            LS_state.s_mmio_read2 -> Mux(master.r.fire && master.r.bits.last, LS_state.s_idle, state),

            LS_state.s_mmio_write1 -> MuxCase(state, Seq(
                (master.aw.fire && master.w.fire) -> LS_state.s_mmio_write3,
                master.aw.fire -> LS_state.s_mmio_write2,
                io.is_flush -> LS_state.s_idle
            )),
            LS_state.s_mmio_write2 -> Mux(master.w.fire, LS_state.s_mmio_write3, state),
            LS_state.s_mmio_write3 -> Mux(master.b.fire, LS_state.s_idle, state)
        ))

        io.ISU_2_LSU.ready := io.LSU_2_WBU.ready /*is it needed?*/ && state === (LS_state.s_idle)

        master.ar.bits.addr := Mux(state === LS_state.s_mmio_read1, addr, Cat(addr(31, block_bits + 2), 0.U((block_bits + 2).W)))
        master.ar.valid := state.isOneOf(LS_state.s_mmio_read1, LS_state.s_cache_update_1)
        master.ar.bits.len   := Mux(state === LS_state.s_mmio_read1, 0.U, (burst_transfer_time - 1).U)

        io.LSU_2_WBU.valid := MuxLookup(state, false.B)(Seq(
            LS_state.s_idle -> (fire && cache_hit),
            LS_state.s_mmio_read2 -> master.r.fire,
            LS_state.s_mmio_write3 -> master.b.fire,
        ))
        master.r.ready := MuxLookup(state, false.B)(Seq(
            LS_state.s_mmio_read2 -> io.LSU_2_WBU.ready,
            LS_state.s_cache_update_2 -> true.B,
        ))
        master.b.ready := MuxLookup(state, false.B)(Seq(
            LS_state.s_mmio_write3 -> io.LSU_2_WBU.ready,
            LS_state.s_memWriteBack_3 -> true.B
        ))
        
        master.aw.bits.addr := Mux(state === LS_state.s_mmio_write1, addr, wb.write_back_addr)
        master.aw.valid := state.isOneOf(LS_state.s_mmio_write1, LS_state.s_memWriteBack_1)
        master.aw.bits.len  := Mux(state === LS_state.s_mmio_write1, 0.U, (burst_transfer_time - 1).U)
        
        master.w.valid := state.isOneOf(LS_state.s_mmio_write1, LS_state.s_mmio_write2, LS_state.s_memWriteBack_2)
        master.w.bits.strb := Mux(state.isOneOf(LS_state.s_mmio_write1, LS_state.s_mmio_write2), io.ISU_2_LSU.bits.mask, "b1111".U(4.W)) 
        val write_back_counter = Counter(0 until burst_transfer_time, master.w.fire, state === LS_state.s_idle)
        master.w.bits.last := Mux(state.isOneOf(LS_state.s_mmio_write1, LS_state.s_mmio_write2), true.B, write_back_counter._1 === (burst_transfer_time - 1).U)

        master.w.bits.data := Mux(state.isOneOf(LS_state.s_mmio_write1, LS_state.s_mmio_write2), data, wb.write_back(write_back_counter._1))

        val mem_rdata = (Mux(state === LS_state.s_idle, Dcache.io.areq.rdata, master.r.bits.data) >> (addr(1,0) << 3.U))(31, 0)
        
        val rdata = MuxLookup(io.ISU_2_LSU.bits.Ctrl, 0.U)(Seq(
            LsCtrl.LBU -> Cat(Fill(24, 0.U), mem_rdata(7,0)),
            LsCtrl.LB  -> Cat(Fill(24, mem_rdata(7)), mem_rdata(7,0)),
            LsCtrl.LHU -> Cat(Fill(16, 0.U), mem_rdata(15,0)),
            LsCtrl.LH -> Cat(Fill(16, mem_rdata(15)), mem_rdata(15,0)),
            LsCtrl.LW -> mem_rdata(31,0).asUInt,
        ))

        val gpr_waddr = Mux(io.ISU_2_LSU.bits.Ctrl.isOneOf(LsCtrl.SB, LsCtrl.SH, LsCtrl.SW), 0.U, io.ISU_2_LSU.bits.gpr_waddr)

        io.LSU_2_WBU.bits.basic.pc := io.ISU_2_LSU.bits.basic.pc
        io.LSU_2_WBU.bits.basic.npc := io.ISU_2_LSU.bits.basic.npc
        io.LSU_2_WBU.bits.basic.trap.traped := false.B
        io.LSU_2_WBU.bits.basic.trap.trap_type := Trap_type.Ebreak

        io.LSU_2_WBU.bits.Result := rdata
        io.LSU_2_WBU.bits.CSR_rdata := 0.U

        io.LSU_2_WBU.bits.gpr_waddr := gpr_waddr
        io.LSU_2_WBU.bits.CSR_waddr := 0.U
        io.LSU_2_WBU.bits.wbCtrl := WbCtrl.Write_GPR

        if(Config.Simulate) {
            val Catch = Module(new LSU_catch)
            Catch.io.clock := clock
            Catch.io.valid := io.LSU_2_WBU.fire && !reset.asBool
            Catch.io.pc    := io.LSU_2_WBU.bits.basic.pc
            Catch.io.diff_skip := Config.diff_mis_map.map(_.contains(io.ISU_2_LSU.bits.addr)).reduce(_ || _)
            Catch.io.skip_val := rdata
        }
    }
}