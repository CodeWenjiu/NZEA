package nzea_core.frontend

import chisel3._
import chisel3.util.{Mux1H, MuxLookup, PriorityEncoder, Valid, switch, is}
import nzea_core.PipeIO
import nzea_config.NzeaConfig
import nzea_core.backend.{AluOp, BruOp, DivOp, LsuOp, MulOp, SysuOp}
import nzea_core.retire.rob.{RobEnqIO, RobMemType, LsAllocReq}
import nzea_config.FuConfig

/** PRF write port: addr, data. Shared by all FU completions. */
class PrfWriteBundle(prfAddrWidth: Int) extends Bundle {
  val addr = UInt(prfAddrWidth.W)
  val data = UInt(32.W)
}

/** CSR write from SYSU: csr_type, data. */
class CsrWriteBundle extends Bundle {
  val csr_type = CsrType()
  val data     = UInt(32.W)
}

/** FuType to port name: LSU (decode) maps to AGU (port). */
private object FuTypePortName {
  def portName(fuType: FuType.Type): String = fuType match {
    case FuType.ALU  => "ALU"
    case FuType.BRU  => "BRU"
    case FuType.LSU  => "AGU"
    case FuType.MUL  => "MUL"
    case FuType.DIV  => "DIV"
    case FuType.SYSU => "SYSU"
  }
}

/** ISU factory: config-driven, port count derived from FuConfig. */
object ISU {
  def apply(addrWidth: Int)(implicit config: NzeaConfig): ISU =
    Module(new ISU(addrWidth, FuConfig.numPrfWritePorts))
}

/** ISU: Issue Unit. Per-port payload types; operand extraction (e.g. ALU opA/opB) done in ISU before pipeline reg.
  * Mask-based routing selects port; only selected port gets valid.
  */
class ISU(addrWidth: Int, numPrfWritePorts: Int)(implicit config: NzeaConfig) extends Module {
  private val robDepth       = config.robDepth
  private val robIdWidth     = chisel3.util.log2Ceil(robDepth.max(2))
  private val prfAddrWidth    = config.prfAddrWidth
  private val prfDepth        = config.prfDepth
  private val numIssuePorts   = FuConfig.numIssuePorts
  private val issuePortConfigs = FuConfig.issuePorts

  // -------- Elaboration-time: capability mask (FuType -> port bitmask) --------
  private val fuTypeToPortMask: Seq[(UInt, UInt)] = FuType.all.map { fuType =>
    val portName = FuTypePortName.portName(fuType)
    val supportedIndices = issuePortConfigs.zipWithIndex.filter(_._1.name == portName).map(_._2)
    val maskValue = supportedIndices.foldLeft(0)((acc, idx) => acc | (1 << idx))
    (fuType.asUInt, maskValue.U(numIssuePorts.W))
  }

  // -------- IO --------

  private val lsqIdWidth = chisel3.util.log2Ceil((robDepth / 2).max(1).max(2))

  val io = IO(new Bundle {
    val in             = Flipped(new PipeIO(new IDUOut(addrWidth, prfAddrWidth)))
    val rob_enq        = Flipped(new RobEnqIO(robIdWidth, prfAddrWidth))
    val ls_alloc       = new Bundle {
      val valid  = Output(Bool())
      val ready  = Input(Bool())
      val bits   = Output(new LsAllocReq(robIdWidth, prfAddrWidth))
      val lsq_id = Input(UInt(lsqIdWidth.W))
    }
    val prf_write      = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    val bypass_level1  = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    val prf_read_addr  = Input(UInt(prfAddrWidth.W))
    val prf_read_data  = Output(UInt(32.W))
    val csr_write      = Input(Valid(new CsrWriteBundle))
    val commit_rob_id  = Input(UInt(robIdWidth.W))
    val commit_valid   = Input(Bool())
    val issuePorts     = new IssuePortsBundle(robIdWidth, prfAddrWidth, lsqIdWidth)
  })

  // -------- Physical Register File (banked for timing) --------
  private val numBanks  = 4
  private val bankDepth = 16
  require(prfDepth == numBanks * bankDepth, s"prfDepth=$prfDepth must equal numBanks*bankDepth")

  val bank_regs  = RegInit(VecInit(Seq.tabulate(numBanks)(_ => VecInit(Seq.fill(bankDepth)(0.U(32.W))))))
  val bank_ready = RegInit(VecInit(Seq.tabulate(numBanks)(b =>
    VecInit(Seq.tabulate(bankDepth)(i => (b * bankDepth + i) < 32).map(_.B))
  )))

