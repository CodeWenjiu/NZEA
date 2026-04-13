package nzea_core.backend.integer

import chisel3._
import chisel3.util.{Mux1H, MuxLookup, PopCount, PriorityEncoder, Valid}
import nzea_rtl.{PipeIO, PipelineConnect}
import nzea_core.frontend.{CsrType, FuSrcWidth, FuType, IssuePortsBundle, PrfBypass, PrfRawRead, PrfReadIO, PrfWriteBundle}
import nzea_core.retire.rob.RobMemType
import nzea_config.{CoreConfig, FuConfig, FuKind}

/** Integer issue queue entry: FuType + operand tags (paddr) + ready. No source data; values read via bypass net at dispatch. */
class IntegerIssueQueueEntry(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int) extends Bundle {
  val fu_type        = FuType()
  val rs1_ready      = Bool()
  val rs2_ready      = Bool()
  val p_rs1          = UInt(prfAddrWidth.W)
  val p_rs2          = UInt(prfAddrWidth.W)
  val p_rd           = UInt(prfAddrWidth.W)
  val old_p_rd       = UInt(prfAddrWidth.W)
  val rd_index       = UInt(5.W)
  val imm            = UInt(32.W)
  val pc             = UInt(32.W)
  val pred_next_pc   = UInt(32.W)
  val fu_op          = UInt(FuOpWidth.Width.W)
  val fu_src         = UInt(FuSrcWidth.Width.W)
  val csr_addr       = UInt(12.W)
  /** Decode-time: SYSU will write CSR (rs1/zimm non-zero for CSRRS/CSRRC/… ). */
  val csr_will_write = Bool()
  val rob_id         = UInt(robIdWidth.W)
  val lsq_id         = UInt(lsqIdWidth.W)
  val mem_type       = RobMemType()
}

