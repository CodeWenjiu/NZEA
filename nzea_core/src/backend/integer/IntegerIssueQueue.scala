package nzea_core.backend.integer

import chisel3._
import chisel3.util.{Mux1H, MuxLookup, PopCount, PriorityEncoder, Valid}
import nzea_rtl.{PipeIO, PipelineConnect}
import nzea_core.frontend.{AluSrc, CsrType, FuDecode, FuSrcWidth, FuType, IssuePortsBundle, PrfBypass, PrfRawRead, PrfReadIO, PrfWriteBundle}
import nzea_core.retire.rob.RobMemType
import nzea_config.{FuConfig, NzeaConfig}
import nzea_core.backend.integer.nnu.NnOp

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
class IntegerIssueQueueSelectStage(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int, depth: Int, numPrfWritePorts: Int)(
  implicit config: NzeaConfig
) extends Module {
  private val issuePortConfigs = FuConfig.issuePorts(config)

  /** Port index for SYSU. CSR-write pending stalls only SYSU so older ALU/AGU/BRU/MUL/DIV can still issue (avoids deadlock). */
  private val sysuPortIdx = (FuConfig.numIssuePorts - 1).U
  private val fuTypeToPortIdx: Seq[(UInt, UInt)] = FuType.all.zipWithIndex.map { case (ft, i) =>
    val portName = ft match {
      case FuType.ALU  => "ALU"
      case FuType.BRU  => "BRU"
      case FuType.LSU  => "AGU"
      case FuType.MUL  => "MUL"
      case FuType.DIV  => "DIV"
      case FuType.SYSU => "SYSU"
      case FuType.NNU  => "NNU"
    }
    val idx = issuePortConfigs.indexWhere(_.name == portName)
    (ft.asUInt, (if (idx >= 0) idx else 0).U)
  }

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new IntegerIssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth)))
    val prf_write     = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    val bypass_level1 = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    /** Clear SYSU CSR-write pending when matching rob commits. */
    val commit_rob_id = Input(UInt(robIdWidth.W))
    val commit_valid  = Input(Bool())
    /** Read-stage ALU: valid + p_rd only; Select uses for operand readiness (no data). */
    val alu_read_hint = Input(Valid(UInt(prfAddrWidth.W)))
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
    when(!flush && io.alu_read_hint.valid && entryValid) {
      val waddr = io.alu_read_hint.bits
      val p_rs1 = Mux(enqFire && firstInvalid === i.U, io.in.bits.p_rs1, entries(i).p_rs1)
      val p_rs2 = Mux(enqFire && firstInvalid === i.U, io.in.bits.p_rs2, entries(i).p_rs2)
      when(p_rs1 === waddr && p_rs1 =/= 0.U) { entries(i).rs1_ready := true.B }
      when(p_rs2 === waddr && p_rs2 =/= 0.U) { entries(i).rs2_ready := true.B }
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
    val rs1BypassHit = (io.alu_read_hint.valid && io.alu_read_hint.bits === entry.p_rs1 && entry.p_rs1 =/= 0.U) ||
      bypassPorts.map { case (_, j) =>
        (io.bypass_level1(j).valid && io.bypass_level1(j).bits.addr === entry.p_rs1) ||
        (io.prf_write(j).valid && io.prf_write(j).bits.addr === entry.p_rs1)
      }.foldLeft(false.B)(_ || _)
    val rs2BypassHit = (io.alu_read_hint.valid && io.alu_read_hint.bits === entry.p_rs2 && entry.p_rs2 =/= 0.U) ||
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
class IntegerIssueQueueReadStage(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int)(implicit config: NzeaConfig) extends Module {
  private val hasM  = config.isaConfig.hasM
  private val hasNn = config.isaConfig.hasWjcus0
  private val numPorts = FuConfig.numIssuePorts

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
    /** ALU port in read stage: valid + p_rd for SelectStage readiness only (no result). */
    val alu_read_hint = Output(Valid(UInt(prfAddrWidth.W)))
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

  private def wireAlu(i: Int): Unit = {
    val e = entry(i)
    io.issuePorts.alu.valid := io.in(i).valid
    val (aluSrc0, _) = AluSrc.safe(FuDecode.take(e.fu_src, AluSrc.getWidth))
    io.issuePorts.alu.bits.opA := MuxLookup(aluSrc0.asUInt, rs1(i))(Seq(
      AluSrc.ImmZero.asUInt -> e.imm,
      AluSrc.PcImm.asUInt   -> e.pc
    ))
    io.issuePorts.alu.bits.opB := MuxLookup(aluSrc0.asUInt, rs2(i))(Seq(
      AluSrc.Rs1Imm.asUInt  -> e.imm,
      AluSrc.ImmZero.asUInt -> 0.U(32.W),
      AluSrc.PcImm.asUInt   -> e.imm
    ))
    io.issuePorts.alu.bits.aluOp := AluOp.safe(FuDecode.take(e.fu_op, AluOp.getWidth))._1
    io.issuePorts.alu.bits.pc := e.pc
    io.issuePorts.alu.bits.rob_id := e.rob_id
    io.issuePorts.alu.bits.p_rd := e.p_rd
  }

  private def wireBru(i: Int): Unit = {
    val e = entry(i)
    io.issuePorts.bru.valid := io.in(i).valid
    io.issuePorts.bru.bits.pc := e.pc
    io.issuePorts.bru.bits.pred_next_pc := e.pred_next_pc
    io.issuePorts.bru.bits.offset := e.imm
    io.issuePorts.bru.bits.rs1 := rs1(i)
    io.issuePorts.bru.bits.rs2 := rs2(i)
    io.issuePorts.bru.bits.bruOp := BruOp.safe(FuDecode.take(e.fu_op, BruOp.getWidth))._1
    io.issuePorts.bru.bits.rob_id := e.rob_id
    io.issuePorts.bru.bits.p_rd := e.p_rd
  }

  private def wireAgu(i: Int): Unit = {
    val e = entry(i)
    io.issuePorts.agu.valid := io.in(i).valid
    io.issuePorts.agu.bits.base := rs1(i)
    io.issuePorts.agu.bits.imm := e.imm
    io.issuePorts.agu.bits.lsuOp := LsuOp.safe(FuDecode.take(e.fu_op, LsuOp.getWidth))._1
    io.issuePorts.agu.bits.storeData := rs2(i)
    io.issuePorts.agu.bits.pc := e.pc
    io.issuePorts.agu.bits.rob_id := e.rob_id
    io.issuePorts.agu.bits.p_rd := e.p_rd
    io.issuePorts.agu.bits.lsq_id := e.lsq_id
  }

  private def wireMul(i: Int): Unit = {
    val e = entry(i)
    io.issuePorts.mul.get.valid := io.in(i).valid
    io.issuePorts.mul.get.bits.opA := rs1(i)
    io.issuePorts.mul.get.bits.opB := rs2(i)
    io.issuePorts.mul.get.bits.mulOp := MulOp.safe(FuDecode.take(e.fu_op, MulOp.getWidth))._1
    io.issuePorts.mul.get.bits.pc := e.pc
    io.issuePorts.mul.get.bits.rob_id := e.rob_id
    io.issuePorts.mul.get.bits.p_rd := e.p_rd
  }

  private def wireDiv(i: Int): Unit = {
    val e = entry(i)
    io.issuePorts.div.get.valid := io.in(i).valid
    io.issuePorts.div.get.bits.opA := rs1(i)
    io.issuePorts.div.get.bits.opB := rs2(i)
    io.issuePorts.div.get.bits.divOp := DivOp.safe(FuDecode.take(e.fu_op, DivOp.getWidth))._1
    io.issuePorts.div.get.bits.pc := e.pc
    io.issuePorts.div.get.bits.rob_id := e.rob_id
    io.issuePorts.div.get.bits.p_rd := e.p_rd
  }

  private def wireSysu(i: Int): Unit = {
    val e = entry(i)
    io.issuePorts.sysu.valid := io.in(i).valid
    io.issuePorts.sysu.bits.rob_id := e.rob_id
    io.issuePorts.sysu.bits.pc := e.pc
    io.issuePorts.sysu.bits.p_rd := e.p_rd
    io.issuePorts.sysu.bits.csr_type := Mux(e.fu_type === FuType.SYSU, CsrType.fromAddr(e.csr_addr), CsrType.None)
    io.issuePorts.sysu.bits.csr_rdata := io.csr_rdata
    io.issuePorts.sysu.bits.rs1_val := rs1(i)
    io.issuePorts.sysu.bits.sysuOp := SysuOp.safe(FuDecode.take(e.fu_op, SysuOp.getWidth))._1
    io.issuePorts.sysu.bits.imm := e.imm
  }

  private def wireNnu(i: Int): Unit = {
    val e = entry(i)
    io.issuePorts.nnu.get.valid := io.in(i).valid
    io.issuePorts.nnu.get.bits.nnOp := NnOp.safe(FuDecode.take(e.fu_op, NnOp.getWidth))._1
    io.issuePorts.nnu.get.bits.rs1 := rs1(i)
    io.issuePorts.nnu.get.bits.rs2 := rs2(i)
    io.issuePorts.nnu.get.bits.pc := e.pc
    io.issuePorts.nnu.get.bits.rob_id := e.rob_id
    io.issuePorts.nnu.get.bits.p_rd := e.p_rd
  }

  io.csr_read_addr := Mux(
    io.in(sysuIdx).valid && entry(sysuIdx).fu_type === FuType.SYSU,
    entry(sysuIdx).csr_addr,
    0.U(12.W)
  )

  wireAlu(aluIdx)
  val aluE = entry(aluIdx)
  io.alu_read_hint.valid := io.in(aluIdx).valid
  io.alu_read_hint.bits := aluE.p_rd

  wireBru(bruIdx)
  wireAgu(aguIdx)
  mulIdxOpt.foreach(wireMul)
  divIdxOpt.foreach(wireDiv)
  nnuIdxOpt.foreach(wireNnu)
  wireSysu(sysuIdx)
}

/** Integer issue queue: 2-stage pipeline. S1 selects slot; S2 reads PRF+bypass and dispatches to FUs.
  * Flush: S2 extracts from issuePorts (consumer); S1 gets it via PipelineConnect from S2 input. */
class IntegerIssueQueue(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int, depth: Int, numPrfWritePorts: Int)(
  implicit config: NzeaConfig
) extends Module {
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

  val s0 = Module(new IntegerIssueQueueSelectStage(robIdWidth, prfAddrWidth, lsqIdWidth, depth, numPrfWritePorts))
  val s1 = Module(new IntegerIssueQueueReadStage(robIdWidth, prfAddrWidth, lsqIdWidth))

  s0.io.in <> io.in
  s0.io.prf_write := io.prf_write
  s0.io.bypass_level1 := io.bypass_level1
  s0.io.commit_rob_id := io.commit_rob_id
  s0.io.commit_valid  := io.commit_valid
  s0.io.alu_read_hint := s1.io.alu_read_hint
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
