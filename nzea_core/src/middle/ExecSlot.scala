package nzea_core.middle

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.CoreConfig
import nzea_core.back.OpWindow
import nzea_core.isa.{Backend, Gpr}

/** The IS/EX register's payload: the ready-operand uop ([[nzea_core.back.IssueUop]]) plus the two fields the backend's
  * contract deliberately leaves out.
  *
  * `rd_index` rides here so the write-back register can latch the destination with the completion, and `csr_addr` so
  * the CSR read is addressed for the whole residence of the slot. Both used to be side registers written beside the
  * uop; as fields of the register's own payload they cost the same flops and need no fill-side write.
  */
class ExecSlot(config: CoreConfig) extends Bundle:
  val pc = Aligned(UInt(config.width))
  val pc_plus4 = Aligned(UInt(config.width))
  val imm = Aligned(UInt(config.width))
  val src_a = Aligned(UInt(config.width))
  val src_b = Aligned(UInt(config.width))
  val src_c = Aligned(UInt(config.width))
  val backend = Aligned(UInt(Backend.width))
  val op = Aligned(UInt(OpWindow.width(config)))
  val rd_index = Aligned(UInt(Gpr.indexWidth))
  val csr_addr = Aligned(UInt(12))
