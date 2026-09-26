package nzea_core.front.idu

import nzea_core.back.OpWindow
import nzea_core.back.int.{AluOp, BruOp, FuType, IntOp, MulOp, SysuOp}
import nzea_core.back.mem.{LsuOp, MemOp}
import nzea_core.isa.{Backend, Srcs}
import nzea_config.core.CoreConfig
import nzea_rtl.PlaSim
import nzea_rtl.stdlib.{Encoding, Qmc}
import utest.*

/** The decode tables, checked the way the IDU consumes them: through the minimized PLA.
  *
  * Every instruction is fed to the minimizer, and the PLA's output for it is compared with what the table declared.
  * That covers the whole path — patterns, field encodings and the minimization itself — without elaborating hardware.
  */
object DecodeTablesTest extends TestSuite:

  private val withM = CoreConfig("riscv32im", 0x80000000L, sim = false)
  private val withoutM = CoreConfig("riscv32i", 0x80000000L, sim = false)

  private lazy val tables = DecodeTables.all(withM)
  private lazy val pla = Qmc(tables)

  /** The decode output fields, in the order they are concatenated into the PLA output. */
  private val fields = Seq(
    DecodeTables.BackendName -> Backend.width,
    DecodeTables.OpName -> OpWindow.Width,
    DecodeTables.SrcsName -> Srcs.width,
    DecodeTables.ImmTypeName -> ImmType.width,
    DecodeTables.GprWrName -> 1,
    DecodeTables.Rs1RdName -> 1,
    DecodeTables.Rs2RdName -> 1
  )

  /** A word matching an instruction pattern: the don't-care bits are zero, which the instruction does not care about.
    */
  private def sample(inst: RVInst): BigInt = inst.pattern.terms.head.value

  /** A word matching `pattern`, with its don't-care bits filled in at random. */
  private def randomize(random: scala.util.Random, value: BigInt, mask: BigInt): BigInt =
    value | (BigInt(32, random) & ~mask)

  /** Evaluate the PLA the way its consumer builds it in hardware (see [[PlaSim]]), then slice the fields out. */
  private def decode(word: BigInt): Map[String, BigInt] =
    assert(fields.map(_._2).sum == pla.outputWidth)
    val out = PlaSim.decode(pla, word)
    fields
      .foldLeft((Map.empty[String, BigInt], pla.outputWidth)): (state, field) =>
        val (values, high) = state
        val (name, width) = field
        (values + (name -> ((out >> (high - width)) & ((BigInt(1) << width) - 1))), high - width)
      ._1

  /** What the table declares for one instruction, as the PLA must report it. */
  private def declared(inst: RVInst): Map[String, BigInt] = Map(
    DecodeTables.BackendName -> BigInt(inst.dispatch.backend.lit),
    DecodeTables.OpName -> BigInt(inst.dispatch.window),
    DecodeTables.SrcsName -> BigInt(inst.dispatch.srcs.lit),
    DecodeTables.ImmTypeName -> BigInt(inst.immType.fold(ImmType.I.lit)(_.lit)),
    DecodeTables.GprWrName -> BigInt(if inst.gprWr then 1 else 0),
    DecodeTables.Rs1RdName -> BigInt(if inst.rs1Rd then 1 else 0),
    DecodeTables.Rs2RdName -> BigInt(if inst.rs2Rd then 1 else 0)
  )

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
      for inst <- RiscvInsts.all(withM) do assert(inst.dispatch.window != 0)
      val noop = decode(BigInt(0))
      assert(noop(DecodeTables.BackendName) == BigInt(Backend.INT.lit))
      assert(noop(DecodeTables.OpName) == 0)
      assert(noop(DecodeTables.SrcsName) == BigInt(Srcs.NoOperand.lit))
      assert(noop(DecodeTables.ImmTypeName) == BigInt(ImmType.I.lit))
      assert(noop(DecodeTables.GprWrName) == 0)
      assert(noop(DecodeTables.Rs1RdName) == 0)
      assert(noop(DecodeTables.Rs2RdName) == 0)

    test("M-extension instructions are gated by the ISA config"):
      assert(RiscvInsts.all(withoutM).size == 46)
      assert(RiscvInsts.all(withM).size == 54)
      assert(!RiscvInsts.all(withoutM).exists(_.name == "MUL"))
      assert(Qmc(DecodeTables.all(withoutM)).table.nonEmpty)

    test("representative entries match the legacy decoder field for field"):
      def entry(name: String): RVInst = RiscvInsts.all(withM).find(_.name == name).get

      /** One legacy row: the columns decode must hand over, and the register/immediate flags of that class. */
      def check(
          name: String,
          backend: Backend,
          window: Int,
          srcs: Srcs,
          immType: Option[ImmType],
          gprWr: Boolean,
          rs1Rd: Boolean,
          rs2Rd: Boolean
      ): Unit =
        val inst = entry(name)
        assert(inst.dispatch.backend == backend)
        assert(inst.dispatch.window == window)
        assert(inst.dispatch.srcs == srcs)
        assert(inst.immType == immType)
        assert(inst.gprWr == gprWr)
        assert(inst.rs1Rd == rs1Rd)
        assert(inst.rs2Rd == rs2Rd)

      def intWindow(unit: FuType, op: Encoding): Int = IntOp.lit(unit, op)
      def memWindow(op: LsuOp): Int = MemOp.lit(op)

      check("LUI", Backend.INT, intWindow(FuType.ALU, AluOp.Add), Srcs.ImmZero, Some(ImmType.U), true, false, false)
      check("AUIPC", Backend.INT, intWindow(FuType.ALU, AluOp.Add), Srcs.PcImm, Some(ImmType.U), true, false, false)
      check("JAL", Backend.INT, intWindow(FuType.BRU, BruOp.JAL), Srcs.NoOperand, Some(ImmType.J), true, false, false)
      check("JALR", Backend.INT, intWindow(FuType.BRU, BruOp.JALR), Srcs.Rs1Imm, Some(ImmType.I), true, true, false)
      check("LW", Backend.MEM, memWindow(LsuOp.LW), Srcs.Rs1Imm, Some(ImmType.I), true, true, false)
      check("SB", Backend.MEM, memWindow(LsuOp.SB), Srcs.Rs1ImmRs2, Some(ImmType.S), false, true, true)
      check("ADDI", Backend.INT, intWindow(FuType.ALU, AluOp.Add), Srcs.Rs1Imm, Some(ImmType.I), true, true, false)
      check("SLLI", Backend.INT, intWindow(FuType.ALU, AluOp.Sll), Srcs.Rs1Imm, Some(ImmType.I), true, true, false)
      check("ADD", Backend.INT, intWindow(FuType.ALU, AluOp.Add), Srcs.Rs1Rs2, None, true, true, true)
      check("BEQ", Backend.INT, intWindow(FuType.BRU, BruOp.BEQ), Srcs.Rs1Rs2, Some(ImmType.B), false, true, true)
      check(
        "ECALL",
        Backend.INT,
        IntOp.lit(FuType.SYSU, SysuOp.ECALL),
        Srcs.NoOperand,
        Some(ImmType.I),
        false,
        false,
        false
      )
      check("CSRRW", Backend.INT, IntOp.lit(FuType.SYSU, SysuOp.CSRRW), Srcs.Rs1, Some(ImmType.I), true, true, true)
      check(
        "CSRRWI",
        Backend.INT,
        IntOp.lit(FuType.SYSU, SysuOp.CSRRWI),
        Srcs.ImmZero,
        Some(ImmType.Z),
        true,
        false,
        false
      )
      check("FENCE", Backend.INT, IntOp.lit(FuType.SYSU, SysuOp.FENCE), Srcs.NoOperand, None, false, false, false)
      check("MUL", Backend.INT, IntOp.lit(FuType.MUL, MulOp.Mul), Srcs.Rs1Rs2, None, true, true, true)
