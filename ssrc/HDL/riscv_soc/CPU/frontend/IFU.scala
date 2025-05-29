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

class Icache_catch extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle{
        val Icache = Input(Bool())
        val map_hit = Input(Bool())
        val cache_hit = Input(Bool())
    })
    val code =
    s"""
    |module Icache_catch(
    |   input Icache,
    |   input map_hit,
    |   input cache_hit
    |);
    |
    |   import "DPI-C" function void Icache_catch(input bit map_hit, input bit cache_hit);
    |   always @(posedge Icache) begin
    |       Icache_catch(map_hit, cache_hit);
    |   end
    |
    |endmodule
    """

    setInline("Icache_catch.v", code.stripMargin)
}

class Icache_state_catch extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle {
        val valid = Input(Bool())

        val write_index = Input(UInt(32.W))
        val write_way = Input(UInt(32.W))
        val write_tag = Input(UInt(32.W))
        val write_data = Input(UInt((Config.Icache_Param.block_size * 8).W))

        val flush = Input(Bool())
    })
    val code = 
    s"""
    |module Icache_state_catch(
    |    input valid,
    |
    |    input [31:0] write_index,
    |    input [31:0] write_way,
    |    input [31:0] write_tag,
    |    input [${Config.Icache_Param.block_size * 8 - 1}:0] write_data,
    |
    |    input flush
    |);
    |
    |   import "DPI-C" function void Icache_state_catch(input bit [31:0] write_index, input bit [31:0] write_way, input bit [31:0] write_tag, input bit [${Config.Icache_Param.block_size * 8 - 1}:0] write_data);
    |   always @(posedge valid) begin
    |       Icache_state_catch(write_index, write_way, write_tag, write_data);
    |   end
    |
    |   import "DPI-C" function void Icache_flush();
    |   always @(posedge flush) begin
    |       Icache_flush();
    |   end
    |
    |endmodule
    """

    setInline("Icache_state_catch.v", code.stripMargin)
}

class Icache_MAT_catch extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle {
        val valid = Input(Bool())
        val count = Input(UInt(32.W))
    })
    val code =
    s"""
    |module Icache_MAT_catch(
    |    input valid,
    |    input [31:0] count
    |);
    |
    |   import "DPI-C" function void Icache_MAT_catch(input bit [31:0] count);
    |   always @(posedge valid) begin
    |       Icache_MAT_catch(count);
    |   end
    |
    |endmodule
    """

    setInline("Icache_MAT_catch.v", code.stripMargin)
}

class Icache(address: Seq[AddressSet], way: Int, set: Int, block_size: Int) extends Module {
    val io = IO(new Bundle{
        val addr = Flipped(ValidIO(Input(UInt(32.W))))
        val data = Output(UInt((Config.Icache_Param.block_size * 8).W))

        val cache_hit = Output(Bool())
        val replace_data = Flipped(ValidIO(Input(UInt((Config.Icache_Param.block_size * 8).W))))
        val replace_addr = Input(UInt(32.W))

        val flush = Input(Bool())
    })

    val valid_width = log2Ceil(address.map(_.mask).reduce(_ max _))
    val offset_width = log2Ceil(block_size)
    val set_width = log2Ceil(set)
    val tag_width = valid_width - offset_width - set_width

    // a vector(Way) of Mem(Set)
    val valid_array = RegInit(VecInit(Seq.fill(set)(0.U(way.W))))

    when(io.flush){
        valid_array.foreach(_ := 0.U)
    }
    
    val meta = Mem(set, Vec(way, UInt((tag_width).W)))
    val data = Mem(set, Vec(way, UInt((block_size * 8).W)))

    val set_index = io.addr.bits(set_width + offset_width - 1, offset_width)
    val tag = io.addr.bits(valid_width - 1, set_width + offset_width)
    
    val metas = meta.read(set_index)

    val data_set = data.read(set_index)

    val valid_vec = VecInit(valid_array(set_index).asBools)
    val tag_equal_vec = VecInit(metas.map(_ === tag))
    val tag_match_vec = tag_equal_vec.zip(valid_vec).map{case (a, b) => a && b}
    val tag_match = tag_match_vec.reduce(_ | _)
    val match_way = Mux1H(tag_match_vec, (0 until way).map(_.U))
    
