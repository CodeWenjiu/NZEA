package nzea_core.back

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.isa.{Backend, CsrType, Gpr, Srcs}
import nzea_rtl.stdlib.*

/** The IS/EX register's payload: the uop the middle hands the back, in the back's own vocabulary.
  *
  * Four kinds of field ride here and nothing else. The fetch's facts — the pc, the prediction it goes on with, the
  * instruction's own fall-through and the direction counter its prediction read — are carried since the fetch and no
  * stage recomputes them. The dispatch's contract — the backend and the decoded op — is what the back routes and each
  * path decodes by. The write-back's keys — the destination register and the CSR code — are what the forward network
  * and the retire record name their writers by. And the operand pool is what the issue stage's form filled: four
  * positional slots that each unit reads through its own view (`nzea_core.back.int.AluInput` and its siblings). This
  * payload knows only that the pool is there — never which unit reads which slot.
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

    /** The operand pool: the slots the issue stage's form filled ([[nzea_core.isa.Srcs.Arity]] of them), named per unit
      * in that unit's own view ([[nzea_core.back.FuView]]).
      */
    val src = Aligned(StaticVec[UInt, Srcs.Arity](UInt(config.width)))
