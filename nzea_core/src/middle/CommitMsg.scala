package nzea_core.middle

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.{CsrType, Gpr}

/** Architectural state change of one committed instruction — the core's external commit trace.
  *
  * Only what the reference model needs: where the instruction went next, the register it wrote, the CSR it touched. The
  * CSR rides as an architectural fact — `csr_valid` and the CSR *address*, not the internal `CsrType` code — so a
  * consumer (a debugger, a simulator bridge) needs no knowledge of the core's ISA types.
  */
class CommitMsg(width: Int) extends Bundle:
    val next_pc = Aligned(UInt(width))
    val rd_index = Aligned(UInt(Gpr.indexWidth))
    val rd_value = Aligned(UInt(width))
    val is_mmio = Aligned(Bool())
    val csr_valid = Aligned(Bool())
    val csr_addr = Aligned(UInt(CsrType.addrWidth))
    val csr_data = Aligned(UInt(width))
