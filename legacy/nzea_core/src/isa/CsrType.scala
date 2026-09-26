package nzea_core.isa

import chisel3._
import chisel3.util.{Mux1H, MuxCase}

/** CSR type: None = no mapping (for blocking/bypass); others = machine-mode CSRs. ISA-level: produced by decode,
  * carried through the middle and backend, committed by the CSR file.
  */
object CsrType extends chisel3.ChiselEnum {
  val None = Value
  val Mstatus = Value
  val Mtvec = Value
  val Mepc = Value
  val Mcause = Value
  val Mscratch = Value

  val MSTATUS_ADDR = 0x300
  val MTVEC_ADDR = 0x305
  val MSCRATCH_ADDR = 0x340
  val MEPC_ADDR = 0x341
  val MCAUSE_ADDR = 0x342

  def fromAddr(addr: UInt): CsrType.Type = {
    val a = addr(11, 0)
    MuxCase(
      CsrType.None,
      Seq(
        (a === MSTATUS_ADDR.U) -> CsrType.Mstatus,
        (a === MTVEC_ADDR.U) -> CsrType.Mtvec,
        (a === MSCRATCH_ADDR.U) -> CsrType.Mscratch,
        (a === MEPC_ADDR.U) -> CsrType.Mepc,
        (a === MCAUSE_ADDR.U) -> CsrType.Mcause
      )
    )
  }

  /** Convert CsrType to (imm, valid) for DPI commit_trace. imm = CSR addr (12-bit), valid = csr_type =/= None. */
  def toImmValid(csrType: CsrType.Type): (UInt, Bool) = {
    val valid = csrType =/= CsrType.None
    val imm = Mux1H(
      Seq(
        csrType === CsrType.Mstatus,
        csrType === CsrType.Mtvec,
        csrType === CsrType.Mepc,
        csrType === CsrType.Mcause,
        csrType === CsrType.Mscratch
      ),
      Seq(MSTATUS_ADDR.U, MTVEC_ADDR.U, MEPC_ADDR.U, MCAUSE_ADDR.U, MSCRATCH_ADDR.U)
    )
    (imm, valid)
  }

}