package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, Mux1H}
import nzea_core.backend.fu.{AluOut, BruOut, LsuOut, SysuOut}
import nzea_core.frontend.FuType

/** One entry in the commit queue (ROB): fu_type + rd_index. rd_index comes from queue head at commit. */
class CommitEntry extends Bundle {
  val fu_type  = FuType()
  val rd_index = UInt(5.W)
}

/** WBU: four FU inputs + commit queue; commit_in head gives fu_type and rd_index. In-order commit. */
class WBU extends Module {
  val io = IO(new Bundle {
    val alu_in    = Flipped(Decoupled(new AluOut))
    val bru_in    = Flipped(Decoupled(new BruOut))
    val lsu_in    = Flipped(Decoupled(new LsuOut))
    val sysu_in   = Flipped(Decoupled(new SysuOut))
    val commit_in = Flipped(Decoupled(new CommitEntry))
    val gpr_wr   = Output(new Bundle {
      val addr = UInt(5.W)
      val data = UInt(32.W)
    })
  })

  val c = io.commit_in.bits
  val alu_ok  = io.commit_in.valid && (c.fu_type === FuType.ALU)
  val bru_ok  = io.commit_in.valid && (c.fu_type === FuType.BRU)
  val lsu_ok  = io.commit_in.valid && (c.fu_type === FuType.LSU)
  val sysu_ok = io.commit_in.valid && (c.fu_type === FuType.SYSU)

  // FU ready: if valid low -> ready high; if valid high -> ready high only when commit_in says this FU
  io.alu_in.ready  := !io.alu_in.valid  || alu_ok
  io.bru_in.ready  := !io.bru_in.valid  || bru_ok
  io.lsu_in.ready  := !io.lsu_in.valid  || lsu_ok
  io.sysu_in.ready := !io.sysu_in.valid || sysu_ok

  io.commit_in.ready := (alu_ok  && io.alu_in.valid) || (bru_ok  && io.bru_in.valid) ||
                        (lsu_ok  && io.lsu_in.valid) || (sysu_ok && io.sysu_in.valid)

  val fires   = Seq(io.alu_in.fire, io.bru_in.fire, io.lsu_in.fire, io.sysu_in.fire)
  val anyFire = fires.reduce(_ || _)
  val sel     = fires :+ !anyFire
  val rd_data = Mux1H(sel, Seq(io.alu_in.bits.rd_data, io.bru_in.bits.rd_data, io.lsu_in.bits.rd_data, io.sysu_in.bits.rd_data, 0.U(32.W)))

  io.gpr_wr.addr := Mux(anyFire, io.commit_in.bits.rd_index, 0.U)
  io.gpr_wr.data := rd_data
}
