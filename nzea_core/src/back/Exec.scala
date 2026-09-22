package nzea_core.back

import chisel3._
import chisel3.util.{Mux1H, MuxLookup, Valid}
import nzea_core.CoreTagWidths
import nzea_core.{BackBusPort, CoreChannels}
import nzea_rtl.{LiteBusRW, PrefixAdder}
import nzea_core.back.MemUnit
import nzea_core.back._
import nzea_core.front.{AluSrc, CsrType, FuDecode, FuSrcWidth, FuType}
import nzea_config.core.CoreConfig

/** Completion report from the backend to the in-order middle — the single reverse channel (back → middle). */
class ExecOut(width: Int) extends Bundle {
  val done = Bool()
  val data = UInt(32.W)
  val next_pc = UInt(width.W)
  val csr_type = CsrType()
  val csr_data = UInt(32.W)
  val is_mmio = Bool()
  val flush = Bool()
  val redirect_target = UInt(width.W)
}

/** Execution-stage exit latch: the result between execute and commit. */
class MinExecResult(width: Int) extends Bundle {
  val data = UInt(32.W)
  val next_pc = UInt(width.W)
  val csr_type = CsrType()
  val csr_data = UInt(32.W)
  val is_mmio = Bool()
}

/** Ready-operand uop: all operands are values resolved by the middle (rs1/rs2 from the ARF, csr_rdata from the CSR
  * port), so the backend never sees register indexes. Held stable by the middle until done/flush.
  */
class IssueUop(width: Int)(implicit config: CoreConfig) extends Bundle {
  val pc = UInt(width.W)
  val imm = UInt(32.W)
  val fu_type = FuType()
  val fu_op = UInt(FuOpWidth.Width.W)
  val fu_src = UInt(FuSrcWidth.Width.W)
  val rd_index = UInt(5.W)
  val rs1 = UInt(32.W)
  val rs2 = UInt(32.W)
  val csr_type = CsrType()
  val csr_rdata = UInt(32.W)
}

/** Sequential execute stage: own dispatch, in-order single issue, shared FU implementations driven directly.
  *
  * Entry: fu_type selects one FU per cycle; tag/waddr stay zero (in-order needs no renaming). ALU/SYSU are
  * combinational (level valid while the uop is held); BRU/MUL are un-buffered pipelines and take one entry pulse per
  * uop (uopPulse) — a level would re-admit the same instruction every cycle; DIV buffers via a fire handshake.
  *
  * Completion: FU completion.valid / the LSU bus response, gated by uopValid so a drained FU cannot re-fire. The exit
  * latch consumes the completion edge (ALU/SYSU report multi-cycle levels) and report.done is the one-cycle pulse.
  * LSU requests are tagged (riding the tag echo) so a stale response cannot fire the next uop's done.
  */
class Exec(mmioRanges: Seq[(BigInt, BigInt)] = Seq.empty)(implicit config: CoreConfig) extends Module {
  private val width = config.width
  private val tagWidth = CoreTagWidths.lsuTag
  private val waddrWidth = CoreTagWidths.regIndex
  private val hasM = config.isaConfig.hasM

  val io = IO(new Bundle {
    val bus = new BackBusPort(width)
    val dbus = CoreChannels.dbus(width)
  })

  private val uopValid = io.bus.issue.valid
  private val u = io.bus.issue.bits

  private val alu = Module(new ALU(tagWidth, waddrWidth))
  private val bru = Module(new BRU(tagWidth, waddrWidth))
  private val sysu = Module(new SYSU(tagWidth, waddrWidth))
  private val mul = Option.when(hasM)(Module(new MUL(tagWidth, waddrWidth)))
  private val div = Option.when(hasM)(Module(new DIV(tagWidth, waddrWidth)))

  private val isAlu = u.fu_type === FuType.ALU
  private val isBru = u.fu_type === FuType.BRU
  private val isLsu = u.fu_type === FuType.LSU
  private val isMul = u.fu_type === FuType.MUL
  private val isDiv = u.fu_type === FuType.DIV
  private val isSysu = u.fu_type === FuType.SYSU

  private val flushing = uopValid && isBru && bru.io.completion.valid && bru.io.completion.bits.flush

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
  alu.io.out.flush := flushing

  bru.io.in.valid := uopPulse && isBru
  bru.io.in.bits.pc := u.pc
  bru.io.in.bits.pred_next_pc := u.pc + 4.U // always-not-taken
  bru.io.in.bits.offset := u.imm
  bru.io.in.bits.rs1 := u.rs1
  bru.io.in.bits.rs2 := u.rs2
  bru.io.in.bits.bruOp := BruOp.safe(FuDecode.take(u.fu_op, BruOp.getWidth))._1
  bru.io.in.bits.tag := 0.U
  bru.io.in.bits.waddr := 0.U
  bru.io.out.ready := true.B
  bru.io.out.flush := flushing

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
  sysu.io.out.flush := flushing

