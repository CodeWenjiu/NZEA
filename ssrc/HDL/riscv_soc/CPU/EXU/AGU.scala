package riscv_soc

import chisel3._
import chisel3.util._

import riscv_soc.bus._

import config._

class AGU extends Module {
    val io = IO(new Bundle{
        val IDU_2_EXU = Flipped(Decoupled(Input(new BUS_IDU_2_EXU)))
        val AGU_2_LSU = Decoupled(Output(new BUS_AGU_2_LSU))
    })

    val state = RegInit(bus_state.s_wait_valid)

    state := MuxLookup(state, bus_state.s_wait_valid)(
        Seq(
            bus_state.s_wait_valid -> Mux(io.IDU_2_EXU.valid,  bus_state.s_wait_ready, bus_state.s_wait_valid),
            bus_state.s_wait_ready -> Mux(io.AGU_2_LSU.ready, bus_state.s_wait_valid, bus_state.s_wait_ready),
        )
    )

    io.AGU_2_LSU.valid := state === bus_state.s_wait_ready
    io.IDU_2_EXU.ready := state === bus_state.s_wait_valid

    val addr = WireDefault(io.IDU_2_EXU.bits.EXU_A + io.IDU_2_EXU.bits.Imm)

    io.AGU_2_LSU.bits.MemAddr := addr
    io.AGU_2_LSU.bits.MemData := (io.IDU_2_EXU.bits.EXU_B << (addr(1,0) << 3.U))(31, 0)
}
