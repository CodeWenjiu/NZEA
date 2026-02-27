package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, Valid}
import nzea_core.backend.fu.{AluInput, AluOut, AguInput, AguOut, BruInput, BruOut, SysuOut}
import nzea_core.backend.fu.{AluOp, BruOp, LsuOp}

/** fu_op unified width: max of all FU opcode widths; used by decode/IDU/ISU. */
object FuOpWidth {
  val Width: Int = Seq(AluOp.getWidth, BruOp.getWidth, LsuOp.getWidth).max
}

/** EXU: 4 FU input buses, 4 FU output buses (each its own type); pc_redirect from BRU. AGU outputs to WBU (no dbus). */
class EXU extends Module {
  val io = IO(new Bundle {
    val alu         = Flipped(Decoupled(new AluInput))
    val bru         = Flipped(Decoupled(new BruInput))
    val agu         = Flipped(Decoupled(new AguInput))
    val sysu        = Flipped(Decoupled(new Bundle {}))
    val alu_out     = Decoupled(new AluOut)
    val bru_out     = Decoupled(new BruOut)
    val agu_out     = Decoupled(new AguOut)
    val sysu_out    = Decoupled(new SysuOut)
    val pc_redirect = Output(Valid(UInt(32.W)))
  })

  val alu  = Module(new fu.ALU)
  val bru  = Module(new fu.BRU)
  val agu  = Module(new fu.AGU)
  val sysu = Module(new fu.SYSU)

  io.pc_redirect := bru.io.pc_redirect
  io.alu  <> alu.io.in
  io.bru  <> bru.io.in
  io.agu  <> agu.io.in
  io.sysu <> sysu.io.in
  io.alu_out  <> alu.io.out
  io.bru_out  <> bru.io.out
  io.agu_out  <> agu.io.out
  io.sysu_out <> sysu.io.out
}
