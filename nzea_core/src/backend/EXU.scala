package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, Valid}
import chisel3.util.Mux1H
import nzea_core.backend.fu.{AluInput, AluOp, BruInput, BruOp}

/** fu_op unified width: max of all FU opcode widths (e.g. ALU 10b, BRU 8b one-hot); used by decode/IDU/ISU. */
object FuOpWidth {
  val Width: Int = Seq(AluOp.getWidth, BruOp.getWidth).max
}

/** EXU → WBU payload: GPR write-back. */
class ExuOut extends Bundle {
  val rd_wen  = Bool()
  val rd_addr = UInt(5.W)
  val rd_data = UInt(32.W)
}

/** EXU: exposes 4 FU input buses (for ISU pipe), aggregates to one Decoupled output to WBU; BRU pc_redirect to IFU. */
class EXU extends Module {
  val io = IO(new Bundle {
    val alu         = Flipped(Decoupled(new AluInput))
    val bru         = Flipped(Decoupled(new BruInput))
    val lsu         = Flipped(Decoupled(new Bundle {}))
    val sysu        = Flipped(Decoupled(new Bundle {}))
    val out         = Decoupled(new ExuOut)
    val pc_redirect = Output(Valid(UInt(32.W)))
  })

  val alu  = Module(new fu.ALU)
  val bru  = Module(new fu.BRU)
  val lsu  = Module(new fu.LSU)
  val sysu = Module(new fu.SYSU)

  io.pc_redirect := bru.io.pc_redirect
  io.alu <> alu.io.in
  io.bru <> bru.io.in
  io.lsu  <> lsu.io.in
  io.sysu <> sysu.io.in

  val fuOuts   = Seq(alu.io.out, bru.io.out, lsu.io.out, sysu.io.out)
  val fuValids = fuOuts.map(_.valid)
  val anyValid = fuValids.reduce(_ || _)
  val default  = Wire(new ExuOut)
  default.rd_wen := false.B
  default.rd_addr := 0.U
  default.rd_data := 0.U
  io.out.valid := anyValid
  io.out.bits  := Mux1H(fuValids :+ !anyValid, fuOuts.map(_.bits) :+ default)
  fuOuts.foreach(_.ready := io.out.ready)
}
