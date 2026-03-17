package nzea_core.frontend

import chisel3._
import chisel3.util.{Mux1H, MuxLookup, PriorityEncoder, Valid}
import nzea_core.PipeIO
import nzea_core.backend.{AluOp, BruOp, DivOp, FuOpWidth, LsuOp, MulOp, SysuOp}
import nzea_core.retire.rob.RobMemType
import nzea_config.{FuConfig, NzeaConfig}

/** Unified issue queue entry: all source data + FuType + ready bits (bypass-applied).
  * Bypass is applied combinationally when reading for dispatch. */
class IssueQueueEntry(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int) extends Bundle {
  val fu_type        = FuType()
  val rs1_val        = UInt(32.W)
  val rs2_val        = UInt(32.W)
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
  })

  // -------- Queue storage --------
  val entries = Reg(Vec(depth, new IssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth)))
  val valids  = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val count   = RegInit(0.U((iqIdWidth + 1).W))
  val full    = count >= depth.U
  val bypassPorts = FuConfig.prfWritePorts(config).zipWithIndex.filter(_._1.hasBypass)

  // -------- Combinational bypass: for each entry, compute effective rs1/rs2 and ready (same logic as readPrfWithBypass) --------
  def bypassed(e: IssueQueueEntry): (UInt, UInt, Bool, Bool) = {
    val level1SelRs1 = bypassPorts.map { case (_, i) => io.bypass_level1(i).valid && io.bypass_level1(i).bits.addr === e.p_rs1 }
    val level2SelRs1 = bypassPorts.map { case (_, i) => io.prf_write(i).valid && io.prf_write(i).bits.addr === e.p_rs1 }
    val bypassSelRs1 = level1SelRs1 ++ level2SelRs1
    val bypassHitRs1 = if (bypassPorts.isEmpty) false.B else bypassSelRs1.reduce((a: Bool, b: Bool) => a || b)
    val bypassDataRs1 = if (bypassPorts.isEmpty) 0.U(32.W) else Mux1H(
      bypassSelRs1,
      bypassPorts.map { case (_, i) => io.bypass_level1(i).bits.data } ++ bypassPorts.map { case (_, i) => io.prf_write(i).bits.data }
    )
    val rs1_val   = Mux(e.p_rs1 === 0.U, 0.U(32.W), Mux(bypassHitRs1, bypassDataRs1, e.rs1_val))
    val rs1_ready = (e.p_rs1 === 0.U) || bypassHitRs1 || e.rs1_ready

    val level1SelRs2 = bypassPorts.map { case (_, i) => io.bypass_level1(i).valid && io.bypass_level1(i).bits.addr === e.p_rs2 }
    val level2SelRs2 = bypassPorts.map { case (_, i) => io.prf_write(i).valid && io.prf_write(i).bits.addr === e.p_rs2 }
    val bypassSelRs2 = level1SelRs2 ++ level2SelRs2
    val bypassHitRs2 = if (bypassPorts.isEmpty) false.B else bypassSelRs2.reduce((a: Bool, b: Bool) => a || b)
    val bypassDataRs2 = if (bypassPorts.isEmpty) 0.U(32.W) else Mux1H(
      bypassSelRs2,
      bypassPorts.map { case (_, i) => io.bypass_level1(i).bits.data } ++ bypassPorts.map { case (_, i) => io.prf_write(i).bits.data }
    )
    val rs2_val   = Mux(e.p_rs2 === 0.U, 0.U(32.W), Mux(bypassHitRs2, bypassDataRs2, e.rs2_val))
    val rs2_ready = (e.p_rs2 === 0.U) || bypassHitRs2 || e.rs2_ready
    (rs1_val, rs2_val, rs1_ready, rs2_ready)
  }

  val bypassedEntries = (0 until depth).map { i => bypassed(entries(i)) }

  // -------- Enqueue: write to first invalid slot --------
  val firstInvalid = PriorityEncoder(VecInit((0 until depth).map(i => !valids(i))).asUInt)
  io.in.ready := !full && !io.flush
  val enqFire = !io.flush && io.in.fire
  when(enqFire) {
    entries(firstInvalid) := io.in.bits
    valids(firstInvalid) := true.B
  }

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
        when(p_rs1 === waddr && p_rs1 =/= 0.U) {
          entries(i).rs1_val := wdata
          entries(i).rs1_ready := true.B
        }
        when(p_rs2 === waddr && p_rs2 =/= 0.U) {
          entries(i).rs2_val := wdata
          entries(i).rs2_ready := true.B
        }
      }
    }
  }

  // -------- Flush --------
  when(io.flush) {
    for (i <- 0 until depth) { valids(i) := false.B }
  }

  // -------- Dispatch: scan head-to-tail (index 0 to depth-1), first ready --------
  val fuReady = VecInit(io.issuePorts.orderedPorts.map(_.ready))
  val canIssue = Wire(Vec(depth, Bool()))
  for (i <- 0 until depth) {
    val (_, _, r1, r2) = bypassedEntries(i)
    val ft = entries(i).fu_type
    val portIdx = MuxLookup(ft.asUInt, 0.U)(fuTypeToPortIdx)
    canIssue(i) := valids(i) && r1 && r2 && fuReady(portIdx)
  }
  val firstReadyIdx = PriorityEncoder(canIssue.asUInt)
  val anyCanIssue = canIssue.asUInt.orR
  // Mux1H requires exactly one select bit; canIssue can have multiple 1s when several entries are ready.
  // Use one-hot from firstReadyIdx to avoid undefined behavior and wrong fu_type routing (e.g. LUI to AGU).
  val selOneHot = VecInit((0 until depth).map(i => (i.U === firstReadyIdx)))

  val (selFuType, _) = FuType.safe(Mux1H(selOneHot, entries.map(_.fu_type.asUInt)))
  val rs1_val       = Mux1H(selOneHot, bypassedEntries.map(_._1))
  val rs2_val       = Mux1H(selOneHot, bypassedEntries.map(_._2))

  val e = Wire(new IssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth))
  val (eFuType, _)  = FuType.safe(Mux1H(selOneHot, entries.map(_.fu_type.asUInt)))
  val (eMemType, _) = RobMemType.safe(Mux1H(selOneHot, entries.map(_.mem_type.asUInt)))
  e.fu_type   := eFuType
  e.mem_type  := eMemType
  e.rs1_val   := rs1_val
  e.rs2_val   := rs2_val
  e.rs1_ready := Mux1H(selOneHot, bypassedEntries.map(_._3))
  e.rs2_ready := Mux1H(selOneHot, bypassedEntries.map(_._4))
  e.p_rs1     := Mux1H(selOneHot, entries.map(_.p_rs1))
  e.p_rs2     := Mux1H(selOneHot, entries.map(_.p_rs2))
  e.imm       := Mux1H(selOneHot, entries.map(_.imm))
  e.pc        := Mux1H(selOneHot, entries.map(_.pc))
  e.pred_next_pc   := Mux1H(selOneHot, entries.map(_.pred_next_pc))
  e.fu_op     := Mux1H(selOneHot, entries.map(_.fu_op))
  e.fu_src    := Mux1H(selOneHot, entries.map(_.fu_src))
  e.csr_addr  := Mux1H(selOneHot, entries.map(_.csr_addr))
  e.csr_rdata := Mux1H(selOneHot, entries.map(_.csr_rdata))
  e.csr_will_write := Mux1H(selOneHot, entries.map(_.csr_will_write))
  e.rob_id    := Mux1H(selOneHot, entries.map(_.rob_id))
  e.p_rd      := Mux1H(selOneHot, entries.map(_.p_rd))
  e.old_p_rd  := Mux1H(selOneHot, entries.map(_.old_p_rd))
  e.rd_index  := Mux1H(selOneHot, entries.map(_.rd_index))
  e.lsq_id    := Mux1H(selOneHot, entries.map(_.lsq_id))
  e.might_flush := Mux1H(selOneHot, entries.map(_.might_flush))

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
  when(deqFire) {
    // When enq and deq target the same slot, the new instruction overwrites the old;
    // do not clear valid, else the new instruction is lost. (Bug: 0x80005c9c branch silently disappeared.)
    when(!(enqFire && firstInvalid === firstReadyIdx)) {
      valids(firstReadyIdx) := false.B
    }
  }

  // Count: enq+1, deq-1; when both in same cycle, net 0. Avoid separate when blocks (last-wins bug).
  when(io.flush) {
    count := 0.U
  }.otherwise {
    count := count + enqFire.asUInt - deqFire.asUInt
  }

  io.in.flush := io.flush
}
