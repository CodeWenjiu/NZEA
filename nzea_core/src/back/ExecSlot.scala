package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.isa.{Backend, CsrType, Gpr}

/** The IS/EX register's payload: the ready-operand uop ([[nzea_core.back.IssueUop]]) plus the fields the backend's
  * contract deliberately leaves out.
  *
  * `rd_index` rides here so the write-back register can latch the destination with the completion; the CSR pair —
  * `csr_type`, the code the instruction's address names, and `csr_rdata`, the value the read settled on — rides like an
  * operand does, resolved at the instruction's issue, so the execute stage consumes the CSR without ever naming the
  * file. All of them used to be side registers written beside the uop; as fields of the register's own payload they
  * cost the same flops and need no fill-side write.
  */
class ExecSlot(config: CoreConfig) extends Bundle:
    val pc = Aligned(UInt(config.width))
    val next_pc = Aligned(UInt(config.width))
    val fall_through = Aligned(UInt(config.width))
    val pht_count = Aligned(UInt(BpUpdate.phtCountBits))
    val imm = Aligned(UInt(config.width))
    val src_a = Aligned(UInt(config.width))
    val src_b = Aligned(UInt(config.width))
    val src_c = Aligned(UInt(config.width))
    val backend = Aligned(UInt(Backend.width))
    val op = Aligned(UInt(OpWindow.width(config)))
    val rd_index = Aligned(UInt(Gpr.indexWidth))
    val csr_type = Aligned(UInt(CsrType.width))
    val csr_rdata = Aligned(UInt(config.width))
