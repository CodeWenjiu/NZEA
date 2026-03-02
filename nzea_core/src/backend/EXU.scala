package nzea_core.backend

import chisel3._
import nzea_core.PipeIO
import nzea_core.backend.fu.{
  AguInput,
  AluInput,
  BruInput,
  SysuInput
}
import nzea_core.backend.fu.{AluOp, BruOp, LsuOp}

/** fu_op unified width: max of all FU opcode widths; used by decode/IDU/ISU. */
object FuOpWidth {
  val Width: Int = Seq(AluOp.getWidth, BruOp.getWidth, LsuOp.getWidth).max
}

/** EXU: 4 FU input buses; FUs write to Rob via rob_access; Rob sends mem_req to MemUnit. */
class EXU(robIdWidth: Int) extends Module {
  val io = IO(new Bundle {
    val alu_in  = Flipped(new PipeIO(new AluInput(robIdWidth)))
    val bru_in  = Flipped(new PipeIO(new BruInput(robIdWidth)))
    val agu_in  = Flipped(new PipeIO(new AguInput(robIdWidth)))
    val sysu_in = Flipped(new PipeIO(new SysuInput(robIdWidth)))

    val alu_rob_access  = new RobAccessIO(robIdWidth)
    val bru_rob_access  = new RobAccessIO(robIdWidth)
    val sysu_rob_access = new RobAccessIO(robIdWidth)
    val agu_rob_access  = new RobAccessIO(robIdWidth)
  })

  val alu  = Module(new fu.ALU(robIdWidth))
  val bru  = Module(new fu.BRU(robIdWidth))
  val agu  = Module(new fu.AGU(robIdWidth))
  val sysu = Module(new fu.SYSU(robIdWidth))

  io.alu_in <> alu.io.in
  io.bru_in <> bru.io.in
  io.agu_in <> agu.io.in
  io.sysu_in <> sysu.io.in

  io.alu_rob_access <> alu.io.rob_access
  io.bru_rob_access <> bru.io.rob_access
  io.sysu_rob_access <> sysu.io.rob_access
  io.agu_rob_access <> agu.io.rob_access
}
