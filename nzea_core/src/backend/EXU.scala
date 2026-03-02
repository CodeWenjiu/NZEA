package nzea_core.backend

import chisel3._
import nzea_core.PipeIO
import nzea_core.backend.fu.{
  AluInput,
  AluOut,
  AguInput,
  AguOut,
  BruInput,
  BruOut,
  SysuInput,
  SysuOut
}
import nzea_core.backend.fu.{AluOp, BruOp, LsuOp}

/** fu_op unified width: max of all FU opcode widths; used by decode/IDU/ISU. */
object FuOpWidth {
  val Width: Int = Seq(AluOp.getWidth, BruOp.getWidth, LsuOp.getWidth).max
}

/** EXU: 4 FU input buses, 4 FU output buses (each its own type). AGU outputs to
  * WBU (no dbus). robIdWidth from upper level.
  */
class EXU(robIdWidth: Int) extends Module {
  val io = IO(new Bundle {
    val alu_in  = Flipped(new PipeIO(new AluInput(robIdWidth)))
    val bru_in  = Flipped(new PipeIO(new BruInput(robIdWidth)))
    val agu_in  = Flipped(new PipeIO(new AguInput(robIdWidth)))
    val sysu_in = Flipped(new PipeIO(new SysuInput(robIdWidth)))

    val alu_out  = new PipeIO(new AluOut)
    val bru_out  = new PipeIO(new BruOut)
    val agu_out  = new PipeIO(new AguOut)
    val sysu_out = new PipeIO(new SysuOut)
  })

  val alu  = Module(new fu.ALU(robIdWidth))
  val bru  = Module(new fu.BRU(robIdWidth))
  val agu  = Module(new fu.AGU(robIdWidth))
  val sysu = Module(new fu.SYSU(robIdWidth))

  io.alu_in <> alu.io.in
  io.bru_in <> bru.io.in
  io.agu_in <> agu.io.in
  io.sysu_in <> sysu.io.in
  io.alu_out <> alu.io.out
  io.bru_out <> bru.io.out
  io.agu_out <> agu.io.out
  io.sysu_out <> sysu.io.out
}