/** Stage 1: Select slot, push to pipeline reg. Uses pipeline reg ready (not Fu ready) for canIssue. */
class IntegerIssueQueueSelectStage(
  robIdWidth: Int,
  prfAddrWidth: Int,
  lsqIdWidth: Int,
  depth: Int
)(
  implicit config: CoreConfig
) extends Module {
  private val issuePortConfigs = FuConfig.issuePorts(config)
  private val numPrfWritePorts = FuConfig.numPrfWritePorts
  private val numWakeupHints   = FuConfig.numWakeupHints

  /** Port index for SYSU. CSR-write pending stalls only SYSU so older ALU/AGU/BRU/MUL/DIV can still issue (avoids deadlock). */
  private val sysuPortIdx = (FuConfig.numIssuePorts - 1).U
  private def fuKindForType(ft: FuType.Type): FuKind = ft match {
    case FuType.ALU  => FuKind.Alu
    case FuType.BRU  => FuKind.Bru
    case FuType.LSU  => FuKind.Agu
    case FuType.MUL  => FuKind.Mul
    case FuType.DIV  => FuKind.Div
    case FuType.SYSU => FuKind.Sysu
    case FuType.NNU  => FuKind.Nnu
  }
  private val fuTypeToPortIdx: Seq[(UInt, UInt)] = FuType.all.zipWithIndex.map { case (ft, i) =>
    val idx = issuePortConfigs.indexWhere(_.kind == fuKindForType(ft))
    (ft.asUInt, (if (idx >= 0) idx else 0).U)
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

/** Stage 2: Per-port independent. If pipe valid, read PRF+bypass and drive FU. No arbitration.
  * Extracts flush from issuePorts (consumer-driven) and propagates to in.flush. */
class IntegerIssueQueueReadStage(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int)(implicit config: CoreConfig) extends Module {
  private val hasM  = config.isaConfig.hasM
  private val hasNn = config.isaConfig.hasWjcus0
  private val numPorts = FuConfig.numIssuePorts
  private val issuePortConfigs = FuConfig.issuePorts(config)
  private val wakeupHintSpecs: Seq[(Int, Int)] = issuePortConfigs.zipWithIndex.flatMap { case (cfg, idx) =>
    cfg.wakeupHintLatency.map(lat => (idx, lat))
  }
  private val numWakeupHints = wakeupHintSpecs.size

  private val aluIdx = 0
  private val bruIdx = 1
  private val aguIdx = 2
  private val mulIdxOpt = Option.when(hasM)(3)
  private val divIdxOpt = Option.when(hasM)(4)
  private val baseAfterMd = if (hasM) 5 else 3
  private val nnuIdxOpt = Option.when(hasNn)(baseAfterMd)
  private val sysuIdx = baseAfterMd + (if (hasNn) 1 else 0)

  val io = IO(new Bundle {
    val in = Flipped(Vec(numPorts, new PipeIO(new IntegerIssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth))))
    val prf_read = Vec(numPorts, Vec(2, new PrfReadIO(prfAddrWidth)))
    /** Combinational CSR read for SYSU port (addr from [[csr_read_addr]]). */
    val csr_rdata = Input(UInt(32.W))
    /** CSR address for [[csr_rdata]] read; 0 when SYSU port not issuing. */
    val csr_read_addr = Output(UInt(12.W))
    val issuePorts = new IssuePortsBundle(robIdWidth, prfAddrWidth, lsqIdWidth)
    /** Early wakeup hints (valid + p_rd) generated from fixed-latency FU issue points. */
    val wakeup_hints = Output(Vec(numWakeupHints, Valid(UInt(prfAddrWidth.W))))
  })

  private val flush = io.issuePorts.orderedPorts(0).flush
  for (i <- 0 until numPorts) {
    io.in(i).flush := flush
    io.in(i).ready := io.issuePorts.orderedPorts(i).ready
  }

  private def entry(i: Int) = io.in(i).bits
  private def rs1(i: Int) = Mux(entry(i).p_rs1 === 0.U, 0.U(32.W), io.prf_read(i)(0).data)
  private def rs2(i: Int) = Mux(entry(i).p_rs2 === 0.U, 0.U(32.W), io.prf_read(i)(1).data)
  for (i <- 0 until numPorts) {
    io.prf_read(i)(0).addr := entry(i).p_rs1
    io.prf_read(i)(1).addr := entry(i).p_rs2
  }

  private def driveWakeupHint(dst: Valid[UInt], portIdx: Int, latency: Int): Unit = {
    require(latency >= 0, s"wakeupHintLatency must be >= 0, got $latency for port $portIdx")
    if (latency == 0) {
      dst.valid := io.in(portIdx).valid
      dst.bits  := entry(portIdx).p_rd
    } else {
      val validPipe = RegInit(VecInit(Seq.fill(latency)(false.B)))
      val pRdPipe   = Reg(Vec(latency, UInt(prfAddrWidth.W)))
      when(flush) {
        for (k <- 0 until latency) {
          validPipe(k) := false.B
        }
      }.otherwise {
        validPipe(0) := io.in(portIdx).valid
        pRdPipe(0)   := entry(portIdx).p_rd
        for (k <- 1 until latency) {
          validPipe(k) := validPipe(k - 1)
          pRdPipe(k)   := pRdPipe(k - 1)
        }
      }
      dst.valid := validPipe(latency - 1)
      dst.bits  := pRdPipe(latency - 1)
    }
  }

  private def dispatchToFuPorts(): Unit = {
    IssueAdapters.Alu.drive(
      valid = io.in(aluIdx).valid,
      entry = entry(aluIdx),
      rs1 = rs1(aluIdx),
      rs2 = rs2(aluIdx),
      out = io.issuePorts.alu
    )
    IssueAdapters.Bru.drive(
      valid = io.in(bruIdx).valid,
      entry = entry(bruIdx),
      rs1 = rs1(bruIdx),
      rs2 = rs2(bruIdx),
      out = io.issuePorts.bru
    )
    IssueAdapters.Agu.drive(
      valid = io.in(aguIdx).valid,
      entry = entry(aguIdx),
      rs1 = rs1(aguIdx),
      rs2 = rs2(aguIdx),
      out = io.issuePorts.agu
    )
    mulIdxOpt.foreach { i =>
      IssueAdapters.Mul.drive(
        valid = io.in(i).valid,
        entry = entry(i),
        rs1 = rs1(i),
        rs2 = rs2(i),
        out = io.issuePorts.mul.get
      )
    }
    divIdxOpt.foreach { i =>
      IssueAdapters.Div.drive(
        valid = io.in(i).valid,
        entry = entry(i),
        rs1 = rs1(i),
        rs2 = rs2(i),
        out = io.issuePorts.div.get
      )
    }
    nnuIdxOpt.foreach { i =>
      IssueAdapters.Nnu.drive(
        valid = io.in(i).valid,
        entry = entry(i),
        rs1 = rs1(i),
        rs2 = rs2(i),
        out = io.issuePorts.nnu.get
      )
    }
    IssueAdapters.Sysu.drive(
      valid = io.in(sysuIdx).valid,
      entry = entry(sysuIdx),
      rs1 = rs1(sysuIdx),
      csrRdata = io.csr_rdata,
      out = io.issuePorts.sysu
    )
  }

  io.csr_read_addr := Mux(
    io.in(sysuIdx).valid && entry(sysuIdx).fu_type === FuType.SYSU,
    entry(sysuIdx).csr_addr,
    0.U(12.W)
  )

  dispatchToFuPorts()
  for (hintIdx <- wakeupHintSpecs.indices) {
    val (portIdx, latency) = wakeupHintSpecs(hintIdx)
    driveWakeupHint(io.wakeup_hints(hintIdx), portIdx, latency)
  }
}

/** Integer issue queue: 2-stage pipeline. S1 selects slot; S2 reads PRF+bypass and dispatches to FUs.
  * Flush: S2 extracts from issuePorts (consumer); S1 gets it via PipelineConnect from S2 input. */
class IntegerIssueQueue(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int, depth: Int)(
  implicit config: CoreConfig
) extends Module {
  private val numPrfWritePorts = FuConfig.numPrfWritePorts
  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new IntegerIssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth)))
    val prf_write     = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    val bypass_level1 = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    val commit_rob_id   = Input(UInt(robIdWidth.W))
    val commit_valid    = Input(Bool())
    val issuePorts    = new IssuePortsBundle(robIdWidth, prfAddrWidth, lsqIdWidth)
    val prf_read      = Vec(FuConfig.numIssuePorts, Vec(2, new PrfReadIO(prfAddrWidth)))
    val csr_rdata     = Input(UInt(32.W))
    val csr_read_addr = Output(UInt(12.W))
    /** Enqueue PRF read (rs1/rs2); same ports as [[in]].bits p_rs*. */
    val prf_enqueue_rs1 = Input(new PrfRawRead(prfAddrWidth))
    val prf_enqueue_rs2 = Input(new PrfRawRead(prfAddrWidth))
  })

  val s0 = Module(
    new IntegerIssueQueueSelectStage(
      robIdWidth,
      prfAddrWidth,
      lsqIdWidth,
      depth
    )
  )
  val s1 = Module(new IntegerIssueQueueReadStage(robIdWidth, prfAddrWidth, lsqIdWidth))

  s0.io.in <> io.in
  s0.io.prf_write := io.prf_write
  s0.io.bypass_level1 := io.bypass_level1
  s0.io.commit_rob_id := io.commit_rob_id
  s0.io.commit_valid  := io.commit_valid
  s0.io.wakeup_hints := s1.io.wakeup_hints
  s0.io.prf_enqueue_rs1 := io.prf_enqueue_rs1
  s0.io.prf_enqueue_rs2 := io.prf_enqueue_rs2

  val pipeRegOut = Wire(Vec(FuConfig.numIssuePorts, new PipeIO(new IntegerIssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth))))
  for (i <- 0 until FuConfig.numIssuePorts) {
    s1.io.in(i).valid := pipeRegOut(i).valid
    s1.io.in(i).bits := pipeRegOut(i).bits
    pipeRegOut(i).ready := s1.io.in(i).ready
    pipeRegOut(i).flush := s1.io.in(i).flush
    PipelineConnect(s0.io.out(i), pipeRegOut(i))
  }
  for (i <- 0 until FuConfig.numIssuePorts; j <- 0 until 2) {
    io.prf_read(i)(j) <> s1.io.prf_read(i)(j)
  }
  s1.io.csr_rdata := io.csr_rdata
  io.csr_read_addr := s1.io.csr_read_addr
  io.issuePorts <> s1.io.issuePorts
}
