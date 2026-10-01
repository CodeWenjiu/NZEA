package nzea_core.middle

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.{CsrType, Gpr}

/** Architectural state change of one committed instruction — the core's external commit trace.
  *
  * Only what the reference model needs: where the instruction went next, the register it wrote, the CSR it touched.
  */
class CommitMsg(width: Int) extends Bundle:
    val next_pc = Aligned(UInt(width))
    val rd_index = Aligned(UInt(Gpr.indexWidth))
    val rd_value = Aligned(UInt(width))
    val is_mmio = Aligned(Bool())
    val csr_type = Aligned(UInt(CsrType.width))
    val csr_data = Aligned(UInt(width))
