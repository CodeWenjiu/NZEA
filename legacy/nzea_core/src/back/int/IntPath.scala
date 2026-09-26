package nzea_core.back.int

import chisel3._
import chisel3.util.{Mux1H, MuxLookup}
import nzea_core.CoreTagWidths
import nzea_core.back.ExecPathPort
import nzea_core.isa.{AluOp, AluSrc, BruOp, CsrType, DivOp, FuDecode, FuType, MulOp, SysuOp}
import nzea_config.core.CoreConfig

/** Integer execution path: dispatch over ALU/BRU/MUL/DIV/SYSU, all shared FU implementations driven combinationally
  * from the held issue uop. This is the shape a real scheduler (in-order queue or OoO issue) later grows into; the
  * merging point in [[nzea_core.back.Back]] only sees [[ExecPathPort]].
  */
class IntPath(width: Int)(implicit config: CoreConfig) extends Module {
  private val tagWidth = CoreTagWidths.lsuTag
  private val waddrWidth = CoreTagWidths.regIndex
  private val hasM = config.isaConfig.hasM

  val io = IO(new Bundle {
    val path = new ExecPathPort(width)
  })

  private val uopValid = io.path.issue.valid
  private val u = io.path.issue.bits

  private val alu = Module(new ALU(tagWidth, waddrWidth))
  private val bru = Module(new BRU(tagWidth, waddrWidth))
  private val sysu = Module(new SYSU(tagWidth, waddrWidth))
  private val mul = Option.when(hasM)(Module(new MUL(tagWidth, waddrWidth)))
  private val div = Option.when(hasM)(Module(new DIV(tagWidth, waddrWidth)))

  private val isAlu = u.fu_type === FuType.ALU
  private val isBru = u.fu_type === FuType.BRU
  private val isMul = u.fu_type === FuType.MUL
  private val isDiv = u.fu_type === FuType.DIV
  private val isSysu = u.fu_type === FuType.SYSU

  // BRU/MUL are un-buffered pipelines and take one entry pulse per uop; a held level would re-admit the same uop.
  private val uopPulse = uopValid && !RegNext(uopValid, false.B)

  alu.io.in.valid := uopValid && isAlu
  private val (aluSrc0, _) = AluSrc.safe(FuDecode.take(u.fu_src, AluSrc.getWidth))

  alu.io.in.bits.opA := MuxLookup(aluSrc0.asUInt, u.rs1)(
    Seq(AluSrc.ImmZero.asUInt -> u.imm, AluSrc.PcImm.asUInt -> u.pc)
  )

  alu.io.in.bits.opB := MuxLookup(aluSrc0.asUInt, u.rs2)(
    Seq(
      AluSrc.Rs1Imm.asUInt -> u.imm,
      AluSrc.ImmZero.asUInt -> 0.U(32.W),
      AluSrc.PcImm.asUInt -> u.imm
    )
  )

  alu.io.in.bits.aluOp := AluOp.safe(FuDecode.take(u.fu_op, AluOp.getWidth))._1
  alu.io.in.bits.pc := u.pc
  alu.io.in.bits.tag := 0.U
  alu.io.in.bits.waddr := 0.U
  alu.io.out.ready := true.B
  alu.io.out.flush := io.path.flush

  bru.io.in.valid := uopPulse && isBru
  bru.io.in.bits.pc := u.pc
  bru.io.in.bits.pred_next_pc := u.pc + 4.U
  bru.io.in.bits.offset := u.imm
  bru.io.in.bits.rs1 := u.rs1
  bru.io.in.bits.rs2 := u.rs2
  bru.io.in.bits.bruOp := BruOp.safe(FuDecode.take(u.fu_op, BruOp.getWidth))._1
  bru.io.in.bits.tag := 0.U
  bru.io.in.bits.waddr := 0.U
  bru.io.out.ready := true.B
  bru.io.out.flush := io.path.flush

  sysu.io.in.valid := uopValid && isSysu
  sysu.io.in.bits.tag := 0.U
  sysu.io.in.bits.pc := u.pc
  sysu.io.in.bits.waddr := 0.U
  sysu.io.in.bits.csr_type := u.csr_type
  sysu.io.in.bits.csr_rdata := u.csr_rdata
  sysu.io.in.bits.rs1_val := u.rs1
  sysu.io.in.bits.sysuOp := SysuOp.safe(FuDecode.take(u.fu_op, SysuOp.getWidth))._1
  sysu.io.in.bits.imm := u.imm
  sysu.io.out.ready := true.B
  sysu.io.out.flush := io.path.flush

  mul.foreach { m =>
    m.io.in.valid := uopPulse && isMul
    m.io.in.bits.opA := u.rs1
    m.io.in.bits.opB := u.rs2
    m.io.in.bits.mulOp := MulOp.safe(FuDecode.take(u.fu_op, MulOp.getWidth))._1
    m.io.in.bits.pc := u.pc
    m.io.in.bits.tag := 0.U
    m.io.in.bits.waddr := 0.U
    m.io.out.ready := true.B
    m.io.out.flush := io.path.flush
  }

  div.foreach { d =>
    d.io.in.valid := uopValid && isDiv
    d.io.in.bits.opA := u.rs1
    d.io.in.bits.opB := u.rs2
    d.io.in.bits.divOp := DivOp.safe(FuDecode.take(u.fu_op, DivOp.getWidth))._1
    d.io.in.bits.pc := u.pc
    d.io.in.bits.tag := 0.U
    d.io.in.bits.waddr := 0.U
    d.io.out.ready := true.B
    d.io.out.flush := io.path.flush
  }

  private val done = uopValid &&
    ((isAlu && alu.io.completion.valid) ||
      (isBru && bru.io.completion.valid) ||
      (isSysu && sysu.io.completion.valid) ||
      mul.map(m => isMul && m.io.completion.valid).getOrElse(false.B) ||
      div.map(d => isDiv && d.io.completion.valid).getOrElse(false.B))

  private val exuData = Mux1H(
    Seq(isAlu -> alu.io.out.bits.data, isBru -> bru.io.out.bits.data, isSysu -> sysu.io.out.bits.data) ++
      mul.map(m => isMul -> m.io.out.bits.data).toSeq ++
      div.map(d => isDiv -> d.io.out.bits.data).toSeq
  )

  io.path.done := done
  io.path.result.data := exuData
  io.path.result.next_pc := Mux(isBru, bru.io.completion.bits.next_pc, u.pc + 4.U)
  io.path.result.csr_type := Mux(isSysu, sysu.io.completion.bits.csr_type, CsrType.None)
  io.path.result.csr_data := Mux(isSysu, sysu.io.completion.bits.csr_data, 0.U(32.W))
  io.path.result.is_mmio := false.B

  io.path.redirect.valid := done && isBru && bru.io.completion.bits.flush
  io.path.redirect.bits := bru.io.flush_target
}