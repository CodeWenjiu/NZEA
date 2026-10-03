package nzea_sideband

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*

/** Architectural state change of one committed instruction — the commit trace.
  *
  * The core produces it and a device (the debugger) consumes it, and the two are independent modules: the shape lives
  * here, in the sideband layer both depend on, so neither needs the other. Only what a reference model needs — where
  * the instruction went next, the register it wrote, the CSR it touched — and the CSR rides as an architectural
  * `(valid, addr)` pair, not the core's internal `CsrType` code.
  *
  * The widths are explicit rather than derived: this layer knows no ISA, so the core passes its own constants at the
  * construction site.
  */
class CommitTrace(width: Int, gprIndexWidth: Int, csrAddrWidth: Int) extends Bundle:
    val next_pc = Aligned(UInt(width))
    val rd_index = Aligned(UInt(gprIndexWidth))
    val rd_value = Aligned(UInt(width))
    val is_mmio = Aligned(Bool())
    val csr_valid = Aligned(Bool())
    val csr_addr = Aligned(UInt(csrAddrWidth))
    val csr_data = Aligned(UInt(width))
