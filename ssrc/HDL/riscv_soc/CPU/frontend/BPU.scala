package riscv_soc.cpu.frontend

import chisel3._
import chisel3.util._
import riscv_soc.bus._
import config._
import utility.CacheTemplate

class FLush_PC extends Bundle {
    val pc = UInt(32.W)
    val target = UInt(32.W)
}

class BPU extends Module {
    val io = IO(new Bundle {
        val pc = Input(UInt(32.W))

        val hit = Output(Bool())
        val npc = Output(UInt(32.W))

        val flush_pc = Flipped(ValidIO(new FLush_PC))
    })
    
    val btb_depth = 16
    
    val btb = Module(
        new CacheTemplate(
            set = btb_depth, 
            name = "btb", 
            with_valid = Config.Four_state_sim,
        )
    )

    val prediction = btb.io.areq
    prediction.addr := io.pc

    prediction.valid := !io.flush_pc.valid
    btb.io.rreq.valid := io.flush_pc.valid
    btb.io.rreq.bits.addr := io.flush_pc.bits.pc
    btb.io.rreq.bits.data := io.flush_pc.bits.target

    io.hit := prediction.hit
    io.npc := prediction.data
}
