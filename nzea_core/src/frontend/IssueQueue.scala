package nzea_core.frontend

import chisel3._
import chisel3.util.{Mux1H, MuxLookup, PopCount, PriorityEncoder, Valid}
import nzea_core.PipeIO
import nzea_core.backend.{AluOp, BruOp, DivOp, FuOpWidth, LsuOp, MulOp, SysuOp}
import nzea_core.retire.rob.RobMemType
import nzea_config.{FuConfig, NzeaConfig}

/** Issue queue entry: FuType + operand tags (paddr) + ready. No source data; values read via bypass net at dispatch. */
class IssueQueueEntry(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int) extends Bundle {
  val fu_type        = FuType()
  val rs1_ready      = Bool()
  val rs2_ready      = Bool()
  val p_rs1          = UInt(prfAddrWidth.W)
  val p_rs2          = UInt(prfAddrWidth.W)
  val imm            = UInt(32.W)
  val pc             = UInt(32.W)
  val pred_next_pc   = UInt(32.W)
  val fu_op          = UInt(FuOpWidth.Width.W)
  val fu_src         = UInt(FuSrcWidth.Width.W)
  val csr_addr       = UInt(12.W)
  val csr_rdata      = UInt(32.W)
  val csr_will_write = Bool()
  val rob_id         = UInt(robIdWidth.W)
  val p_rd           = UInt(prfAddrWidth.W)
  val old_p_rd       = UInt(prfAddrWidth.W)
  val rd_index       = UInt(5.W)
  val lsq_id         = UInt(lsqIdWidth.W)
  val might_flush    = Bool()
  val mem_type       = RobMemType()
}