  for (bank <- 0 until numBanks) {
    for (idx <- 0 until bankDepth) {
      val writeSel = (0 until numPrfWritePorts).map { i =>
        io.prf_write(i).valid &&
        io.prf_write(i).bits.addr(prfAddrWidth - 1, prfAddrWidth - 2) === bank.U &&
        io.prf_write(i).bits.addr(prfAddrWidth - 3, 0) === idx.U
      }
      val anyWrite = writeSel.reduce((a, b) => a || b)
      val writeData = Mux1H(writeSel, (0 until numPrfWritePorts).map(i => io.prf_write(i).bits.data))
      when(anyWrite) {
        bank_regs(bank)(idx) := writeData
        bank_ready(bank)(idx) := true.B
      }
    }
  }
  when(io.in.fire && io.in.bits.p_rd =/= 0.U) {
    val p_rd  = io.in.bits.p_rd
    val bank  = p_rd(prfAddrWidth - 1, prfAddrWidth - 2)
    val idx   = p_rd(prfAddrWidth - 3, 0)
    bank_ready(bank)(idx) := false.B
  }

  def readPrf(addr: UInt): (UInt, Bool) = {
    val bankSel = (0 until numBanks).map(b => addr(prfAddrWidth - 1, prfAddrWidth - 2) === b.U)
    val idx     = addr(prfAddrWidth - 3, 0)
    val data    = Mux(addr === 0.U, 0.U(32.W), Mux1H(bankSel, (0 until numBanks).map(b => bank_regs(b)(idx))))
    val ready   = Mux(addr === 0.U, true.B, Mux1H(bankSel, (0 until numBanks).map(b => bank_ready(b)(idx))))
    (data, ready)
  }

  def readPrfWithBypass(addr: UInt): (UInt, Bool) = {
    val (prfData, prfReady) = readPrf(addr)
    val bypassPorts = FuConfig.prfWritePorts(config).zipWithIndex.filter(_._1.hasBypass)
    val level1Sel = bypassPorts.map { case (_, i) => io.bypass_level1(i).valid && io.bypass_level1(i).bits.addr === addr }
    val level2Sel = bypassPorts.map { case (_, i) => io.prf_write(i).valid && io.prf_write(i).bits.addr === addr }
    val bypassSel = level1Sel ++ level2Sel
    val bypassHit = if (bypassPorts.isEmpty) false.B else bypassSel.reduce((a, b) => a || b)
    val bypassData = if (bypassPorts.isEmpty) 0.U(32.W) else Mux1H(
      bypassSel,
      bypassPorts.map { case (_, i) => io.bypass_level1(i).bits.data } ++ bypassPorts.map { case (_, i) => io.prf_write(i).bits.data }
    )
    val data  = Mux(addr === 0.U, 0.U(32.W), Mux(bypassHit, bypassData, prfData))
    val ready = (addr === 0.U) || bypassHit || prfReady
    (data, ready)
  }

  val (prf_read_val, _) = readPrf(io.prf_read_addr)
  val commitWbBypassSel = (0 until numPrfWritePorts).map(i => io.prf_write(i).valid && io.prf_write(i).bits.addr === io.prf_read_addr)
  val commitWbBypassHit = commitWbBypassSel.reduce((a, b) => a || b)
  val commitWbBypassData = Mux1H(commitWbBypassSel, (0 until numPrfWritePorts).map(i => io.prf_write(i).bits.data))
  io.prf_read_data := Mux(io.prf_read_addr === 0.U, 0.U(32.W), Mux(commitWbBypassHit, commitWbBypassData, prf_read_val))

  // -------- CSR registers --------
  val csr_mstatus  = RegInit(0x1800.U(32.W))
  val csr_mtvec    = RegInit(0.U(32.W))
  val csr_mepc     = RegInit(0.U(32.W))
  val csr_mcause   = RegInit(0.U(32.W))
  val csr_mscratch = RegInit(0.U(32.W))

  when(io.csr_write.valid && io.csr_write.bits.csr_type =/= CsrType.None) {
    switch(io.csr_write.bits.csr_type) {
      is(CsrType.Mstatus)  { csr_mstatus  := io.csr_write.bits.data }
      is(CsrType.Mtvec)    { csr_mtvec    := io.csr_write.bits.data }
      is(CsrType.Mepc)     { csr_mepc     := io.csr_write.bits.data }
      is(CsrType.Mcause)   { csr_mcause   := io.csr_write.bits.data }
      is(CsrType.Mscratch) { csr_mscratch := io.csr_write.bits.data }
    }
  }

  def readCsr(csrType: CsrType.Type): UInt =
    Mux(csrType === CsrType.None, 0.U(32.W),
      Mux1H(
        Seq(
          csrType === CsrType.Mstatus,
          csrType === CsrType.Mtvec,
          csrType === CsrType.Mepc,
          csrType === CsrType.Mcause,
          csrType === CsrType.Mscratch
        ),
        Seq(csr_mstatus, csr_mtvec, csr_mepc, csr_mcause, csr_mscratch)
      ))

