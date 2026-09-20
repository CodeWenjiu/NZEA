package nzea_core.min.back

import chisel3._
import chisel3.util.{Mux1H, MuxLookup, Valid}
import nzea_core.min.{BackBusPort, CoreChannels}
import nzea_rtl.{LiteBusRW, PrefixAdder}
import nzea_core.backend.MemUnit
import nzea_core.backend.integer._
import nzea_core.backend.integer.nnu.{NNU, NnOp}
import nzea_core.frontend.{AluSrc, CsrType, FuDecode, FuSrcWidth, FuType}
import nzea_config.core.{CoreConfig, PayloadSpec}
import nzea_core.PayloadFields

/** Completion report from the backend to the in-order middle — the single reverse channel (back → middle).
  *
  * `done` marks the cycle the uop finishes (data/next_pc/CSR fields valid that cycle); `flush` marks a branch
  * mispredict, with `redirect_target` carrying the resolved target. Both may be set in the same cycle for a
  * mispredicted branch: the branch itself is still valid and must commit, while everything fetched after it is
  * squashed.
  *
  * Field policy: every field comes from [[nzea_core.PayloadFields]] — the mandatory commit/redirect information is the
  * baseline report set (exists by default; a future microarchitecture drops a piece by gating that factory), and
  * configuration-dependent report units are added on demand through the same factories. Absent fields are structural
  * (no port, no registers).
  */
class MinExecOut(width: Int) extends Bundle {
  val done = PayloadFields.reportDone
  val data = PayloadFields.reportData
  val next_pc = PayloadFields.reportNextPc(width)
  val csr_type = PayloadFields.reportCsrType
  val csr_data = PayloadFields.reportCsrData
  val is_mmio = PayloadFields.reportIsMmio
  val flush = PayloadFields.reportFlush
  val redirect_target = PayloadFields.reportRedirectTarget(width)
}

/** Execution-stage exit: the result latch between the execute stage and the commit point. */
class MinExecResult(width: Int) extends Bundle {
  val data = UInt(32.W)
  val next_pc = UInt(width.W)
  val csr_type = CsrType()
  val csr_data = UInt(32.W)
  val is_mmio = Bool()
}

/** Ready-operand uop handed from the in-order middle ([[MinMiddle]]) to the backend ([[MinExec]]).
  *
  * All operands are architectural values resolved by the middle: rs1/rs2 come from the ARF (x0 reads as 0) and
  * csr_rdata from the CSR read port, so the backend never sees register indexes or the CSR file. The middle holds the
  * uop stable from issue until the backend reports done or flush (single-flight).
  */
class MinIssueUop(width: Int)(implicit config: CoreConfig) extends Bundle {
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

/** Sequential execute stage of the baseline core: own dispatch, in-order single issue.
  *
  * The uop's fu_type selects exactly one FU per cycle (ALU / BRU / SYSU / MUL / DIV / NNU / LSU) — no cluster ports, no
  * arbitration, no ID state (rob_id/p_rd are constant zero: in-order needs no renaming). FUs are the shared
  * implementations driven directly instead of through [[nzea_core.backend.integer.IntegerExecutionCluster]].
  *
  * Completion: a FU reports done via its rob_access.valid (completion, not write-back valid — a DIV/REM targeting x0
  * still completes) and the LSU completes at the bus response. Every done edge is gated by uopValid, so the pipeline
  * drain of a multi-cycle FU (BRU/MUL 3-stage, DIV/NNU FSM) after the middle clears the uop cannot re-fire done.
  *
  * Memory: the AGU work is a combinational local (addr/wstrb/wdata) and the bus request is injected once on the uop
  * edge (memReqValid) then held until the bus accepts — a level-driven mem_req would re-fire the same request every
  * cycle the middle holds the uop.
  */
class MinExec(mmioRanges: Seq[(BigInt, BigInt)] = Seq.empty)(implicit config: CoreConfig) extends Module {
  private val width = config.width
  private val robIdWidth = chisel3.util.log2Ceil(config.robDepth.max(2))
  private val prfAddrWidth = config.prfAddrWidth
  private val hasM = config.isaConfig.hasM
  private val hasNn = config.isaConfig.hasWjcus0

  val io = IO(new Bundle {
    val bus = new BackBusPort(width, robIdWidth, prfAddrWidth)
    val dbus = CoreChannels.dbus(width, robIdWidth, prfAddrWidth)
  })

  private val uopValid = io.bus.issue.valid
  private val u = io.bus.issue.bits

  // -------- FU instances (config-gated, shared implementations) --------

  private val alu = Module(new ALU(robIdWidth, prfAddrWidth))
  private val bru = Module(new BRU(robIdWidth, prfAddrWidth))
  private val sysu = Module(new SYSU(robIdWidth, prfAddrWidth))
  private val mul = Option.when(hasM)(Module(new MUL(robIdWidth, prfAddrWidth)))
  private val div = Option.when(hasM)(Module(new DIV(robIdWidth, prfAddrWidth)))
  private val nnu = Option.when(hasNn)(Module(new NNU(robIdWidth, prfAddrWidth)))

