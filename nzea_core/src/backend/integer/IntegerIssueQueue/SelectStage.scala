package nzea_core.backend.integer

import chisel3._
import chisel3.util.{Mux1H, MuxLookup, PopCount, PriorityEncoder, Valid}
import nzea_core.frontend.{CsrType, FuType, PrfBypass, PrfRawRead, PrfWriteBundle}
import nzea_core.retire.rob.RobMemType
import nzea_config.{CoreConfig, FuConfig, FuKind}
import nzea_rtl.PipeIO

/** Stage 1: select one ready slot and push to pipeline register.
  * Uses downstream pipe-register readiness (not FU internal readiness) for canIssue.
  */
class IntegerIssueQueueSelectStage(
  robIdWidth: Int,
  prfAddrWidth: Int,
  lsqIdWidth: Int,
  depth: Int
)(
  implicit config: CoreConfig
) extends Module {
  private val issuePortConfigs = FuConfig.issuePorts(config)
  private val portIdxByKind = issuePortConfigs.zipWithIndex.map { case (cfg, idx) => cfg.kind -> idx }.toMap
  require(
    portIdxByKind.size == issuePortConfigs.size,
    s"Duplicate FuKind in issue port config: ${issuePortConfigs.map(_.kind)}"
  )

  private def portIdx(kind: FuKind): Int =
    portIdxByKind.getOrElse(kind, throw new IllegalArgumentException(s"Missing issue port for FuKind.$kind"))
  private def portIdxOpt(kind: FuKind): Option[Int] = portIdxByKind.get(kind)

  private val numPrfWritePorts = FuConfig.numPrfWritePorts
  private val numWakeupHints   = FuConfig.numWakeupHints

  /** Port index for SYSU. CSR-write pending stalls only SYSU so older ALU/AGU/BRU/MUL/DIV can still issue (avoids deadlock). */
  private val sysuPortIdx = portIdx(FuKind.Sysu).U
  private def fuKindForType(ft: FuType.Type): FuKind = ft match {
    case FuType.ALU  => FuKind.Alu
    case FuType.BRU  => FuKind.Bru
    case FuType.LSU  => FuKind.Agu
    case FuType.MUL  => FuKind.Mul
    case FuType.DIV  => FuKind.Div
    case FuType.SYSU => FuKind.Sysu
    case FuType.NNU  => FuKind.Nnu
  }
  private val fuTypeToPortIdx: Seq[(UInt, UInt)] = FuType.all.map { ft =>
    val idx = portIdxOpt(fuKindForType(ft)).getOrElse(0)
    (ft.asUInt, idx.U)
  }

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new IntegerIssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth)))
    val prf_write     = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    val bypass_level1 = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    /** Clear SYSU CSR-write pending when matching rob commits. */
    val commit_rob_id = Input(UInt(robIdWidth.W))
    val commit_valid  = Input(Bool())
    /** Read-stage early wakeup hints from fixed-latency FU pipelines (valid + p_rd only). */
    val wakeup_hints = Input(Vec(numWakeupHints, Valid(UInt(prfAddrWidth.W))))
    /** Raw PRF read at enqueue p_rs1 / p_rs2 (same addrs as [[in]]); used with bypass for slot ready. */
    val prf_enqueue_rs1 = Input(new PrfRawRead(prfAddrWidth))
    val prf_enqueue_rs2 = Input(new PrfRawRead(prfAddrWidth))
    /** One output per FU; ready/flush come from PipelineConnect (pipe reg) and consumer. */
    val out = Vec(FuConfig.numIssuePorts, new PipeIO(new IntegerIssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth)))
  })

  private val flush = io.out(0).flush
  val entries = Reg(Vec(depth, new IntegerIssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth)))
  val valids  = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val count   = PopCount(valids.asUInt)
  val full    = count >= depth.U
  val bypassPorts = FuConfig.prfWritePorts(config).zipWithIndex.filter(_._1.hasBypass)

  val firstInvalid = PriorityEncoder(VecInit((0 until depth).map(i => !valids(i))).asUInt)
  val pending_csr_write_rob_id = RegInit(0.U(robIdWidth.W))
  val pending_csr_write_valid  = RegInit(false.B)
  val enqFire = !flush && io.in.fire

  io.in.ready := !full && !flush
  val csr_pending_issue_stall = pending_csr_write_valid
  private def hasWakeupHitFor(paddr: UInt): Bool =
    io.wakeup_hints.map(h => h.valid && h.bits === paddr && paddr =/= 0.U).foldLeft(false.B)(_ || _)

  for (i <- 0 until depth) {
    val entryValid = valids(i) || (enqFire && firstInvalid === i.U)
    for (j <- 0 until numPrfWritePorts) {
      val waddr = Mux(io.bypass_level1(j).valid, io.bypass_level1(j).bits.addr, io.prf_write(j).bits.addr)
      val wvalid = io.bypass_level1(j).valid || io.prf_write(j).valid
      when(!flush && wvalid && entryValid) {
        val p_rs1 = Mux(enqFire && firstInvalid === i.U, io.in.bits.p_rs1, entries(i).p_rs1)
        val p_rs2 = Mux(enqFire && firstInvalid === i.U, io.in.bits.p_rs2, entries(i).p_rs2)
        when(p_rs1 === waddr && p_rs1 =/= 0.U) { entries(i).rs1_ready := true.B }
        when(p_rs2 === waddr && p_rs2 =/= 0.U) { entries(i).rs2_ready := true.B }
      }
    }
    when(!flush && entryValid) {
      val p_rs1 = Mux(enqFire && firstInvalid === i.U, io.in.bits.p_rs1, entries(i).p_rs1)
      val p_rs2 = Mux(enqFire && firstInvalid === i.U, io.in.bits.p_rs2, entries(i).p_rs2)
      when(hasWakeupHitFor(p_rs1)) { entries(i).rs1_ready := true.B }
      when(hasWakeupHitFor(p_rs2)) { entries(i).rs2_ready := true.B }
    }
  }

  when(flush) {
    for (i <- 0 until depth) { valids(i) := false.B }
  }

  // fuReady = pipeline reg input ready (set by PipelineConnect in parent)
  val fuReady = VecInit(io.out.map(_.ready))
  val canIssue = Wire(Vec(depth, Bool()))
  for (i <- 0 until depth) {
    val entry = entries(i)
    val rs1BypassHit = hasWakeupHitFor(entry.p_rs1) ||
      bypassPorts.map { case (_, j) =>
        (io.bypass_level1(j).valid && io.bypass_level1(j).bits.addr === entry.p_rs1) ||
        (io.prf_write(j).valid && io.prf_write(j).bits.addr === entry.p_rs1)
      }.foldLeft(false.B)(_ || _)
    val rs2BypassHit = hasWakeupHitFor(entry.p_rs2) ||
      bypassPorts.map { case (_, j) =>
        (io.bypass_level1(j).valid && io.bypass_level1(j).bits.addr === entry.p_rs2) ||
        (io.prf_write(j).valid && io.prf_write(j).bits.addr === entry.p_rs2)
      }.foldLeft(false.B)(_ || _)
    val actual_rs1_ready = (entry.p_rs1 === 0.U) || entry.rs1_ready || rs1BypassHit
    val actual_rs2_ready = (entry.p_rs2 === 0.U) || entry.rs2_ready || rs2BypassHit
    val portIdx = MuxLookup(entry.fu_type.asUInt, 0.U)(fuTypeToPortIdx)
    canIssue(i) := valids(i) && actual_rs1_ready && actual_rs2_ready && fuReady(portIdx) &&
      !(csr_pending_issue_stall && portIdx === sysuPortIdx)
  }
  val firstReadyIdx = PriorityEncoder(canIssue.asUInt)
  val anyCanIssue = canIssue.asUInt.orR
  val selOneHot = VecInit((0 until depth).map(i => (i.U === firstReadyIdx)))

  val rawEntryFor = entries
  val (selFuType, _) = FuType.safe(Mux1H(selOneHot, rawEntryFor.map(_.fu_type.asUInt)))
  val portIdxForSel = MuxLookup(selFuType.asUInt, 0.U)(fuTypeToPortIdx)

  val e = Wire(new IntegerIssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth))
  val (eFuType, _)  = FuType.safe(Mux1H(selOneHot, rawEntryFor.map(_.fu_type.asUInt)))
  val (eMemType, _) = RobMemType.safe(Mux1H(selOneHot, rawEntryFor.map(_.mem_type.asUInt)))
  e.fu_type   := eFuType
  e.mem_type  := eMemType
  e.rs1_ready := true.B
  e.rs2_ready := true.B
  e.p_rs1     := Mux1H(selOneHot, rawEntryFor.map(_.p_rs1))
  e.p_rs2     := Mux1H(selOneHot, rawEntryFor.map(_.p_rs2))
  e.imm       := Mux1H(selOneHot, rawEntryFor.map(_.imm))
  e.pc        := Mux1H(selOneHot, rawEntryFor.map(_.pc))
  e.pred_next_pc   := Mux1H(selOneHot, rawEntryFor.map(_.pred_next_pc))
  e.fu_op     := Mux1H(selOneHot, rawEntryFor.map(_.fu_op))
  e.fu_src    := Mux1H(selOneHot, rawEntryFor.map(_.fu_src))
  e.csr_addr  := Mux1H(selOneHot, rawEntryFor.map(_.csr_addr))
  e.csr_will_write := Mux1H(selOneHot, rawEntryFor.map(_.csr_will_write))
  e.rob_id    := Mux1H(selOneHot, rawEntryFor.map(_.rob_id))
  e.p_rd      := Mux1H(selOneHot, rawEntryFor.map(_.p_rd))
  e.old_p_rd  := Mux1H(selOneHot, rawEntryFor.map(_.old_p_rd))
  e.rd_index  := Mux1H(selOneHot, rawEntryFor.map(_.rd_index))
  e.lsq_id    := Mux1H(selOneHot, rawEntryFor.map(_.lsq_id))

  for (i <- 0 until FuConfig.numIssuePorts) {
    io.out(i).valid := anyCanIssue && (portIdxForSel === i.U)
    io.out(i).bits := e
    // flush driven by PipelineConnect from consumer
  }

  val issueFire = anyCanIssue && io.out(portIdxForSel).ready
  val deqFire = !flush && issueFire
  val csr_type_deq = Mux(e.fu_type === FuType.SYSU, CsrType.fromAddr(e.csr_addr), CsrType.None)
  val will_csr_write_deq = csr_type_deq =/= CsrType.None && e.csr_will_write
  when(flush) {
    pending_csr_write_valid := false.B
  }.elsewhen(io.commit_valid && pending_csr_write_valid && io.commit_rob_id === pending_csr_write_rob_id) {
    pending_csr_write_valid := false.B
  }.elsewhen(deqFire && e.fu_type === FuType.SYSU && will_csr_write_deq) {
    pending_csr_write_valid := true.B
    pending_csr_write_rob_id := e.rob_id
  }

  when(deqFire) { valids(firstReadyIdx) := false.B }
  when(enqFire) {
    val enq = io.in.bits
    entries(firstInvalid) := enq
    val (_, rs1Merged) = PrfBypass.mergeOperand(
      enq.p_rs1,
      io.prf_enqueue_rs1.data,
      io.prf_enqueue_rs1.ready,
      io.bypass_level1,
      io.prf_write
    )
    val (_, rs2Merged) = PrfBypass.mergeOperand(
      enq.p_rs2,
      io.prf_enqueue_rs2.data,
      io.prf_enqueue_rs2.ready,
      io.bypass_level1,
      io.prf_write
    )
    entries(firstInvalid).rs1_ready := rs1Merged
    entries(firstInvalid).rs2_ready := rs2Merged
    valids(firstInvalid) := true.B
  }

  io.in.flush := flush
}