  val csr_type_sysu = Mux(
    io.in.bits.fu_type === FuType.SYSU,
    CsrType.fromAddr(io.in.bits.csr_addr),
    CsrType.None
  )
  val csr_rdata = readCsr(csr_type_sysu)

  val rs1_addr = io.in.bits.p_rs1
  val rs2_addr = io.in.bits.p_rs2
  val (rs1_val, rs1_ready) = readPrfWithBypass(rs1_addr)
  val (rs2_val, rs2_ready) = readPrfWithBypass(rs2_addr)
  val stall    = io.in.valid && (!rs1_ready || !rs2_ready)

  // -------- Flush --------
  io.in.flush := io.issuePorts.orderedPorts.map(_.flush).reduce(_ || _)

  // -------- Dispatch --------
  val fu_type       = io.in.bits.fu_type
  val fu_src        = io.in.bits.fu_src
  val fu_op         = io.in.bits.fu_op
  val imm           = io.in.bits.imm
  val pc            = io.in.bits.pc
  val rob_id        = io.rob_enq.rob_id
  val can_dispatch  = io.in.valid && !stall

  val will_csr_write = csr_type_sysu =/= CsrType.None && io.in.bits.csr_will_write
  val pending_csr_write_rob_id = RegInit(0.U(robIdWidth.W))
  val pending_csr_write_valid  = RegInit(false.B)
  when(io.in.flush) {
    pending_csr_write_valid := false.B
  }.elsewhen(io.commit_valid && pending_csr_write_valid && io.commit_rob_id === pending_csr_write_rob_id) {
    pending_csr_write_valid := false.B
  }.elsewhen(can_dispatch && io.in.ready && fu_type === FuType.SYSU && will_csr_write) {
    pending_csr_write_valid := true.B
    pending_csr_write_rob_id := rob_id
  }
  val csr_write_pending_stall = pending_csr_write_valid

  // -------- Mask-based routing --------
  val valid_port_mask     = MuxLookup(fu_type.asUInt, 0.U(numIssuePorts.W))(fuTypeToPortMask)
  val current_ports_ready = VecInit(io.issuePorts.orderedPorts.map(_.ready)).asUInt
  val lsu_can_alloc       = Mux(fu_type === FuType.LSU, io.ls_alloc.ready, true.B)
  val available_and_ready = valid_port_mask & current_ports_ready
  val can_issue = can_dispatch && !csr_write_pending_stall && io.rob_enq.req.ready && lsu_can_alloc && (available_and_ready =/= 0.U)
  val selected_port_index = PriorityEncoder(available_and_ready)

  val (lsuOp, _) = LsuOp.safe(FuDecode.take(fu_op, LsuOp.getWidth))
  io.rob_enq.req.valid := can_issue
  io.ls_alloc.valid := can_issue && (fu_type === FuType.LSU)
  io.ls_alloc.bits.rob_id := rob_id
  io.ls_alloc.bits.p_rd := io.in.bits.p_rd
  io.ls_alloc.bits.lsuOp := lsuOp.asUInt
  io.rob_enq.req.bits.rd_index := Mux(fu_type === FuType.SYSU, 0.U(5.W), io.in.bits.rd_index)
  io.rob_enq.req.bits.might_flush := (fu_type === FuType.BRU)
  io.rob_enq.req.bits.mem_type := Mux(
    fu_type === FuType.LSU,
    Mux(LsuOp.isLoad(lsuOp), RobMemType.Load, RobMemType.Store),
    RobMemType.None
  )
  io.rob_enq.req.bits.p_rd := io.in.bits.p_rd
  io.rob_enq.req.bits.old_p_rd := io.in.bits.old_p_rd

  // -------- Per-port payloads (operand extraction in ISU, before pipeline reg) --------
  val (aluSrc, _) = AluSrc.safe(FuDecode.take(fu_src, AluSrc.getWidth))
  val aluOpA = MuxLookup(aluSrc.asUInt, rs1_val)(Seq(
    AluSrc.ImmZero.asUInt -> imm,
    AluSrc.PcImm.asUInt   -> pc
  ))
  val aluOpB = MuxLookup(aluSrc.asUInt, rs2_val)(Seq(
    AluSrc.Rs1Imm.asUInt  -> imm,
    AluSrc.ImmZero.asUInt -> 0.U(32.W),
    AluSrc.PcImm.asUInt   -> imm
  ))
  val (aluOp, _) = AluOp.safe(FuDecode.take(fu_op, AluOp.getWidth))
  val (bruOp, _) = BruOp.safe(FuDecode.take(fu_op, BruOp.getWidth))
  val (mulOp, _) = MulOp.safe(FuDecode.take(fu_op, MulOp.getWidth))
  val (divOp, _) = DivOp.safe(FuDecode.take(fu_op, DivOp.getWidth))
  val (sysuOp, _) = SysuOp.safe(FuDecode.take(fu_op, SysuOp.getWidth))

