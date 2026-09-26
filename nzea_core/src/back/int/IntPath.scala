package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.core.CoreConfig
import nzea_core.back.{IssueUop, PathResult}
import nzea_core.isa.CsrType
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class IntPathParameter(isa: String, defaultPc: Long, sim: Boolean) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc, sim)

given upickle.default.ReadWriter[IntPathParameter] = upickle.default.macroRW

class IntPathLayers(parameter: IntPathParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class IntPathIO(parameter: IntPathParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val issue = Flipped(Valid(new IssueUop(width)))
  val done = Aligned(Bool())
  val result = Aligned(new PathResult(width))
  val redirect = Aligned(Valid(UInt(width)))

class IntPathProbe(parameter: IntPathParameter) extends DVBundle[IntPathParameter, IntPathLayers](parameter)

/** Integer execution path: every int unit driven from the held uop, plus the branch redirect the middle forwards to the
  * front.
  *
  * This is where the unit select and the op mean something — decode only packed them into [[IntOp]]'s columns. ALU, BRU
  * and SYSU are combinational and report completion for as long as the uop is held; MUL and DIV are multi-cycle units
  * that report it from their FSM. The redirect is the resolved branch: a taken branch is always a mispredict here,
  * because the fetch stream predicts not-taken.
  */
@generator
object IntPath extends Generator[IntPathParameter, IntPathLayers, IntPathIO, IntPathProbe]:
  override def moduleName(parameter: IntPathParameter): String = "IntPath"

  def architecture(parameter: IntPathParameter) =
    val io = summon[Interface[IntPathIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    val uopValid = io.issue.valid
    val u = io.issue.bits

    val op: Referable[UInt] = u.op
    val fuOp: Node[UInt] = IntOp.fuOp(op)
    val fuOpBits: Node[Bits] = Node(fuOp.asBits)
    val fuType: Node[UInt] = IntOp.fuType(op)

    def selects(unit: FuType): Node[Bool] = fuType === unit.lit.U(FuType.width)
    val isAlu = selects(FuType.ALU)
    val isBru = selects(FuType.BRU)
    val isSysu = selects(FuType.SYSU)

    val rs1Bits: Node[Bits] = Node(u.rs1.asBits)
    val rs2Bits: Node[Bits] = Node(u.rs2.asBits)

    // ALU: operands by source select, one result per op class, 4:1 on top of the four classes. The class and per-class
    // selects come off the op field directly — it is one-hot, so a result only sees the selector bits that name it.
    val aluOpA: Referable[UInt] = muxLookup(IntOp.fuSrc(op), u.rs1)(
      Seq[(Referable[UInt], Referable[UInt])](
        AluSrc.ImmZero.lit.U(AluSrc.width) -> u.imm,
        AluSrc.PcImm.lit.U(AluSrc.width) -> u.pc
      )
    )
    val aluOpB: Referable[UInt] = muxLookup(IntOp.fuSrc(op), u.rs2)(
      Seq[(Referable[UInt], Referable[UInt])](
        AluSrc.Rs1Imm.lit.U(AluSrc.width) -> u.imm,
        AluSrc.ImmZero.lit.U(AluSrc.width) -> 0.U(width),
        AluSrc.PcImm.lit.U(AluSrc.width) -> u.imm
      )
    )

    // Every ALU op reads the source-selected operands, never the raw register fields: an immediate form has no rs2 to
    // read at all (the decode stage requests x0 for it), so a raw `rs2` is zero rather than the immediate.
    val opABits: Node[Bits] = Node(aluOpA.asBits)
    val opBBits: Node[Bits] = Node(aluOpB.asBits)
    val shamt: Node[UInt] = opBBits.bits(4, 0).asUInt
    val signFill: Node[Bits] = fill(width, opABits.bit(width - 1))

    // Tree shifters, not barrel shifters: selecting a constant shift is free rewiring, so only the select network is
    // left. Arithmetic right shift rides on the logical one: a signed constant shift folds back into a logical shift
    // (the sign is dropped), so the fill is explicit, as a tree of constant masks.
    val sll: Node[UInt] =
      muxTree(shamt, (0 until width).map(i => (opABits.asUInt << i).asBits.bits(width - 1, 0).asUInt))
    val srl: Node[UInt] =
      muxTree(shamt, (0 until width).map(i => (opABits.asUInt >> i).asBits.pad(width).asUInt))
    val sra: Node[UInt] = muxTree(
      shamt,
      (0 until width).map: i =>
        val mask = if i == 0 then BigInt(0) else ((BigInt(1) << i) - 1) << (width - i)
        ((opABits.asUInt >> i).asBits.pad(width) | (mask.U(width).asBits & signFill)).asUInt
    )

    val add = prefixAdder(aluOpA, aluOpB, false.B, width)
    val sub = prefixAdder(aluOpA, (~opBBits).asUInt, true.B, width)
    val slt = (opABits.asSInt < opBBits.asSInt) ? (1.U(width), 0.U(width))
    val sltu = (aluOpA < aluOpB) ? (1.U(width), 0.U(width))

    val arith = fuOpBits.bit(1) ? (sub, add)
    val logic = mux1H(
      Node(fuOpBits.bits(4, 2)).asUInt,
      Seq((opABits & opBBits).asUInt, (opABits | opBBits).asUInt, (opABits ^ opBBits).asUInt),
      width
    )
    val shift = mux1H(Node(fuOpBits.bits(7, 5)).asUInt, Seq(sll, srl, sra), width)
    val cmp = fuOpBits.bit(9) ? (sltu, slt)
    val arithSel = fuOpBits.bit(0) | fuOpBits.bit(1)
    val logicSel = fuOpBits.bit(2) | fuOpBits.bit(3) | fuOpBits.bit(4)
    val shiftSel = fuOpBits.bit(5) | fuOpBits.bit(6) | fuOpBits.bit(7)
    val cmpSel = fuOpBits.bit(8) | fuOpBits.bit(9)
    val classSel: Node[UInt] = (cmpSel.asBits ## shiftSel.asBits ## logicSel.asBits ## arithSel.asBits).asUInt
    val aluData: Node[UInt] = mux1H(classSel, Seq(arith, logic, shift, cmp), width)

    // SYSU: the CSR read is already in the uop; the write data and whether to write at all depend on the op.
    val sysuOp: Node[UInt] = Opcode.take(fuOp, SysuOp.width)
    def isSysuOp(sysu: SysuOp): Node[Bool] = sysuOp === sysu.lit.U(SysuOp.width)
    val csrSrc = (isSysuOp(SysuOp.CSRRWI) | isSysuOp(SysuOp.CSRRSI) | isSysuOp(SysuOp.CSRRCI)) ? (u.imm, u.rs1)
    val csrOp: Node[Bits] = Node(csrSrc.asBits)
    val csrRdata: Node[Bits] = Node(u.csr_rdata.asBits)
    val csrData: Referable[UInt] = muxCase(
      0.U(width),
      Seq[(Referable[Bool], Referable[UInt])](
        (isSysuOp(SysuOp.CSRRW), u.rs1),
        (isSysuOp(SysuOp.CSRRWI), u.imm),
        (isSysuOp(SysuOp.CSRRS), (csrRdata | csrOp).asUInt),
        (isSysuOp(SysuOp.CSRRSI), (csrRdata | csrOp).asUInt),
        (isSysuOp(SysuOp.CSRRC), (csrRdata & ~csrOp).asUInt),
        (isSysuOp(SysuOp.CSRRCI), (csrRdata & ~csrOp).asUInt)
      )
    )
    // A CSRRS/CSRRC with a zero source reads without writing, which is not the same as writing the old value back. Only
    // the six CSR ops write at all: the address field of the others (ECALL, EBREAK, FENCE) is not a CSR address, even
    // when it happens to alias one.
    val csrWrite = (u.csr_type =/= CsrType.None.lit.U(CsrType.width)) & (sysuOp < SysuOp.ECALL.lit.U(SysuOp.width)) &
      muxCase(
        true.B,
        Seq(
          (isSysuOp(SysuOp.CSRRS), u.rs1 =/= 0.U(width)),
          (isSysuOp(SysuOp.CSRRC), u.rs1 =/= 0.U(width)),
          (isSysuOp(SysuOp.CSRRSI), u.imm =/= 0.U(width)),
          (isSysuOp(SysuOp.CSRRCI), u.imm =/= 0.U(width))
        )
      )
    val csrNone: Referable[UInt] = CsrType.None.lit.U(CsrType.width)

    // BRU: not-taken prediction, so `next_pc` is the target exactly when the branch is taken, and the redirect is a taken
    // branch whose target is not the fall-through (a zero-offset branch is taken but nothing changes).
    val bruOp: Node[UInt] = Opcode.take(fuOp, BruOp.width)
    val pcPlus4 = prefixAdder(u.pc, 4.U(width), false.B, width)
    val target = prefixAdder((bruOp === BruOp.JALR.lit.U(BruOp.width)) ? (u.rs1, u.pc), u.imm, false.B, width)
    val taken = mux1H(
      bruOp,
      Seq(
        true.B,
        true.B,
        u.rs1 === u.rs2,
        u.rs1 =/= u.rs2,
        (rs1Bits.asSInt < rs2Bits.asSInt),
        (rs1Bits.asSInt >= rs2Bits.asSInt),
        u.rs1 < u.rs2,
        u.rs1 >= u.rs2
      )
    )
    val nextPc = taken ? (target, pcPlus4)
    val mispredict = taken & (target =/= pcPlus4)
    io.redirect.valid := uopValid & isBru & mispredict
    io.redirect.bits := nextPc

    // MUL and DIV only exist when the ISA has M, so an ISA without it pays nothing for them.
    val mul = Option.when(parameter.config.isaConfig.hasM)(Mul.instantiate(MulParameter(width)))
    val div = Option.when(parameter.config.isaConfig.hasM)(Div.instantiate(DivParameter(width)))
    for m <- mul do
      m.io.clock := io.clock
      m.io.resetN := io.resetN
      m.io.issue.valid := uopValid & selects(FuType.MUL)
      m.io.issue.bits.opA := u.rs1
      m.io.issue.bits.opB := u.rs2
      m.io.issue.bits.op := Opcode.take(fuOp, MulOp.width)
    for d <- div do
      d.io.clock := io.clock
      d.io.resetN := io.resetN
      d.io.issue.valid := uopValid & selects(FuType.DIV)
      d.io.issue.bits.opA := u.rs1
      d.io.issue.bits.opB := u.rs2
      d.io.issue.bits.op := Opcode.take(fuOp, DivOp.width)
    val mulDone = mul.map(m => selects(FuType.MUL) & m.io.done).getOrElse(false.B)
    val divDone = div.map(d => selects(FuType.DIV) & d.io.done).getOrElse(false.B)
    val mulData: Referable[UInt] = mul.map(m => m.io.data).getOrElse(0.U(width))
    val divData: Referable[UInt] = div.map(d => d.io.data).getOrElse(0.U(width))

    val data: Referable[UInt] = muxCase(
      aluData,
      Seq[(Referable[Bool], Referable[UInt])](
        (isBru, pcPlus4),
        (isSysu, u.csr_rdata),
        (selects(FuType.MUL), mulData),
        (selects(FuType.DIV), divData)
      )
    )

    io.done := uopValid & (isAlu | isBru | isSysu | mulDone | divDone)
    io.result.data := data
    io.result.next_pc := isBru ? (nextPc, pcPlus4)
    io.result.csr_type := isSysu ? (csrWrite ? (u.csr_type, csrNone), csrNone)
    io.result.csr_data := isSysu ? (csrWrite ? (csrData, 0.U(width)), 0.U(width))
    io.result.is_mmio := false.B
