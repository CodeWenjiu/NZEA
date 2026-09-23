package nzea_core.back

import chisel3._
import chisel3.util.Valid
import nzea_core.isa.LsuOp
import nzea_core.isa.CsrType

/** FU completion report: the uniform shape every FU drives to the backend's completion point.
  *
  * `tag` echoes the request tag carried with the uop (the in-order engine uses it to match bus responses); `flush`
  * marks a branch mispredict with `next_pc` carrying the resolved target; `is_done` is completion, not write-back
  * validity (a DIV/REM targeting x0 still completes). csr_type/csr_data carry a commit-side CSR write (CsrType.None
  * otherwise).
  */
class FuCompletion(tagWidth: Int) extends Bundle {
  val tag = UInt(tagWidth.W)
  val is_done = Bool()
  val flush = Bool()
  val next_pc = UInt(32.W)
  val is_mmio = Bool()
  val csr_type = CsrType()
  val csr_data = UInt(32.W)
}

object FuCompletion {

  /** Build a completion report from an FU's completion condition. */
  def report(
      valid: Bool,
      tag: UInt,
      is_done: Bool,
      flush: Bool = false.B,
      next_pc: UInt = 0.U,
      is_mmio: Bool = false.B,
      csr_type: CsrType.Type = CsrType.None,
      csr_data: UInt = 0.U
  )(tagWidth: Int): Valid[FuCompletion] = {
    val w = Wire(Valid(new FuCompletion(tagWidth)))
    w.valid := valid
    w.bits.tag := tag
    w.bits.is_done := is_done
    w.bits.flush := flush
    w.bits.next_pc := next_pc
    w.bits.is_mmio := is_mmio
    w.bits.csr_type := csr_type
    w.bits.csr_data := csr_data
    w
  }

}

/** Memory request from the backend to [[MemUnit]]: address/op plus the request tag (echoed on the response for
  * matching) and the register-write index (0 when no register is written).
  */
class MemRequest(tagWidth: Int, waddrWidth: Int) extends Bundle {
  val tag = UInt(tagWidth.W)
  val addr = UInt(32.W)
  val wdata = UInt(32.W)
  val wstrb = UInt(4.W)
  val lsuOp = LsuOp()
  val waddr = UInt(waddrWidth.W)
}