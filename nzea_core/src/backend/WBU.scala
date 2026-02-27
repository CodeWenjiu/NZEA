package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, Mux1H, Queue}
import nzea_core.backend.fu.{AluOut, AguOut, BruOut, SysuOut}
import nzea_core.frontend.FuType
import nzea_core.CoreBusReadWrite

/** One entry in the Rob: fu_type + rd_index (GPR write address). */
class RobEntry extends Bundle {
  val fu_type  = FuType()
  val rd_index = UInt(5.W)
}

/** WBU: four FU inputs; Rob (Queue) inside; head gives fu_type and rd_index for in-order commit.
  * AGU output goes to MemUnit for actual memory access; dbus exposed for Core.
  */
class WBU(dbusGen: () => CoreBusReadWrite) extends Module {
  val io = IO(new Bundle {
    val alu_in   = Flipped(Decoupled(new AluOut))
    val bru_in   = Flipped(Decoupled(new BruOut))
    val agu_in   = Flipped(Decoupled(new AguOut))
    val sysu_in  = Flipped(Decoupled(new SysuOut))
    val rob_enq  = Flipped(Decoupled(new RobEntry))
    val gpr_wr   = Output(new Bundle {
      val addr = UInt(5.W)
      val data = UInt(32.W)
    })
    val dbus    = dbusGen()
  })

  val memUnit = Module(new MemUnit(dbusGen))

  val rob   = Queue(io.rob_enq, 4)
  val head  = rob.bits
  val alu_ok  = rob.valid && (head.fu_type === FuType.ALU)
  val bru_ok  = rob.valid && (head.fu_type === FuType.BRU)
  val lsu_ok  = rob.valid && (head.fu_type === FuType.LSU)
  val sysu_ok = rob.valid && (head.fu_type === FuType.SYSU)

  memUnit.io.req.valid := lsu_ok && io.agu_in.valid
  memUnit.io.req.bits  := io.agu_in.bits
  io.agu_in.ready  := !io.agu_in.valid || (lsu_ok && memUnit.io.req.ready)

  io.alu_in.ready  := !io.alu_in.valid  || alu_ok
  io.bru_in.ready  := !io.bru_in.valid  || bru_ok
  io.sysu_in.ready := !io.sysu_in.valid || sysu_ok

  val lsu_done = lsu_ok && memUnit.io.ready
  rob.ready := (alu_ok  && io.alu_in.valid) || (bru_ok  && io.bru_in.valid) ||
               lsu_done || (sysu_ok && io.sysu_in.valid)

  val sel = Seq(alu_ok && io.alu_in.valid, bru_ok && io.bru_in.valid, lsu_done, sysu_ok && io.sysu_in.valid)
  val rd_data = Mux1H(sel :+ !sel.reduce(_ || _), Seq(io.alu_in.bits.rd_data, io.bru_in.bits.rd_data, memUnit.io.loadData, io.sysu_in.bits.rd_data, 0.U(32.W)))

  io.gpr_wr.addr := Mux(rob.ready, head.rd_index, 0.U)
  io.gpr_wr.data := rd_data

  io.dbus <> memUnit.io.dbus
}
