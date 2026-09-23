package nzea_core

import chisel3._
import nzea_core.isa.CsrType
import nzea_config.core.CoreConfig

/** Commit logical state (external): the core-level commit message, carrying only architectural state changes. */
class CommitMsg(implicit config: CoreConfig) extends Bundle {
  val next_pc = UInt(32.W)
  val rd_index = UInt(5.W)
  val rd_value = UInt(32.W)
  val is_mmio = Bool() // set by the LSU when committing a device (MMIO) load or store
  val csr_type = CsrType()
  val csr_data = UInt(32.W)
}