    io.data := data_set(match_way)
    
    io.cache_hit := tag_match

    val replace_set_index = io.replace_addr(set_width + offset_width - 1, offset_width) // input addr maybe change after input shake hands
    
    val replacement = ReplacementPolicy.fromString("setlru", way, set)
    
    val replace_way = replacement.way(replace_set_index)
    val replace_way_mask = UIntToOH(replace_way, way)

    val replace_tag = io.replace_addr(valid_width - 1, set_width + offset_width)
    val replace_tag_v = VecInit((0 until way).map(_ => replace_tag))

    val replace_cache = io.replace_data.bits
    val replace_cache_v = VecInit((0 until way).map(_ => replace_cache))

    when(io.replace_data.valid){
        valid_array(replace_set_index) := valid_array(replace_set_index).bitSet(replace_way, true.B)
        meta.write(replace_set_index, replace_tag_v, replace_way_mask.asBools)
        data.write(replace_set_index, replace_cache_v, replace_way_mask.asBools)
        replacement.access(replace_set_index, replace_way)
    }.elsewhen(RegNext(tag_match && io.addr.valid)){
        replacement.access(replace_set_index, match_way)
    }

    if(Config.Simulate){
        val Icache_state = Module(new Icache_state_catch)
        Icache_state.io.valid := io.replace_data.valid
        Icache_state.io.write_index := replace_set_index
        Icache_state.io.write_way := replace_way
        Icache_state.io.write_tag := replace_tag
        Icache_state.io.write_data := replace_cache

        Icache_state.io.flush := false.B
    }
}

object IFU_state extends ChiselEnum{
  val s_wait_valid,
      s_try_fetch,
      s_send_addr,
      s_get_data,
      s_fetch,
      s_replace_send_addr,
      s_replace_get_data,
      s_Icache_write
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
            val WBU_2_IFU = Flipped(new WBU_2_IFU)
            val IFU_2_IDU = Decoupled(Output(new IFU_2_IDU))

