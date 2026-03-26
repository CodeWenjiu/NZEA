package nzea_core.backend.vector

import chisel3._

/** Subset of RVV OP-IVV / OP-IVI ops handled by [[VALU]] (single-lane / first element in this scaffold). */
object ValuOp extends ChiselEnum {
  val VorVv  = Value
  val VsubVv = Value
  val VaddVi = Value
}
