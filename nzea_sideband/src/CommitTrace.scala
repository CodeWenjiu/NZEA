package nzea_sideband

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*

/** Architectural state change of one committed instruction — the commit trace: only what a reference model needs.
  *
  * The core produces it and a device (the debugger) consumes it; the shape lives here so neither depends on the other.
  * The CSR rides as an architectural `(valid, addr)` pair rather than the core's internal `CsrType` code, and the
  * widths are explicit because this layer knows no ISA (the core passes its constants at the construction site).
  */
class CommitTrace(width: Int, gprIndexWidth: Int, csrAddrWidth: Int) extends Bundle:
    val next_pc = Aligned(UInt(width))
    val rd_index = Aligned(UInt(gprIndexWidth))
    val rd_value = Aligned(UInt(width))
    val is_mmio = Aligned(Bool())
    val csr_valid = Aligned(Bool())
    val csr_addr = Aligned(UInt(csrAddrWidth))
    val csr_data = Aligned(UInt(width))
