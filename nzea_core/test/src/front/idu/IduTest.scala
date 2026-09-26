package nzea_core.front.idu

import nzea_config.core.CoreConfig
import nzea_core.back.OpField
import nzea_core.isa.{Backend, Gpr}
import nzea_rtl.stdlib.*
import utest.*

/** The decode stage's load-bearing facts: the immediate shapes are one-hot in the order the IDU selects them, and every
  * window decode can produce fits the field the IDU hands out.
  */
object IduTest extends TestSuite:

  private val configs = Seq(
    CoreConfig("riscv32i", 0x80000000L, sim = false),
    CoreConfig("riscv32im", 0x80000000L, sim = false)
  )

  val tests = Tests:
    test("immediate shapes are one-hot in the order the IDU selects them"):
      assert(ImmType.values.toSeq == Seq(ImmType.I, ImmType.S, ImmType.B, ImmType.U, ImmType.J, ImmType.Z))
      assert(ImmType.values.map(_.lit).toSeq == ImmType.values.indices.map(1 << _))
      assert(ImmType.width == 6)

    test("the register file is xlen-independent"):
      assert(Gpr.depth == 32)
      assert(Gpr.indexWidth == 5)
      assert(1 << Gpr.indexWidth == Gpr.depth)

    test("the tables are 32-bit only, and say so instead of elaborating wrong widths"):
      assert(scala.util.Try(RiscvInsts.all(CoreConfig("riscv64i", 0x80000000L, sim = false))).isFailure)

    test("every decoded window fits the shared op field"):
      for
        config <- configs
        inst <- RiscvInsts.all(config)
      do
        assert(inst.dispatch.window > 0)
        assert(inst.dispatch.window < (1 << OpField.Width))
        assert(inst.dispatch.backend.lit < (1 << Backend.width))
