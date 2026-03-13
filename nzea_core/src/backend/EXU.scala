package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, Valid}
import nzea_core.PipeIO
import nzea_core.frontend.{CsrWriteBundle, PrfWriteBundle}
import nzea_core.frontend.bp.BpUpdate
import nzea_core.retire.rob.{RobAccessIO, RobMemReq}

/** fu_op unified width: max of all FU opcode widths; used by decode/IDU/ISU. */
object FuOpWidth {
  val Width: Int = Seq(AluOp.getWidth, BruOp.getWidth, LsuOp.getWidth, SysuOp.getWidth).max
}

/** EXU: 4 FU input buses; FUs write to Rob (commit) and PRF (direct); AGU enqueues to LS_Queue. */
class EXU(robIdWidth: Int, prfAddrWidth: Int) extends Module {
  val alu  = Module(new ALU(robIdWidth, prfAddrWidth))
  val bru  = Module(new BRU(robIdWidth, prfAddrWidth))
  val agu  = Module(new AGU(robIdWidth, prfAddrWidth))
  val sysu = Module(new SYSU(robIdWidth, prfAddrWidth))

  val io = IO(new Bundle {
    val alu_in  = Flipped(new PipeIO(new AluInput(robIdWidth, prfAddrWidth)))
    val bru_in  = Flipped(new PipeIO(new BruInput(robIdWidth, prfAddrWidth)))
    val agu_in  = Flipped(new PipeIO(new AguInput(robIdWidth, prfAddrWidth)))
    val sysu_in = Flipped(new PipeIO(new SysuInput(robIdWidth, prfAddrWidth)))

    val alu_rob_access  = new RobAccessIO(robIdWidth)
    val bru_rob_access  = new RobAccessIO(robIdWidth)
    val sysu_rob_access = new RobAccessIO(robIdWidth)
    val agu_rob_access  = new RobAccessIO(robIdWidth)
    val agu_ls_enq      = Decoupled(new RobMemReq(robIdWidth, prfAddrWidth))

    val alu_prf_write  = Output(Valid(new PrfWriteBundle(prfAddrWidth)))
    val bru_prf_write  = Output(Valid(new PrfWriteBundle(prfAddrWidth)))
    val sysu_prf_write = Output(Valid(new PrfWriteBundle(prfAddrWidth)))
    val csr_write      = Output(Valid(new CsrWriteBundle))

    val bru_bp_update = Output(Valid(new BpUpdate))
  })

  io.alu_in <> alu.io.in
  io.bru_in <> bru.io.in
  io.agu_in <> agu.io.in
  io.sysu_in <> sysu.io.in

  io.alu_rob_access <> alu.io.rob_access
  io.bru_rob_access <> bru.io.rob_access
  io.sysu_rob_access <> sysu.io.rob_access
  io.agu_rob_access <> agu.io.rob_access
  io.agu_ls_enq <> agu.io.ls_enq

  io.alu_prf_write  := alu.io.prf_write
  io.bru_prf_write  := bru.io.prf_write
  io.sysu_prf_write := sysu.io.prf_write
  io.csr_write      := sysu.io.csr_write
  io.bru_bp_update := bru.io.bp_update
}