/** Issue Queue: receives unified entries from ISU; combinational bypass; dispatch head-to-tail first-ready. */
class IssueQueue(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int, depth: Int, numPrfWritePorts: Int)(
  implicit config: NzeaConfig
) extends Module {
  private val iqIdWidth = chisel3.util.log2Ceil(depth.max(2))
  private val numIssuePorts = FuConfig.numIssuePorts
  private val issuePortConfigs = FuConfig.issuePorts(config)
  private val fuTypeToPortIdx: Seq[(UInt, UInt)] = FuType.all.zipWithIndex.map { case (ft, i) =>
    val portName = ft match {
      case FuType.ALU  => "ALU"
      case FuType.BRU  => "BRU"
      case FuType.LSU  => "AGU"
      case FuType.MUL  => "MUL"
      case FuType.DIV  => "DIV"
      case FuType.SYSU => "SYSU"
    }
    val idx = issuePortConfigs.indexWhere(_.name == portName)
    (ft.asUInt, (if (idx >= 0) idx else 0).U)
  }

  val io = IO(new Bundle {
    val in            = Flipped(new PipeIO(new IssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth)))
    val flush         = Input(Bool())  // global flush (e.g. from Commit)
    val prf_write     = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    val bypass_level1 = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    val issuePorts    = new IssuePortsBundle(robIdWidth, prfAddrWidth, lsqIdWidth)
    // Operand read: IQ outputs addr, receives data from PRF+bypass net (in ISU). Length-2 Seq for rs1/rs2.
    val prf_read = Vec(2, new PrfReadIO(prfAddrWidth))
  })

  // -------- Queue storage --------
  val entries = Reg(Vec(depth, new IssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth)))
  val valids  = RegInit(VecInit(Seq.fill(depth)(false.B)))
  // Derive count from valids to avoid desync (was: count=8 but valids=0 -> deadlock)
  val count   = PopCount(valids.asUInt)
  val full    = count >= depth.U
  val bypassPorts = FuConfig.prfWritePorts(config).zipWithIndex.filter(_._1.hasBypass)

  // -------- Enqueue: write to first invalid slot --------
  val firstInvalid = PriorityEncoder(VecInit((0 until depth).map(i => !valids(i))).asUInt)
  io.in.ready := !full && !io.flush
  val enqFire = !io.flush && io.in.fire

  // -------- Bypass persist: when FU/WB writes, update matching IQ entries (rs1/rs2) so we never miss the bypass window --------
  // Include entry being enqueued this cycle (enqFire && firstInvalid===i), else we miss bypass when producer
  // and consumer fire in the same cycle.
  for (i <- 0 until depth) {
    val entryValid = valids(i) || (enqFire && firstInvalid === i.U)
    for (j <- 0 until numPrfWritePorts) {
      val waddr = Mux(io.bypass_level1(j).valid, io.bypass_level1(j).bits.addr, io.prf_write(j).bits.addr)
      val wdata = Mux(io.bypass_level1(j).valid, io.bypass_level1(j).bits.data, io.prf_write(j).bits.data)
      val wvalid = io.bypass_level1(j).valid || io.prf_write(j).valid
      when(!io.flush && wvalid && entryValid) {
        val p_rs1 = Mux(enqFire && firstInvalid === i.U, io.in.bits.p_rs1, entries(i).p_rs1)
        val p_rs2 = Mux(enqFire && firstInvalid === i.U, io.in.bits.p_rs2, entries(i).p_rs2)
        when(p_rs1 === waddr && p_rs1 =/= 0.U) { entries(i).rs1_ready := true.B }
        when(p_rs2 === waddr && p_rs2 =/= 0.U) { entries(i).rs2_ready := true.B }
      }
    }
  }

  // -------- Flush --------
  when(io.flush) {
    for (i <- 0 until depth) { valids(i) := false.B }
  }

  // -------- Dispatch: only slots valid from prev cycle can issue. Operand ready = slot.rs*_ready OR bypass net has write.
  // No same-cycle bypass: instr must stay in IQ at least 1 cycle before issue.
  val fuReady = VecInit(io.issuePorts.orderedPorts.map(_.ready))
  val canIssue = Wire(Vec(depth, Bool()))
  for (i <- 0 until depth) {
    val entry = entries(i)
    val rs1BypassHit = bypassPorts.map { case (_, j) =>
      (io.bypass_level1(j).valid && io.bypass_level1(j).bits.addr === entry.p_rs1) ||
      (io.prf_write(j).valid && io.prf_write(j).bits.addr === entry.p_rs1)
    }.foldLeft(false.B)(_ || _)
    val rs2BypassHit = bypassPorts.map { case (_, j) =>
      (io.bypass_level1(j).valid && io.bypass_level1(j).bits.addr === entry.p_rs2) ||
      (io.prf_write(j).valid && io.prf_write(j).bits.addr === entry.p_rs2)
    }.foldLeft(false.B)(_ || _)
    val actual_rs1_ready = (entry.p_rs1 === 0.U) || entry.rs1_ready || rs1BypassHit
    val actual_rs2_ready = (entry.p_rs2 === 0.U) || entry.rs2_ready || rs2BypassHit
    val portIdx = MuxLookup(entry.fu_type.asUInt, 0.U)(fuTypeToPortIdx)
    canIssue(i) := valids(i) && actual_rs1_ready && actual_rs2_ready && fuReady(portIdx)
  }
  val firstReadyIdx = PriorityEncoder(canIssue.asUInt)
  val anyCanIssue = canIssue.asUInt.orR
  val selOneHot = VecInit((0 until depth).map(i => (i.U === firstReadyIdx)))

  val rawEntryFor = entries
  val sel_p_rs1 = Mux1H(selOneHot, rawEntryFor.map(_.p_rs1))
  val sel_p_rs2 = Mux1H(selOneHot, rawEntryFor.map(_.p_rs2))
  io.prf_read(0).addr := sel_p_rs1
  io.prf_read(1).addr := sel_p_rs2
  val rs1_val = Mux(sel_p_rs1 === 0.U, 0.U(32.W), io.prf_read(0).data)
  val rs2_val = Mux(sel_p_rs2 === 0.U, 0.U(32.W), io.prf_read(1).data)

  val (selFuType, _) = FuType.safe(Mux1H(selOneHot, rawEntryFor.map(_.fu_type.asUInt)))
  val e = Wire(new IssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth))
  val (eFuType, _)  = FuType.safe(Mux1H(selOneHot, rawEntryFor.map(_.fu_type.asUInt)))
  val (eMemType, _) = RobMemType.safe(Mux1H(selOneHot, rawEntryFor.map(_.mem_type.asUInt)))
  e.fu_type   := eFuType
  e.mem_type  := eMemType
  e.rs1_ready := true.B
  e.rs2_ready := true.B
  e.p_rs1     := sel_p_rs1
  e.p_rs2     := sel_p_rs2
  e.imm       := Mux1H(selOneHot, rawEntryFor.map(_.imm))
  e.pc        := Mux1H(selOneHot, rawEntryFor.map(_.pc))
  e.pred_next_pc   := Mux1H(selOneHot, rawEntryFor.map(_.pred_next_pc))
  e.fu_op     := Mux1H(selOneHot, rawEntryFor.map(_.fu_op))
  e.fu_src    := Mux1H(selOneHot, rawEntryFor.map(_.fu_src))
  e.csr_addr  := Mux1H(selOneHot, rawEntryFor.map(_.csr_addr))
  e.csr_rdata := Mux1H(selOneHot, rawEntryFor.map(_.csr_rdata))
  e.csr_will_write := Mux1H(selOneHot, rawEntryFor.map(_.csr_will_write))
  e.rob_id    := Mux1H(selOneHot, rawEntryFor.map(_.rob_id))
  e.p_rd      := Mux1H(selOneHot, rawEntryFor.map(_.p_rd))
  e.old_p_rd  := Mux1H(selOneHot, rawEntryFor.map(_.old_p_rd))
  e.rd_index  := Mux1H(selOneHot, rawEntryFor.map(_.rd_index))
  e.lsq_id    := Mux1H(selOneHot, rawEntryFor.map(_.lsq_id))
  e.might_flush := Mux1H(selOneHot, rawEntryFor.map(_.might_flush))

  val (aluSrc, _) = AluSrc.safe(FuDecode.take(e.fu_src, AluSrc.getWidth))
  val aluOpA = MuxLookup(aluSrc.asUInt, rs1_val)(Seq(
    AluSrc.ImmZero.asUInt -> e.imm,
    AluSrc.PcImm.asUInt   -> e.pc
  ))
  val aluOpB = MuxLookup(aluSrc.asUInt, rs2_val)(Seq(
    AluSrc.Rs1Imm.asUInt  -> e.imm,
    AluSrc.ImmZero.asUInt -> 0.U(32.W),
    AluSrc.PcImm.asUInt   -> e.imm
  ))
  val (aluOp, _) = AluOp.safe(FuDecode.take(e.fu_op, AluOp.getWidth))
  val (bruOp, _) = BruOp.safe(FuDecode.take(e.fu_op, BruOp.getWidth))
  val (mulOp, _) = MulOp.safe(FuDecode.take(e.fu_op, MulOp.getWidth))
  val (divOp, _) = DivOp.safe(FuDecode.take(e.fu_op, DivOp.getWidth))
  val (lsuOp, _) = LsuOp.safe(FuDecode.take(e.fu_op, LsuOp.getWidth))
  val (sysuOp, _) = SysuOp.safe(FuDecode.take(e.fu_op, SysuOp.getWidth))
  val csr_type_sysu = Mux(e.fu_type === FuType.SYSU, CsrType.fromAddr(e.csr_addr), CsrType.None)

  // Drive issue ports: only the selected port gets valid
  io.issuePorts.alu.valid := anyCanIssue && (selFuType === FuType.ALU)
  io.issuePorts.alu.bits.opA := aluOpA
  io.issuePorts.alu.bits.opB := aluOpB
  io.issuePorts.alu.bits.aluOp := aluOp
  io.issuePorts.alu.bits.pc := e.pc
  io.issuePorts.alu.bits.rob_id := e.rob_id
  io.issuePorts.alu.bits.p_rd := e.p_rd

  io.issuePorts.bru.valid := anyCanIssue && (selFuType === FuType.BRU)
  io.issuePorts.bru.bits.pc := e.pc
  io.issuePorts.bru.bits.pred_next_pc := e.pred_next_pc
  io.issuePorts.bru.bits.offset := e.imm
  io.issuePorts.bru.bits.rs1 := rs1_val
  io.issuePorts.bru.bits.rs2 := rs2_val
  io.issuePorts.bru.bits.bruOp := bruOp
  io.issuePorts.bru.bits.rob_id := e.rob_id
  io.issuePorts.bru.bits.p_rd := e.p_rd

  io.issuePorts.agu.valid := anyCanIssue && (selFuType === FuType.LSU)
  io.issuePorts.agu.bits.base := rs1_val
  io.issuePorts.agu.bits.imm := e.imm
  io.issuePorts.agu.bits.lsuOp := lsuOp
  io.issuePorts.agu.bits.storeData := rs2_val
  io.issuePorts.agu.bits.pc := e.pc
  io.issuePorts.agu.bits.rob_id := e.rob_id
  io.issuePorts.agu.bits.p_rd := e.p_rd
  io.issuePorts.agu.bits.lsq_id := e.lsq_id

  if (config.isaConfig.hasM) {
    io.issuePorts.mul.get.valid := anyCanIssue && (selFuType === FuType.MUL)
    io.issuePorts.mul.get.bits.opA := rs1_val
    io.issuePorts.mul.get.bits.opB := rs2_val
    io.issuePorts.mul.get.bits.mulOp := mulOp
    io.issuePorts.mul.get.bits.pc := e.pc
    io.issuePorts.mul.get.bits.rob_id := e.rob_id
    io.issuePorts.mul.get.bits.p_rd := e.p_rd

    io.issuePorts.div.get.valid := anyCanIssue && (selFuType === FuType.DIV)
    io.issuePorts.div.get.bits.opA := rs1_val
    io.issuePorts.div.get.bits.opB := rs2_val
    io.issuePorts.div.get.bits.divOp := divOp
    io.issuePorts.div.get.bits.pc := e.pc
    io.issuePorts.div.get.bits.rob_id := e.rob_id
    io.issuePorts.div.get.bits.p_rd := e.p_rd
  }

  io.issuePorts.sysu.valid := anyCanIssue && (selFuType === FuType.SYSU)
  io.issuePorts.sysu.bits.rob_id := e.rob_id
  io.issuePorts.sysu.bits.pc := e.pc
  io.issuePorts.sysu.bits.p_rd := e.p_rd
  io.issuePorts.sysu.bits.csr_type := csr_type_sysu
  io.issuePorts.sysu.bits.csr_rdata := e.csr_rdata
  io.issuePorts.sysu.bits.rs1_val := rs1_val
  io.issuePorts.sysu.bits.sysuOp := sysuOp
  io.issuePorts.sysu.bits.imm := e.imm

  val orderedPortsFire = VecInit(io.issuePorts.orderedPorts.map(_.fire))
  val portIdxForSel   = MuxLookup(selFuType.asUInt, 0.U)(fuTypeToPortIdx)
  val issueFire       = anyCanIssue && orderedPortsFire(portIdxForSel)
  val deqFire         = !io.flush && issueFire
  // Deq clear FIRST, then enq overwrites when same slot (enq+deq bypass). Order ensures new instr not lost.
  when(deqFire) {
    valids(firstReadyIdx) := false.B
  }
  when(enqFire) {
    entries(firstInvalid) := io.in.bits
    valids(firstInvalid) := true.B
  }

  // count is derived from PopCount(valids), no separate update needed

  io.in.flush := io.flush
}
