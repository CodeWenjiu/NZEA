package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.{Backend, CsrType}

/** Ready-operand uop: every operand is a value the middle resolved (rs1/rs2 from the register file, csr_rdata from the
  * CSR port), so the backend never sees register indexes. The middle holds it stable until the backend reports done or
  * flush, which is what lets the units take a level instead of a handshake.
  *
  * `op` is the decoded instruction whole, in the window its backend owns (`nzea_core.back.int.IntOp`,
  * `nzea_core.back.mem.MemOp`), so this contract does not change when a backend adds a unit or an operation.
  */
class IssueUop(width: Int) extends Bundle:
  val pc = Aligned(UInt(width))
  val imm = Aligned(UInt(width))
  val rs1 = Aligned(UInt(width))
  val rs2 = Aligned(UInt(width))
  val backend = Aligned(UInt(Backend.width))
  val op = Aligned(UInt(OpField.Width))
  val csr_type = Aligned(UInt(CsrType.width))
  val csr_rdata = Aligned(UInt(width))