  // -------- Assign per-port valid and bits --------
  issuePortConfigs.zipWithIndex.foreach { case (cfg, i) =>
    val sel = selected_port_index === i.U
    cfg.name match {
      case "ALU" =>
        io.issuePorts.alu.valid := can_issue && sel
        io.issuePorts.alu.bits.opA   := aluOpA
        io.issuePorts.alu.bits.opB   := aluOpB
        io.issuePorts.alu.bits.aluOp  := aluOp
        io.issuePorts.alu.bits.pc     := pc
        io.issuePorts.alu.bits.rob_id := rob_id
        io.issuePorts.alu.bits.p_rd   := io.in.bits.p_rd
      case "BRU" =>
        io.issuePorts.bru.valid := can_issue && sel
        io.issuePorts.bru.bits.pc           := pc
        io.issuePorts.bru.bits.pred_next_pc := io.in.bits.pred_next_pc
        io.issuePorts.bru.bits.offset       := imm
        io.issuePorts.bru.bits.rs1          := rs1_val
        io.issuePorts.bru.bits.rs2          := rs2_val
        io.issuePorts.bru.bits.bruOp        := bruOp
        io.issuePorts.bru.bits.rob_id       := rob_id
        io.issuePorts.bru.bits.p_rd         := io.in.bits.p_rd
      case "AGU" =>
        io.issuePorts.agu.valid := can_issue && sel
        io.issuePorts.agu.bits.base      := rs1_val
        io.issuePorts.agu.bits.imm       := imm
        io.issuePorts.agu.bits.lsuOp     := lsuOp
        io.issuePorts.agu.bits.storeData := rs2_val
        io.issuePorts.agu.bits.pc        := pc
        io.issuePorts.agu.bits.rob_id    := rob_id
        io.issuePorts.agu.bits.p_rd      := io.in.bits.p_rd
        io.issuePorts.agu.bits.lsq_id    := io.ls_alloc.lsq_id
      case "MUL" =>
        io.issuePorts.mul.get.valid := can_issue && sel
        io.issuePorts.mul.get.bits.opA    := rs1_val
        io.issuePorts.mul.get.bits.opB    := rs2_val
        io.issuePorts.mul.get.bits.mulOp  := mulOp
        io.issuePorts.mul.get.bits.pc     := pc
        io.issuePorts.mul.get.bits.rob_id := rob_id
        io.issuePorts.mul.get.bits.p_rd   := io.in.bits.p_rd
      case "DIV" =>
        io.issuePorts.div.get.valid := can_issue && sel
        io.issuePorts.div.get.bits.opA    := rs1_val
        io.issuePorts.div.get.bits.opB    := rs2_val
        io.issuePorts.div.get.bits.divOp  := divOp
        io.issuePorts.div.get.bits.pc     := pc
        io.issuePorts.div.get.bits.rob_id := rob_id
        io.issuePorts.div.get.bits.p_rd   := io.in.bits.p_rd
      case "SYSU" =>
        io.issuePorts.sysu.valid := can_issue && sel
        io.issuePorts.sysu.bits.rob_id    := rob_id
        io.issuePorts.sysu.bits.pc        := pc
        io.issuePorts.sysu.bits.p_rd      := io.in.bits.p_rd
        io.issuePorts.sysu.bits.csr_type  := csr_type_sysu
        io.issuePorts.sysu.bits.csr_rdata := csr_rdata
        io.issuePorts.sysu.bits.rs1_val   := rs1_val
        io.issuePorts.sysu.bits.sysuOp    := sysuOp
        io.issuePorts.sysu.bits.imm       := imm
      case _ =>
    }
  }
  io.issuePorts.sysu.bits.rob_id    := rob_id
  io.issuePorts.sysu.bits.pc        := pc
  io.issuePorts.sysu.bits.p_rd      := io.in.bits.p_rd
  io.issuePorts.sysu.bits.csr_type  := csr_type_sysu
  io.issuePorts.sysu.bits.csr_rdata := csr_rdata
  io.issuePorts.sysu.bits.rs1_val   := rs1_val
  io.issuePorts.sysu.bits.sysuOp    := sysuOp
  io.issuePorts.sysu.bits.imm       := imm

  // -------- Back-pressure --------
  io.in.ready := !stall && !csr_write_pending_stall && io.rob_enq.req.ready && can_issue
}
