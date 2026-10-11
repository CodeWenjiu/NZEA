package nzea_core.front.idu

import me.jiuyang.decoder.PLA
import nzea_core.config.CoreConfig
import nzea_core.back.OpWindow
import nzea_core.back.int.{AluOp, BruOp, DivOp, IntOp, MulOp, SysuOp}
import nzea_core.back.mem.{LsuOp, MemOp}
import nzea_core.front.idu.inst.*
import nzea_core.isa.{Backend, Gpr, Isa, Srcs}
import nzea_rtl.PlaSim
import nzea_rtl.stdlib.*
import utest.*

/** The decode tables, checked the way the IDU consumes them: through the minimized PLA.
  *
  * Every instruction is fed to the minimizer, and the PLA's output for it is compared with what the table declared.
  * That covers the whole path — patterns, field encodings and the minimization itself — without elaborating hardware.
  * Beside the tables, the stage's own load-bearing facts: the immediate shapes are one-hot in the order the IDU selects
  * them, and every window decode can produce fits the field the IDU hands out.
  */
object DecodeTest extends TestSuite:

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

    private val configs = Seq(withM, withoutM, rv64i, rv64im)

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
        // ── The stage's facts ──
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
            def names(config: CoreConfig): Set[String] = RiscvInsts.all(config.isa).map(_.name).toSet

            assert(names(rv64i).contains("LD") && names(rv64i).contains("ADDIW") && names(rv64i).contains("SRAW"))
            assert(!names(rv32i).contains("LD") && !names(rv32i).contains("ADDIW"))
            // MULW is a word form: it needs M and 64-bit registers, so the riscv32im table has no row for it.
            assert(names(rv64im).contains("MULW") && names(rv64im).contains("REMUW"))
            assert(!names(CoreConfig(Isa("riscv32im"), 0x80000000L)).contains("MULW"))
            // SLLI's shift amount is six bits at xlen 64 (bit 25 is part of it) and five at xlen 32 (bit 25 is pinned).
            def pattern(config: CoreConfig, name: String): String =
                RiscvInsts.all(config.isa).find(_.name == name).get.bitPatStr
            assert(pattern(rv64i, "SLLI") != pattern(rv32i, "SLLI"))
            // The re-encoding lands on the base row in place, and only once: RV64's table is RV32's order with rows appended.
            for name <- Seq("SLLI", "SRLI", "SRAI") do
                assert(RiscvInsts.all(rv64i.isa).count(_.name == name) == 1)
                assert(
                    RiscvInsts.all(rv64i.isa).indexWhere(_.name == name) == RiscvInsts
                        .all(rv32i.isa)
                        .indexWhere(_.name == name)
                )

        test("every decoded window fits the shared op field"):
            for
                config <- configs
                inst <- RiscvInsts.all(config.isa)
            do
                val ops = IntOp.set(config.isa)
                assert(inst.dispatch.window(ops) > 0)
                assert(inst.dispatch.window(ops) < (1 << OpWindow.width(config)))
                assert(inst.dispatch.backend.lit < (1 << Backend.width))

        // ── The tables through the PLA ──
        test("every instruction minimizes into the PLA and decodes back to its declared fields"):
            for inst <- RiscvInsts.all(withM.isa) do assert(decode(sample(inst)) == declared(inst))

        test("the minimized PLA agrees with the tables on any word"):
            // Instructions with their don't-care bits filled in, plus arbitrary words, which land in no entry at all and must
            // come back as the defaults — the case an over-covering minimizer would get wrong.
            val random = new scala.util.Random(20260926L)
            val words =
                RiscvInsts.all(withM.isa).map(inst => randomize(random, sample(inst), inst.pattern.terms.head.mask)) ++
                    Seq.fill(500)(BigInt(32, random))
            for word <- words do assert(PlaSim.decode(pla, word) == PlaSim.reference(tables, word))

        test("no instruction aliases the no-op an unknown instruction decodes to"):
            // The zero window and the zero flags are the defaults, so a real instruction must differ from them.
            for inst <- RiscvInsts.all(withM.isa) do assert(inst.dispatch.window(intOps) != 0)
            val noop = decode(BigInt(0))
            assert(noop(DecodeTables.BackendName) == BigInt(Backend.INT.lit))
            assert(noop(DecodeTables.OpName) == 0)
            assert(noop(DecodeTables.SrcsName) == BigInt(Srcs.NoOperand.lit))
            assert(noop(DecodeTables.ImmTypeName) == BigInt(ImmType.I.lit))
            assert(noop(DecodeTables.GprWrName) == 0)

        test("M-extension instructions are gated by the ISA config"):
            assert(RiscvInsts.all(withoutM.isa).size == 46)
            assert(RiscvInsts.all(withM.isa).size == 54)
            assert(!RiscvInsts.all(withoutM.isa).exists(_.name == "MUL"))
            assert(Qmc(DecodeTables.all(withoutM)).table.nonEmpty)

        test("a re-encoded row takes the base row it shares a name with"):
            def rows(config: CoreConfig, name: String): Seq[RVInst] = RiscvInsts.all(config.isa).filter(_.name == name)
            // The shift immediates are one instruction name in both ISAs: without 64-bit registers the base row stands, with
            // them the re-encoded row replaces it where it is — a row that only got appended would be a second pattern for
            // the same name.
            assert(rows(withoutM, "SLLI") == Seq(InstI.SLLI))
            assert(rows(rv64i, "SLLI") == Seq(InstI.SLLI64))
            assert(rows(withoutM, "SRAI") == Seq(InstI.SRAI))
            assert(rows(rv64i, "SRAI") == Seq(InstI.SRAI64))

        test("the 64-bit tables minimize and decode back to their declared fields too"):
            assert(RiscvInsts.all(rv64i.isa).size == 58)
            assert(RiscvInsts.all(rv64im.isa).size == 71)
            for inst <- RiscvInsts.all(rv64im.isa) do
                assert(decodeWith(rv64im, pla64, sample(inst)) == declaredWith(intOps64, inst))

        test("the 64-bit rows carry the word-form and 64-bit windows"):
            def entry(name: String): RVInst = RiscvInsts.all(rv64im.isa).find(_.name == name).get
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
            def entry(name: String): RVInst = RiscvInsts.all(withM.isa).find(_.name == name).get

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
            check("JAL", Backend.INT, intWindow(IntOp.Bru(BruOp.JAL)), Srcs.PcImm, Some(ImmType.J), true)
            check("JALR", Backend.INT, intWindow(IntOp.Bru(BruOp.JALR)), Srcs.Rs1Imm, Some(ImmType.I), true)
            check("LW", Backend.MEM, memWindow(LsuOp.LW), Srcs.Rs1Imm, Some(ImmType.I), true)
            check("SB", Backend.MEM, memWindow(LsuOp.SB), Srcs.Rs1ImmRs2, Some(ImmType.S), false)
            check("ADDI", Backend.INT, intWindow(IntOp.Alu(AluOp.Add)), Srcs.Rs1Imm, Some(ImmType.I), true)
            check("SLLI", Backend.INT, intWindow(IntOp.Alu(AluOp.Sll)), Srcs.Rs1Imm, Some(ImmType.I), true)
            check("ADD", Backend.INT, intWindow(IntOp.Alu(AluOp.Add)), Srcs.Rs1Rs2, None, true)
            check("BEQ", Backend.INT, intWindow(IntOp.Bru(BruOp.BEQ)), Srcs.PcRs1Rs2, Some(ImmType.B), false)
            check(
                "ECALL",
                Backend.INT,
                intOps.lit(IntOp.Sysu(SysuOp.ECALL)),
                Srcs.NoOperand,
                Some(ImmType.I),
                false
            )
            check("CSRRW", Backend.INT, intOps.lit(IntOp.Sysu(SysuOp.CSRRW)), Srcs.Rs1Csr, Some(ImmType.I), true)
            check(
                "CSRRWI",
                Backend.INT,
                intOps.lit(IntOp.Sysu(SysuOp.CSRRWI)),
                Srcs.ImmCsr,
                Some(ImmType.Z),
                true
            )
            check("FENCE", Backend.INT, intOps.lit(IntOp.Sysu(SysuOp.FENCE)), Srcs.NoOperand, None, false)
            check("MUL", Backend.INT, intOps.lit(IntOp.Mul(MulOp.Mul)), Srcs.Rs1Rs2, None, true)
