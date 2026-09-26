package nzea_core.isa

/** The integer register file's shape: `x0..x31` in every RISC-V base ISA, so its index width does not follow xlen. */
object Gpr:
  val depth: Int = 32
  val indexWidth: Int = 5