            val Pipeline_ctrl = Flipped(new Pipeline_ctrl)
        })

        val state = RegInit(IFU_state.s_try_fetch)

        val flush = Wire(Bool())
        flush := RegNext(MuxCase(
            flush,
            Seq(
                (io.Pipeline_ctrl.flush) -> true.B,
                (state === IFU_state.s_try_fetch) -> false.B
            )
        ), false.B)
        
        val pc = RegInit(Config.Reset_Vector)
        val snpc = pc + 4.U
        val dnpc = RegEnable(io.WBU_2_IFU.next_pc, 0.U, io.Pipeline_ctrl.flush)

        pc := MuxCase(pc, Seq(
            (flush && (state === IFU_state.s_try_fetch))    -> dnpc,
            io.IFU_2_IDU.fire                               -> snpc,
        ))

        val (master, _) = masterNode.out(0)

        val map_hit = Config.Icache_Param.address.map(_.contains(pc)).reduce(_ || _)

        val Icache = Module(new Icache(Config.Icache_Param.address, Config.Icache_Param.way, Config.Icache_Param.set, Config.Icache_Param.block_size))
        Icache.io.addr.bits := pc
        Icache.io.addr.valid := io.IFU_2_IDU.fire
        Icache.io.flush := io.Pipeline_ctrl.flush
        
        Icache.io.replace_addr := pc

        val block_size = Config.Icache_Param.block_size

        val block_num = block_size / 4

        val block_index = pc(log2Ceil(Config.Icache_Param.block_size) - 1, 2)

        val Multi_transfer = RegInit(VecInit(Seq.fill(block_num)(0.U(32.W))))
        val Multi_transfer_counter = RegInit(0.U(log2Ceil(block_num).W))

        val transfer = RegInit(0.U(32.W)) // which I dont konw why should exist

        when (master.r.fire) {
            when(state === IFU_state.s_replace_get_data){
                when(Multi_transfer_counter === (block_num - 1).U){
                    Multi_transfer_counter := 0.U
                }.otherwise{
                    Multi_transfer_counter := Multi_transfer_counter + 1.U
                }
            }
                
            Multi_transfer(block_num - 1) := master.r.bits.data
            for(i <- 0 until (block_num - 1)){
                Multi_transfer(i) := Multi_transfer(i + 1)
            }

            transfer := master.r.bits.data
        }

        io.IFU_2_IDU.valid := MuxLookup(state, false.B)(Seq(
            IFU_state.s_try_fetch -> (Icache.io.cache_hit && !(flush)),
            IFU_state.s_fetch -> !(flush),
        ))
        io.IFU_2_IDU.bits.PC := pc

        master.ar.valid := (state === IFU_state.s_replace_send_addr) || (state === IFU_state.s_send_addr)
        master.ar.bits.len := Mux(state === IFU_state.s_replace_send_addr, (block_num - 1).U, 0.U)
        master.ar.bits.addr := Mux(state === IFU_state.s_send_addr, pc, 
            (pc & ~((Config.Icache_Param.block_size - 1).U(32.W))) + (Multi_transfer_counter << 2.U))
        
        master.r.ready := (state === IFU_state.s_replace_get_data || state === IFU_state.s_get_data)

        Icache.io.replace_data.bits := Multi_transfer.asTypeOf(UInt((Config.Icache_Param.block_size * 8).W))
        Icache.io.replace_data.valid := (state === IFU_state.s_Icache_write)

        val inst = Mux(state === IFU_state.s_try_fetch, Icache.io.data.asTypeOf(Vec(Config.Icache_Param.block_size / 4, UInt(32.W)))(block_index), transfer)
            
        io.IFU_2_IDU.bits.inst := inst

        state := MuxLookup(state, IFU_state.s_wait_valid)(
            Seq(
                IFU_state.s_try_fetch -> MuxCase(IFU_state.s_try_fetch, 
                    Seq(
                        (flush) -> IFU_state.s_try_fetch,
                        (!Icache.io.cache_hit & map_hit) -> IFU_state.s_replace_send_addr,
                        (!map_hit) -> IFU_state.s_send_addr
                    )
                ),

                IFU_state.s_send_addr -> Mux(master.ar.fire,
                    IFU_state.s_get_data, IFU_state.s_send_addr),

                IFU_state.s_get_data -> Mux(master.r.fire,
                    IFU_state.s_fetch, IFU_state.s_get_data),

                IFU_state.s_fetch -> Mux((io.IFU_2_IDU.fire) || (flush),
                    IFU_state.s_try_fetch, IFU_state.s_fetch),

                IFU_state.s_replace_send_addr -> Mux(master.ar.fire,
                    IFU_state.s_replace_get_data, IFU_state.s_replace_send_addr),

                IFU_state.s_replace_get_data -> Mux(master.r.fire && (Multi_transfer_counter === (block_num - 1).U),
                    IFU_state.s_Icache_write, IFU_state.s_replace_get_data),

                IFU_state.s_Icache_write -> IFU_state.s_try_fetch
            )
        )

        if(Config.Simulate){
            val Catch = Module(new IFU_catch)
            Catch.io.clock := clock
            Catch.io.valid := io.IFU_2_IDU.fire && !reset.asBool
            Catch.io.inst := io.IFU_2_IDU.bits.inst
            Catch.io.pc := io.IFU_2_IDU.bits.PC

            val cache_Catch = Module(new Icache_catch)
            cache_Catch.io.Icache := RegNext((state === IFU_state.s_try_fetch) && !reset.asBool)
            cache_Catch.io.map_hit := map_hit
            cache_Catch.io.cache_hit := Icache.io.cache_hit & map_hit

            val MAT_Counter = RegInit(0.U(32.W))
            when(state === IFU_state.s_try_fetch){
                MAT_Counter := 1.U
            }.otherwise{
                MAT_Counter := MAT_Counter + 1.U
            }

            val MAT_Catch = Module(new Icache_MAT_catch)
            MAT_Catch.io.valid := io.IFU_2_IDU.fire && !reset.asBool
            MAT_Catch.io.count := MAT_Counter
        }

        // master ignore

        master.aw.valid := false.B
        master.aw.bits.addr := 0.U
        master.aw.bits.size := 0.U
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

        master.ar.bits.size  := 2.U
        master.ar.bits.id    := 0.U
        master.ar.bits.burst := 1.U // INCR
        master.ar.bits.lock  := 0.U // Normal access
        master.ar.bits.cache := 0.U // Cacheable
        master.ar.bits.prot  := 0.U // Normal memory
        master.ar.bits.qos   := 0.U // Quality of Service
    }
}