  // -------- Dispatch: exactly one FU sees the uop, selected by fu_type --------

  private val isAlu = u.fu_type === FuType.ALU
  private val isBru = u.fu_type === FuType.BRU
  private val isLsu = u.fu_type === FuType.LSU
  private val isMul = u.fu_type === FuType.MUL
  private val isDiv = u.fu_type === FuType.DIV
  private val isSysu = u.fu_type === FuType.SYSU
  private val isNnu = u.fu_type === FuType.NNU

  // Single-flight: the middle holds the uop stable until done/flush. Write-backs are always ready; the branch flush
  // is broadcast to every FU — with single-flight it only fires while the branch itself is the uop, so nothing else
  // is in flight.
  private val flushing = uopValid && isBru && bru.io.rob_access.valid && bru.io.rob_access.bits.flush

  alu.io.in.valid := uopValid && isAlu
  // ALU operand selection (fu_src): opA/opB by source kind, same mapping as IssueAdapters.Alu.
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
  alu.io.in.bits.rob_id := 0.U // in-order: no ROB / PRF IDs
  alu.io.in.bits.p_rd := 0.U
  alu.io.out.ready := true.B
  alu.io.out.flush := flushing

  bru.io.in.valid := uopValid && isBru
  bru.io.in.bits.pc := u.pc
  // Always-not-taken prediction: the sequential target is known without a prediction chain.
  bru.io.in.bits.pred_next_pc := u.pc + 4.U
  bru.io.in.bits.offset := u.imm
  bru.io.in.bits.rs1 := u.rs1
  bru.io.in.bits.rs2 := u.rs2
  bru.io.in.bits.bruOp := BruOp.safe(FuDecode.take(u.fu_op, BruOp.getWidth))._1
  bru.io.in.bits.rob_id := 0.U
  bru.io.in.bits.p_rd := 0.U
  // No RAS classification on this engine: the BRU's call/ret side fields stay inert.
  bru.io.in.bits.rd_index.foreach(_ := 0.U(5.W))
  bru.io.in.bits.is_ret.foreach(_ := false.B)
  bru.io.out.ready := true.B
  bru.io.out.flush := flushing

  sysu.io.in.valid := uopValid && isSysu
  sysu.io.in.bits.rob_id := 0.U
  sysu.io.in.bits.pc := u.pc
  sysu.io.in.bits.p_rd := 0.U
  sysu.io.in.bits.csr_type := u.csr_type
  sysu.io.in.bits.csr_rdata := u.csr_rdata
  sysu.io.in.bits.rs1_val := u.rs1
  sysu.io.in.bits.sysuOp := SysuOp.safe(FuDecode.take(u.fu_op, SysuOp.getWidth))._1
  sysu.io.in.bits.imm := u.imm
  sysu.io.out.ready := true.B
  sysu.io.out.flush := flushing

  mul.foreach { m =>
    m.io.in.valid := uopValid && isMul
    m.io.in.bits.opA := u.rs1
    m.io.in.bits.opB := u.rs2
    m.io.in.bits.mulOp := MulOp.safe(FuDecode.take(u.fu_op, MulOp.getWidth))._1
    m.io.in.bits.pc := u.pc
    m.io.in.bits.rob_id := 0.U
    m.io.in.bits.p_rd := 0.U
    m.io.out.ready := true.B
    m.io.out.flush := flushing
  }

  div.foreach { d =>
    d.io.in.valid := uopValid && isDiv
    d.io.in.bits.opA := u.rs1
    d.io.in.bits.opB := u.rs2
    d.io.in.bits.divOp := DivOp.safe(FuDecode.take(u.fu_op, DivOp.getWidth))._1
    d.io.in.bits.pc := u.pc
    d.io.in.bits.rob_id := 0.U
    d.io.in.bits.p_rd := 0.U
    d.io.out.ready := true.B
    d.io.out.flush := flushing
  }

  nnu.foreach { n =>
    n.io.in.valid := uopValid && isNnu
    n.io.in.bits.nnOp := NnOp.safe(FuDecode.take(u.fu_op, NnOp.getWidth))._1
    n.io.in.bits.rs1 := u.rs1
    n.io.in.bits.rs2 := u.rs2
    n.io.in.bits.pc := u.pc
    n.io.in.bits.rob_id := 0.U
    n.io.in.bits.p_rd := 0.U
    n.io.out.ready := true.B
    n.io.out.flush := flushing
  }

  // -------- LSU: combinational AGU + once-per-uop bus request --------

  private val lsuOp = LsuOp.safe(FuDecode.take(u.fu_op, LsuOp.getWidth))._1
  private val isLoad = LsuOp.isLoad(lsuOp)
  private val mem = Module(new MemUnit(width, robIdWidth, prfAddrWidth, mmioRanges))
  io.dbus <> mem.io.dbus

  // Combinational address generation (same math as the shared AGU; the AGU's ROB/LS_Queue side is OoO-only).
  private val addr = PrefixAdder(u.rs1, u.imm)
  private val addr2 = addr(1, 0)

