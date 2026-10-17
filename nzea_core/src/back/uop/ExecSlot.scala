package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.isa.{Backend, CsrType, Gpr, Srcs}
import nzea_rtl.stdlib.*

/** The IS/EX register's payload: the uop the middle hands the back, in the back's own vocabulary.
  *
  * The fetch's facts ride here since the fetch and no stage recomputes them; the destination register and the CSR code
  * are what the forward network and the retire record name their writers by; and the operand pool is what the issue
  * stage's form filled — four positional slots whose readers this payload never names, each unit reading them through
  * its own view.
  */
class ExecSlot(config: CoreConfig) extends Bundle:
    val pc = Aligned(UInt(config.width))
    val next_pc = Aligned(UInt(config.width))
    val fall_through = Aligned(UInt(config.width))
    val pht_count = Aligned(UInt(BpUpdate.phtCountBits))
    val backend = Aligned(UInt(Backend.width))
    val op = Aligned(UInt(OpWindow.width(config)))
    val rd_index = Aligned(UInt(Gpr.indexWidth))
    val csr_type = Aligned(UInt(CsrType.width))

    val src = Aligned(StaticVec[UInt, Srcs.Arity](UInt(config.width)))
