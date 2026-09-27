package nzea_core.front.idu

import me.jiuyang.decoder.PLA
import nzea_core.CoreConfig
import nzea_core.back.OpWindow
import nzea_core.back.int.{AluOp, BruOp, DivOp, IntOp, MulOp, SysuOp}
import nzea_core.back.mem.{LsuOp, MemOp}
import nzea_core.isa.{Backend, Isa, Srcs}
import nzea_rtl.PlaSim
import nzea_rtl.stdlib.{Encoding, Qmc}
import utest.*

/** The decode tables, checked the way the IDU consumes them: through the minimized PLA.
  *
  * Every instruction is fed to the minimizer, and the PLA's output for it is compared with what the table declared.
  * That covers the whole path — patterns, field encodings and the minimization itself — without elaborating hardware.
  */
object DecodeTablesTest extends TestSuite:

  private val withM = CoreConfig(Isa("riscv32im"), 0x80000000L)
  private val withoutM = CoreConfig(Isa("riscv32i"), 0x80000000L)
  private val rv64i = CoreConfig(Isa("riscv64i"), 0x80000000L)
  private val rv64im = CoreConfig(Isa("riscv64im"), 0x80000000L)

  private val intOps = IntOp.set(withM.isa)
  private val intOps64 = IntOp.set(rv64im.isa)

  private lazy val tables = DecodeTables.all(withM)
  private lazy val pla = Qmc(tables)
  private lazy val tables64 = DecodeTables.all(rv64im)
  private lazy val pla64 = Qmc(tables64)

  /** The decode output fields, in the order they are concatenated into the PLA output. */
  private def fields(config: CoreConfig): Seq[(String, Int)] = Seq(
    DecodeTables.BackendName -> Backend.width,
    DecodeTables.OpName -> OpWindow.width(config),
    DecodeTables.SrcsName -> Srcs.width,
    DecodeTables.ImmTypeName -> ImmType.width,
    DecodeTables.GprWrName -> 1
  )

  /** A word matching an instruction pattern: the don't-care bits are zero, which the instruction does not care about.
    */
  private def sample(inst: RVInst): BigInt = inst.pattern.terms.head.value

  /** A word matching `pattern`, with its don't-care bits filled in at random. */
  private def randomize(random: scala.util.Random, value: BigInt, mask: BigInt): BigInt =
    value | (BigInt(32, random) & ~mask)

  /** Evaluate the PLA the way its consumer builds it in hardware (see [[PlaSim]]), then slice the fields out. */
  private def decodeWith(config: CoreConfig, pla: PLA, word: BigInt): Map[String, BigInt] =
    val fieldList = fields(config)
    assert(fieldList.map(_._2).sum == pla.outputWidth)
    val out = PlaSim.decode(pla, word)
    fieldList
      .foldLeft((Map.empty[String, BigInt], pla.outputWidth)): (state, field) =>
        val (values, high) = state
        val (name, width) = field
        (values + (name -> ((out >> (high - width)) & ((BigInt(1) << width) - 1))), high - width)
      ._1

  private def decode(word: BigInt): Map[String, BigInt] = decodeWith(withM, pla, word)

  /** What the table declares for one instruction, as the PLA must report it. */
  private def declaredWith(ops: Encoding.UnionSet[IntOp, Isa], inst: RVInst): Map[String, BigInt] = Map(
    DecodeTables.BackendName -> BigInt(inst.dispatch.backend.lit),
    DecodeTables.OpName -> BigInt(inst.dispatch.window(ops)),
    DecodeTables.SrcsName -> BigInt(inst.dispatch.srcs.lit),
    DecodeTables.ImmTypeName -> BigInt(inst.immType.fold(ImmType.I.lit)(_.lit)),
    DecodeTables.GprWrName -> BigInt(if inst.gprWr then 1 else 0)
  )

  private def declared(inst: RVInst): Map[String, BigInt] = declaredWith(intOps, inst)

  val tests = Tests:
    test("every instruction minimizes into the PLA and decodes back to its declared fields"):
      for inst <- RiscvInsts.all(withM) do assert(decode(sample(inst)) == declared(inst))

    test("the minimized PLA agrees with the tables on any word"):
      // Instructions with their don't-care bits filled in, plus arbitrary words, which land in no entry at all and must
      // come back as the defaults — the case an over-covering minimizer would get wrong.
      val random = new scala.util.Random(20260926L)
      val words = RiscvInsts.all(withM).map(inst => randomize(random, sample(inst), inst.pattern.terms.head.mask)) ++
        Seq.fill(500)(BigInt(32, random))
      for word <- words do assert(PlaSim.decode(pla, word) == PlaSim.reference(tables, word))

    test("no instruction aliases the no-op an unknown instruction decodes to"):
      // The zero window and the zero flags are the defaults, so a real instruction must differ from them.
      for inst <- RiscvInsts.all(withM) do assert(inst.dispatch.window(intOps) != 0)
      val noop = decode(BigInt(0))
      assert(noop(DecodeTables.BackendName) == BigInt(Backend.INT.lit))
      assert(noop(DecodeTables.OpName) == 0)
      assert(noop(DecodeTables.SrcsName) == BigInt(Srcs.NoOperand.lit))
      assert(noop(DecodeTables.ImmTypeName) == BigInt(ImmType.I.lit))
      assert(noop(DecodeTables.GprWrName) == 0)

    test("M-extension instructions are gated by the ISA config"):
      assert(RiscvInsts.all(withoutM).size == 46)
      assert(RiscvInsts.all(withM).size == 54)
      assert(!RiscvInsts.all(withoutM).exists(_.name == "MUL"))
      assert(Qmc(DecodeTables.all(withoutM)).table.nonEmpty)

    test("a re-encoded row takes the base row it shares a name with"):
      def rows(config: CoreConfig, name: String): Seq[RVInst] = RiscvInsts.all(config).filter(_.name == name)
      // The shift immediates are one instruction name in both ISAs: without 64-bit registers the base row stands, with
      // them the re-encoded row replaces it where it is — a row that only got appended would be a second pattern for
      // the same name.
      assert(rows(withoutM, "SLLI") == Seq(RiscvInsts.SLLI))
      assert(rows(rv64i, "SLLI") == Seq(RiscvInsts.SLLI64))
      assert(rows(withoutM, "SRAI") == Seq(RiscvInsts.SRAI))
      assert(rows(rv64i, "SRAI") == Seq(RiscvInsts.SRAI64))

    test("the 64-bit tables minimize and decode back to their declared fields too"):
      assert(RiscvInsts.all(rv64i).size == 58)
      assert(RiscvInsts.all(rv64im).size == 71)
      for inst <- RiscvInsts.all(rv64im) do
        assert(decodeWith(rv64im, pla64, sample(inst)) == declaredWith(intOps64, inst))

    test("the 64-bit rows carry the word-form and 64-bit windows"):
      def entry(name: String): RVInst = RiscvInsts.all(rv64im).find(_.name == name).get
      def window(name: String): Int = entry(name).dispatch.window(intOps64)
      def memWindow(op: LsuOp): Int = MemOp.set(rv64im.isa).lit(MemOp.Lsu(op))
      assert(window("ADDIW") == intOps64.lit(IntOp.Alu(AluOp.Addw)))
      assert(window("SRAW") == intOps64.lit(IntOp.Alu(AluOp.Sraw)))
      assert(window("MULW") == intOps64.lit(IntOp.Mul(MulOp.Mulw)))
      assert(window("REMUW") == intOps64.lit(IntOp.Div(DivOp.Remuw)))
      assert(window("LD") == memWindow(LsuOp.LD))
      assert(window("SD") == memWindow(LsuOp.SD))
      assert(window("LWU") == memWindow(LsuOp.LWU))

    test("representative entries match the legacy decoder field for field"):
      def entry(name: String): RVInst = RiscvInsts.all(withM).find(_.name == name).get

      /** One legacy row: the columns decode must hand over, and the register/immediate flags of that class. */
      def check(
          name: String,
          backend: Backend,
          window: Int,
          srcs: Srcs,
          immType: Option[ImmType],
          gprWr: Boolean
      ): Unit =
        val inst = entry(name)
        assert(inst.dispatch.backend == backend)
        assert(inst.dispatch.window(intOps) == window)
        assert(inst.dispatch.srcs == srcs)
        assert(inst.immType == immType)
        assert(inst.gprWr == gprWr)

      def intWindow(op: IntOp): Int = intOps.lit(op)
      def memWindow(op: LsuOp): Int = MemOp.set(withM.isa).lit(MemOp.Lsu(op))

      check("LUI", Backend.INT, intWindow(IntOp.Alu(AluOp.Add)), Srcs.ImmZero, Some(ImmType.U), true)
      check("AUIPC", Backend.INT, intWindow(IntOp.Alu(AluOp.Add)), Srcs.PcImm, Some(ImmType.U), true)
      check("JAL", Backend.INT, intWindow(IntOp.Bru(BruOp.JAL)), Srcs.NoOperand, Some(ImmType.J), true)
      check("JALR", Backend.INT, intWindow(IntOp.Bru(BruOp.JALR)), Srcs.Rs1Imm, Some(ImmType.I), true)
      check("LW", Backend.MEM, memWindow(LsuOp.LW), Srcs.Rs1Imm, Some(ImmType.I), true)
      check("SB", Backend.MEM, memWindow(LsuOp.SB), Srcs.Rs1ImmRs2, Some(ImmType.S), false)
      check("ADDI", Backend.INT, intWindow(IntOp.Alu(AluOp.Add)), Srcs.Rs1Imm, Some(ImmType.I), true)
      check("SLLI", Backend.INT, intWindow(IntOp.Alu(AluOp.Sll)), Srcs.Rs1Imm, Some(ImmType.I), true)
      check("ADD", Backend.INT, intWindow(IntOp.Alu(AluOp.Add)), Srcs.Rs1Rs2, None, true)
      check("BEQ", Backend.INT, intWindow(IntOp.Bru(BruOp.BEQ)), Srcs.Rs1Rs2, Some(ImmType.B), false)
      check(
        "ECALL",
        Backend.INT,
        intOps.lit(IntOp.Sysu(SysuOp.ECALL)),
        Srcs.NoOperand,
        Some(ImmType.I),
        false
      )
      check("CSRRW", Backend.INT, intOps.lit(IntOp.Sysu(SysuOp.CSRRW)), Srcs.Rs1, Some(ImmType.I), true)
      check(
        "CSRRWI",
        Backend.INT,
        intOps.lit(IntOp.Sysu(SysuOp.CSRRWI)),
        Srcs.ImmZero,
        Some(ImmType.Z),
        true
      )
      check("FENCE", Backend.INT, intOps.lit(IntOp.Sysu(SysuOp.FENCE)), Srcs.NoOperand, None, false)
      check("MUL", Backend.INT, intOps.lit(IntOp.Mul(MulOp.Mul)), Srcs.Rs1Rs2, None, true)