  private val sbStrb = Mux(
    addr2 === 0.U,
    "b0001".U(4.W),
    Mux(addr2 === 1.U, "b0010".U(4.W), Mux(addr2 === 2.U, "b0100".U(4.W), "b1000".U(4.W)))
  )

  private val shStrb = Mux(addr2(1), "b1100".U(4.W), "b0011".U(4.W))
  private val swStrb = "b1111".U(4.W)

  private val wstrb = Mux1H(
    lsuOp.asUInt,
    Seq(0.U(4.W), 0.U(4.W), 0.U(4.W), 0.U(4.W), 0.U(4.W), sbStrb, shStrb, swStrb)
  )

  private val wdata = u.rs2 << (addr2 * 8.U)

  // Inject the request once on the uop edge; hold it until the bus accepts (mem_req_ready). flush kills it.
  private val uopEdge = uopValid && !RegNext(uopValid, false.B)
  private val memReqValid = RegInit(false.B)

  when(flushing) { memReqValid := false.B }.otherwise {
    when(memReqValid && mem.io.mem_req_ready) { memReqValid := false.B }
      .elsewhen(uopEdge && isLsu) { memReqValid := true.B }
  }

  mem.io.mem_req.valid := memReqValid
  mem.io.mem_req.bits.addr := addr
  mem.io.mem_req.bits.wdata := wdata
  mem.io.mem_req.bits.wstrb := wstrb
  mem.io.mem_req.bits.lsuOp := lsuOp
  mem.io.mem_req.bits.rob_id := 0.U
  mem.io.mem_req.bits.p_rd := 0.U
  mem.io.out.ready := true.B
  mem.io.out.flush := flushing

  // -------- Completion (single-flight: at most one FU active per cycle) --------

  // uopValid gates every term: exBits keeps the last instruction's type while the middle idles, and a multi-cycle FU's
  // pipeline still drains rob_access.valid for a few cycles after the uop leaves — without the gate that would re-fire
  // done and commit the instruction twice.
  private val exuDone = uopValid &&
    ((isAlu && alu.io.rob_access.valid) ||
      (isBru && bru.io.rob_access.valid) ||
      (isSysu && sysu.io.rob_access.valid) ||
      mul.map(m => isMul && m.io.rob_access.valid).getOrElse(false.B) ||
      div.map(d => isDiv && d.io.rob_access.valid).getOrElse(false.B) ||
      nnu.map(n => isNnu && n.io.rob_access.valid).getOrElse(false.B))

  private val loadDone = uopValid && isLsu && isLoad && mem.io.out.valid
  private val storeDone = uopValid && isLsu && !isLoad && mem.io.rob_access.valid
  private val done = exuDone || loadDone || storeDone

  // -------- Result assembly (combinational, valid on the done edge) --------

  private val exuData = Mux1H(
    Seq(isAlu -> alu.io.out.bits.data, isBru -> bru.io.out.bits.data, isSysu -> sysu.io.out.bits.data) ++
      mul.map(m => isMul -> m.io.out.bits.data).toSeq ++
      div.map(d => isDiv -> d.io.out.bits.data).toSeq ++
      nnu.map(n => isNnu -> n.io.out.bits.data).toSeq
  )

  // -------- Execution-stage exit: latch the result once per uop, report it one cycle later --------

  // The execute stage is a complete Q -> combinational -> Q segment: the uop (exBits) enters from registers and the
  // result is latched here on the done edge, so the completion/commit data path never sees combinational ALU output
  // (this is the EX/WB boundary of the baseline). report.flush/redirect_target stay combinational: redirect happens
  // on the same cycle the branch resolves, exactly as before, while done/data/next_pc/CSR wait for the latch.
  // done is a level, not a pulse: the combinational FUs (ALU/SYSU) report completion for as long as the uop is held.
  // The exit latch consumes the completion *edge* so one uop commits exactly once (a multi-cycle FU like the BRU
  // asserts rob_access.valid for a single cycle anyway — the edge unifies both shapes).
  private val doneEdge = done && !RegNext(done, false.B)
  val resultReg = Reg(new MinExecResult(width))
  when(doneEdge) {
    resultReg.data := Mux(isLsu, Mux(isLoad, mem.io.out.bits.data, 0.U(32.W)), exuData)
    resultReg.next_pc := Mux(isBru, bru.io.rob_access.bits.next_pc, u.pc + 4.U)
    resultReg.csr_type := Mux(isSysu, sysu.io.rob_access.bits.csr_type, CsrType.None)
    resultReg.csr_data := Mux(isSysu, sysu.io.rob_access.bits.csr_data, 0.U(32.W))
    resultReg.is_mmio := Mux(isLsu, mem.io.rob_access.bits.is_mmio, false.B)
  }
  val doneReg = RegNext(doneEdge, false.B)

  io.bus.report.done := doneReg
  io.bus.report.data := resultReg.data
  io.bus.report.next_pc := resultReg.next_pc
  io.bus.report.csr_type := resultReg.csr_type
  io.bus.report.csr_data := resultReg.csr_data
  io.bus.report.is_mmio := resultReg.is_mmio
  io.bus.report.flush := flushing
  io.bus.report.redirect_target := bru.io.bp_update.bits.target
}
