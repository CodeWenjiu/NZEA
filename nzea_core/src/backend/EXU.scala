package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, Valid}
import nzea_core.backend.fu.{AluInput, AluOut, BruInput, BruOut, LsuInput, LsuOut, SysuOut}
import nzea_core.backend.fu.{AluOp, BruOp, LsuOp}
import nzea_core.CoreBusReadWrite

/** fu_op unified width: max of all FU opcode widths; used by decode/IDU/ISU. */
object FuOpWidth {
  val Width: Int = Seq(AluOp.getWidth, BruOp.getWidth, LsuOp.getWidth).max
}

/** EXU: 4 FU input buses, 4 FU output buses (each its own type); pc_redirect from BRU; dbus from LSU. */
class EXU(lsuBusGen: () => CoreBusReadWrite) extends Module {
  val io = IO(new Bundle {
    val alu         = Flipped(Decoupled(new AluInput))
    val bru         = Flipped(Decoupled(new BruInput))
    val lsu         = Flipped(Decoupled(new LsuInput))
    val sysu        = Flipped(Decoupled(new Bundle {}))
    val alu_out     = Decoupled(new AluOut)
    val bru_out     = Decoupled(new BruOut)
    val lsu_out     = Decoupled(new LsuOut)
    val sysu_out    = Decoupled(new SysuOut)
    val pc_redirect = Output(Valid(UInt(32.W)))
    val dbus        = lsuBusGen()
  })

  val alu  = Module(new fu.ALU)
  val bru  = Module(new fu.BRU)
  val lsu  = Module(new fu.LSU(lsuBusGen))
  val sysu = Module(new fu.SYSU)

  io.pc_redirect := bru.io.pc_redirect
  io.dbus.req.valid  := lsu.io.bus.req.valid
  io.dbus.req.bits   := lsu.io.bus.req.bits
  lsu.io.bus.req.ready := io.dbus.req.ready
  lsu.io.bus.resp.valid := io.dbus.resp.valid
  lsu.io.bus.resp.bits  := io.dbus.resp.bits
  io.dbus.resp.ready := lsu.io.bus.resp.ready
  io.alu <> alu.io.in
  io.bru <> bru.io.in
  io.lsu <> lsu.io.in
  io.sysu <> sysu.io.in

  io.alu_out  <> alu.io.out
  io.bru_out  <> bru.io.out
  io.lsu_out  <> lsu.io.out
  io.sysu_out <> sysu.io.out
}
