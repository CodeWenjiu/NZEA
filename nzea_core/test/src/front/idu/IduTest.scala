package nzea_core.front.idu

import nzea_config.core.{CoreConfig, Isa}
import nzea_core.back.OpWindow
import nzea_core.back.int.IntOp
import nzea_core.isa.{Backend, Gpr}
import nzea_rtl.stdlib.*
import utest.*

/** The decode stage's load-bearing facts: the immediate shapes are one-hot in the order the IDU selects them, and every
  * window decode can produce fits the field the IDU hands out.
  */
object IduTest extends TestSuite:

  private val configs = Seq(
    CoreConfig(Isa("riscv32i"), 0x80000000L),
    CoreConfig(Isa("riscv32im"), 0x80000000L),
    CoreConfig(Isa("riscv64i"), 0x80000000L),
    CoreConfig(Isa("riscv64im"), 0x80000000L)
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

    test("the tables follow the register width: the 64-bit rows and the shift-immediate encodings"):
      val rv32i = CoreConfig(Isa("riscv32i"), 0x80000000L)
      val rv64i = CoreConfig(Isa("riscv64i"), 0x80000000L)
      val rv64im = CoreConfig(Isa("riscv64im"), 0x80000000L)
      def names(config: CoreConfig): Set[String] = RiscvInsts.all(config).map(_.name).toSet

      assert(names(rv64i).contains("LD") && names(rv64i).contains("ADDIW") && names(rv64i).contains("SRAW"))
      assert(!names(rv32i).contains("LD") && !names(rv32i).contains("ADDIW"))
      // MULW is a word form: it needs M and 64-bit registers, so the riscv32im table has no row for it.
      assert(names(rv64im).contains("MULW") && names(rv64im).contains("REMUW"))
      assert(!names(CoreConfig(Isa("riscv32im"), 0x80000000L)).contains("MULW"))
      // SLLI's shift amount is six bits at xlen 64 (bit 25 is part of it) and five at xlen 32 (bit 25 is pinned).
      def pattern(config: CoreConfig, name: String): String = RiscvInsts.all(config).find(_.name == name).get.bitPatStr
      assert(pattern(rv64i, "SLLI") != pattern(rv32i, "SLLI"))
      // The re-encoding lands on the base row in place, and only once: RV64's table is RV32's order with rows appended.
      for name <- Seq("SLLI", "SRLI", "SRAI") do
        assert(RiscvInsts.all(rv64i).count(_.name == name) == 1)
        assert(RiscvInsts.all(rv64i).indexWhere(_.name == name) == RiscvInsts.all(rv32i).indexWhere(_.name == name))

    test("every decoded window fits the shared op field"):
      for
        config <- configs
        inst <- RiscvInsts.all(config)
      do
        val ops = IntOp.set(config.isa)
        assert(inst.dispatch.window(ops) > 0)
        assert(inst.dispatch.window(ops) < (1 << OpWindow.width(config)))
        assert(inst.dispatch.backend.lit < (1 << Backend.width))