  mul.foreach { m =>
    m.io.in.valid := uopPulse && isMul
    m.io.in.bits.opA := u.rs1
    m.io.in.bits.opB := u.rs2
    m.io.in.bits.mulOp := MulOp.safe(FuDecode.take(u.fu_op, MulOp.getWidth))._1
    m.io.in.bits.pc := u.pc
    m.io.in.bits.tag := 0.U
    m.io.in.bits.waddr := 0.U
    m.io.out.ready := true.B
    m.io.out.flush := flushing
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
    d.io.out.flush := flushing
  }

  private val lsuOp = LsuOp.safe(FuDecode.take(u.fu_op, LsuOp.getWidth))._1
  private val isLoad = LsuOp.isLoad(lsuOp)
  private val mem = Module(new MemUnit(width, tagWidth, waddrWidth, mmioRanges))
  io.dbus <> mem.io.dbus

  private val addr = PrefixAdder(u.rs1, u.imm)
  private val addr2 = addr(1, 0)

  private val sbStrb = Mux(
    addr2 === 0.U,
    "b0001".U(4.W),
    Mux(addr2 === 1.U, "b0010".U(4.W), Mux(addr2 === 2.U, "b0100".U, "b1000".U(4.W)))
  )

  private val shStrb = Mux(addr2(1), "b1100".U(4.W), "b0011".U(4.W))
  private val swStrb = "b1111".U(4.W)

  private val wstrb = Mux1H(
    lsuOp.asUInt,
    Seq(0.U(4.W), 0.U(4.W), 0.U(4.W), 0.U(4.W), 0.U(4.W), sbStrb, shStrb, swStrb)
  )

  private val wdata = u.rs2 << (addr2 * 8.U)

  // One bus request per uop, held until accepted; flush kills it.
  private val uopEdge = uopValid && !RegNext(uopValid, false.B)
  private val memReqValid = RegInit(false.B)

  when(flushing) { memReqValid := false.B }.otherwise {
    when(memReqValid && mem.io.mem_req_ready) { memReqValid := false.B }
      .elsewhen(uopEdge && isLsu) { memReqValid := true.B }
  }

  // Request tag (riding the tag echo): a stale response must not fire the next uop's done.
  private val lsuTag = RegInit(0.U(2.W))
  private val curLsuTag = RegInit(0.U(2.W))
  when(uopEdge && isLsu) {
    curLsuTag := lsuTag
    lsuTag := lsuTag + 1.U
  }
  private val respTagMatch = mem.io.completion.bits.tag === curLsuTag

  mem.io.mem_req.valid := memReqValid
  mem.io.mem_req.bits.addr := addr
  mem.io.mem_req.bits.wdata := wdata
  mem.io.mem_req.bits.wstrb := wstrb
  mem.io.mem_req.bits.lsuOp := lsuOp
  mem.io.mem_req.bits.tag := curLsuTag
  mem.io.mem_req.bits.waddr := 0.U
  mem.io.out.ready := true.B
  mem.io.out.flush := flushing

  private val exuDone = uopValid &&
    ((isAlu && alu.io.completion.valid) ||
      (isBru && bru.io.completion.valid) ||
      (isSysu && sysu.io.completion.valid) ||
      mul.map(m => isMul && m.io.completion.valid).getOrElse(false.B) ||
      div.map(d => isDiv && d.io.completion.valid).getOrElse(false.B))

  private val loadDone = uopValid && isLsu && isLoad && mem.io.out.valid && respTagMatch
  private val storeDone = uopValid && isLsu && !isLoad && mem.io.completion.valid && respTagMatch
  private val done = exuDone || loadDone || storeDone

  private val exuData = Mux1H(
    Seq(isAlu -> alu.io.out.bits.data, isBru -> bru.io.out.bits.data, isSysu -> sysu.io.out.bits.data) ++
      mul.map(m => isMul -> m.io.out.bits.data).toSeq ++
      div.map(d => isDiv -> d.io.out.bits.data).toSeq
  )

  // Edge, not level: ALU/SYSU hold their completion for as long as the uop is held, and doneReg must pulse once.
  private val doneEdge = done && !RegNext(done, false.B)
  val resultReg = Reg(new MinExecResult(width))
  when(doneEdge) {
    resultReg.data := Mux(isLsu, Mux(isLoad, mem.io.out.bits.data, 0.U(32.W)), exuData)
    resultReg.next_pc := Mux(isBru, bru.io.completion.bits.next_pc, u.pc + 4.U)
    resultReg.csr_type := Mux(isSysu, sysu.io.completion.bits.csr_type, CsrType.None)
    resultReg.csr_data := Mux(isSysu, sysu.io.completion.bits.csr_data, 0.U(32.W))
    resultReg.is_mmio := Mux(isLsu, mem.io.completion.bits.is_mmio, false.B)
  }
  val doneReg = RegNext(doneEdge, false.B)

  io.bus.report.done := doneReg
  io.bus.report.data := resultReg.data
  io.bus.report.next_pc := resultReg.next_pc
  io.bus.report.csr_type := resultReg.csr_type
  io.bus.report.csr_data := resultReg.csr_data
  io.bus.report.is_mmio := resultReg.is_mmio
  io.bus.report.flush := flushing
  io.bus.report.redirect_target := bru.io.flush_target
}