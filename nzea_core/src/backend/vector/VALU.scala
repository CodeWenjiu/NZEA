package nzea_core.backend.vector

import chisel3._
import chisel3.util.{MuxCase, Valid}
import nzea_rtl.PipeIO
import nzea_core.retire.rob.Rob
import nzea_config.CoreConfig

/** Subset of RVV OP-IVV / OP-IVI ops handled by [[VALU]] (single-lane / first element in this scaffold). */
object ValuOp extends ChiselEnum {
  val VorVv  = Value
  val VsubVv = Value
  val VaddVi = Value
}

/** Vector integer ALU: single 32-bit result (first-lane scaffold). */
class VALU(robIdWidth: Int)(implicit config: CoreConfig) extends Module {
  private val pvrAddrWidth = config.pvrAddrWidth

  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new ValuInput(robIdWidth, pvrAddrWidth)))
    val out        = new PipeIO(new VrfWriteBundle(pvrAddrWidth))
    val rob_access = Output(Valid(new nzea_core.retire.rob.RobEntryStateUpdate(robIdWidth)))
  })

  val op = io.in.bits.valu_op

  val useImmAsB = op === ValuOp.VaddVi
  val opB       = Mux(useImmAsB, io.in.bits.imm, io.in.bits.vs2)
  val add       = io.in.bits.vs1 + opB
  val sub       = io.in.bits.vs1 - opB
  val orr       = io.in.bits.vs1 | opB

  val result = MuxCase(
    0.U(32.W),
    Seq(
      (op === ValuOp.VorVv)  -> orr,
      (op === ValuOp.VsubVv) -> sub,
      (op === ValuOp.VaddVi) -> add
    )
  )

  io.rob_access := Rob.entryStateUpdate(io.in.valid, io.in.bits.rob_id, is_done = true.B)(robIdWidth)
  io.in.ready := io.out.ready
  io.out.valid := io.in.valid
  io.out.bits.addr := io.in.bits.p_vd
  io.out.bits.data := result
  io.in.flush := io.out.flush
}
