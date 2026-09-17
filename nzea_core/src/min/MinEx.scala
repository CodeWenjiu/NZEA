package nzea_core.min

import chisel3._
import chisel3.util.{Mux1H, MuxLookup, Valid}
import nzea_rtl.{LiteBusRW, PipeIO}
import nzea_core.backend.MemUnit
import nzea_core.backend.integer._
import nzea_core.backend.integer.nnu.NnOp
import nzea_core.frontend.{AluSrc, CsrFile, CsrType, FuDecode, FuType}
import nzea_core.retire.CommitMsg
import nzea_core.retire.rob.RobMemReq
import nzea_config.core.{CoreConfig, FuConfig, FuKind, PayloadSpec}

/** Commit-stage entry: everything the in-order commit point needs to retire one instruction (ARF write, CSR write,
  * external commit message).
  */
class MinWbEntry(implicit config: CoreConfig) extends Bundle {
  val rd_index = UInt(5.W)
  val data = UInt(32.W)
  val next_pc = UInt(32.W)
  val csr_type = CsrType()
  val csr_data = UInt(32.W)
  val is_mmio = Bool()
  val is_ret = if (PayloadSpec.enabled(PayloadSpec.RetCommit)) Some(Bool()) else None
}

/** Execute + commit stage of the baseline core.
  *
  * Single-flight execution: one instruction is captured on issue (`exEntry`) and the decode stage is frozen (`in.ready
  * := !exValid`) until it completes. Reuses the OoO execution cluster and MemUnit verbatim — Rob/P contain ID state is
  * replaced by (in-order) single-flight + write-back forwarding.
  *
  * Completion timing per FU: ALU/SYSU 2 cycles after issue (cluster issue pipe + comb FU), BRU 4 (3-stage internal pipe
  * + issue pipe), MUL/DIV their own latency, LSU at the bus response. On the completion cycle the entry is latched into
  * `wbEntry` and retired one cycle later (ARF write, CSR write, commit_msg) while the next instruction issues — the wb
  * slot then forwards into the IDU operand read.
  */
class MinEx(mmioRanges: Seq[(BigInt, BigInt)] = Seq.empty)(implicit config: CoreConfig) extends Module {
  private val width = config.width
  private val robIdWidth = chisel3.util.log2Ceil(config.robDepth.max(2))
  private val prfAddrWidth = config.prfAddrWidth
  private val lsqIdWidth = config.lsqIdWidth

  private val dbusType = new LiteBusRW(
    width,
    width,
    width.max(robIdWidth + LsuOp.getWidth + 2 + prfAddrWidth + 1),
    1
  )

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new MinIduOut(width)))
    val commit_msg = Output(Valid(new CommitMsg))
    val redirect = Output(Valid(UInt(width.W)))
    val dbus = dbusType.cloneType
  })

  private val cluster = Module(new IntegerExecutionCluster(robIdWidth, prfAddrWidth, lsqIdWidth))
  private val mem = Module(new MemUnit(width, robIdWidth, prfAddrWidth, mmioRanges))
  private val csr = Module(new CsrFile)
  io.dbus <> mem.io.dbus

  // Elaboration-time topology indices (from the shared FuConfig sources of truth).
  private val exuKinds = FuConfig.exuPrfWritePorts(config)
  private val exuIdx = exuKinds.zipWithIndex.toMap
  private val robPorts = FuConfig.robAccessPorts(config)
  private val robIdx = robPorts.map(_.kind).zipWithIndex.toMap

  // -------- Single-flight entry --------

  val exValid = RegInit(false.B)
  val exBits = Reg(new MinIduOut(width))
  val issue = io.in.valid && io.in.ready
  when(issue) { exBits := io.in.bits }
  val isLsu = exBits.fu_type === FuType.LSU
  val exuUop = exValid && !isLsu

  // Architectural register file, read after issue (single-flight: the previous
  // instruction has committed by then), written on commit. x0 is hardwired 0.
  private val arf = Reg(Vec(32, UInt(32.W)))
  private val rs1Val = Mux(exBits.rs1_index === 0.U, 0.U(32.W), arf(exBits.rs1_index))
  private val rs2Val = Mux(exBits.rs2_index === 0.U, 0.U(32.W), arf(exBits.rs2_index))
  def rs1: UInt = rs1Val
  def rs2: UInt = rs2Val

  // Hardware kind predicates (FuType is a ChiselEnum; literals compare via ===).
  private val isAlu: Bool = exBits.fu_type === FuType.ALU
  private val isBru = exBits.fu_type === FuType.BRU
  private val isAgu = exBits.fu_type === FuType.LSU
  private val isMul = exBits.fu_type === FuType.MUL
  private val isDiv = exBits.fu_type === FuType.DIV
  private val isSysu = exBits.fu_type === FuType.SYSU
  private val isNnu = exBits.fu_type === FuType.NNU

  def kindIs(kind: FuKind): Bool = kind match {
    case FuKind.Alu  => isAlu
    case FuKind.Bru  => isBru
    case FuKind.Agu  => isAgu
    case FuKind.Mul  => isMul
    case FuKind.Div  => isDiv
    case FuKind.Sysu => isSysu
    case FuKind.Nnu  => isNnu
  }

  // AGU is combinational with ready=1, and the cluster issue pipe is transparent
  // while the port holds valid — a level-valid port would keep agu_ls_write firing
  // and re-capture memReq every cycle (duplicate bus requests ⇒ orphan resp).
  // Assert aguIssued on the PORT fire so the port valid lasts exactly one cycle.
  val aguIssued = RegInit(false.B)
  when(issue) { aguIssued := false.B }
  when(cluster.io.issuePorts.agu.fire) { aguIssued := true.B }

  // -------- FU issue-port drive (mirrors ISU operand extraction) --------

  // Single-shot issue: port valid must be a pulse, not a level. A level valid keeps
  // the cluster issue pipe transparent, so pipelined FUs (BRU 3-stage, MUL) ingest a
  // copy of the same uop every cycle and their completion valid spans many cycles —
  // done then fires repeatedly and the instruction commits twice. One global "fired"
  // lock suffices: each uop engages exactly one port (FuTypes are mutually exclusive).
  // Bubbly FUs (DIV) keep their backpressure semantics: no fire ⇒ valid stays high.
  val fuFired = RegInit(false.B)
  when(issue) { fuFired := false.B }
  when(cluster.io.issuePorts.orderedPorts.map(_.fire).reduce(_ || _)) { fuFired := true.B }

  val aluP = cluster.io.issuePorts.alu
  aluP.valid := exuUop && isAlu && !fuFired
  // ALU operand selection (fu_src): opA/opB by source kind, same mapping as IssueAdapters.Alu.
  val (aluSrc0, _) = AluSrc.safe(FuDecode.take(exBits.fu_src, AluSrc.getWidth))

  aluP.bits.opA := MuxLookup(aluSrc0.asUInt, rs1)(
    Seq(AluSrc.ImmZero.asUInt -> exBits.imm, AluSrc.PcImm.asUInt -> exBits.pc)
  )

  aluP.bits.opB := MuxLookup(aluSrc0.asUInt, rs2)(
    Seq(
      AluSrc.Rs1Imm.asUInt -> exBits.imm,
      AluSrc.ImmZero.asUInt -> 0.U(32.W),
      AluSrc.PcImm.asUInt -> exBits.imm
    )
  )

  aluP.bits.aluOp := AluOp.safe(FuDecode.take(exBits.fu_op, AluOp.getWidth))._1
  aluP.bits.pc := exBits.pc
  aluP.bits.rob_id := 0.U
  aluP.bits.p_rd := exBits.rd_index

  val bruP = cluster.io.issuePorts.bru
  bruP.valid := exuUop && isBru && !fuFired
  bruP.bits.pc := exBits.pc
  bruP.bits.pred_next_pc := exBits.pred_next_pc
  bruP.bits.offset := exBits.imm
  bruP.bits.rs1 := rs1
  bruP.bits.rs2 := rs2
  bruP.bits.bruOp := BruOp.safe(FuDecode.take(exBits.fu_op, BruOp.getWidth))._1
  bruP.bits.rob_id := 0.U
  bruP.bits.p_rd := exBits.rd_index
  bruP.bits.rd_index.foreach(_ := exBits.rd_index)
  bruP.bits.is_ret.foreach(_ := exBits.is_ret.getOrElse(false.B))

  val aguP = cluster.io.issuePorts.agu
  aguP.valid := exValid && isAgu && !fuFired
  aguP.bits.base := rs1
  aguP.bits.imm := exBits.imm
  aguP.bits.lsuOp := LsuOp.safe(FuDecode.take(exBits.fu_op, LsuOp.getWidth))._1
  aguP.bits.storeData := rs2
  aguP.bits.pc := exBits.pc
  aguP.bits.rob_id := 0.U
  aguP.bits.p_rd := exBits.rd_index
  aguP.bits.lsq_id := 0.U

  cluster.io.issuePorts.mul.foreach { p =>
    p.valid := exuUop && isMul && !fuFired
    p.bits.opA := rs1
    p.bits.opB := rs2
    p.bits.mulOp := MulOp.safe(FuDecode.take(exBits.fu_op, MulOp.getWidth))._1
    p.bits.pc := exBits.pc
    p.bits.rob_id := 0.U
    p.bits.p_rd := exBits.rd_index
  }

  cluster.io.issuePorts.div.foreach { p =>
    p.valid := exuUop && isDiv && !fuFired
    p.bits.opA := rs1
    p.bits.opB := rs2
    p.bits.divOp := DivOp.safe(FuDecode.take(exBits.fu_op, DivOp.getWidth))._1
    p.bits.pc := exBits.pc
    p.bits.rob_id := 0.U
    p.bits.p_rd := exBits.rd_index
  }

  cluster.io.issuePorts.nnu.foreach { p =>
    p.valid := exuUop && isNnu && !fuFired
    p.bits.nnOp := NnOp.safe(FuDecode.take(exBits.fu_op, NnOp.getWidth))._1
    p.bits.rs1 := rs1
    p.bits.rs2 := rs2
    p.bits.pc := exBits.pc
    p.bits.rob_id := 0.U
    p.bits.p_rd := exBits.rd_index
  }

  val sysuP = cluster.io.issuePorts.sysu
  sysuP.valid := exuUop && isSysu && !fuFired
  sysuP.bits.rob_id := 0.U
  sysuP.bits.pc := exBits.pc
  sysuP.bits.p_rd := exBits.rd_index
  sysuP.bits.csr_type := Mux(isLsu, CsrType.None, CsrType.fromAddr(exBits.csr_addr))
  sysuP.bits.csr_rdata := csr.io.read_data
  sysuP.bits.rs1_val := rs1
  sysuP.bits.sysuOp := SysuOp.safe(FuDecode.take(exBits.fu_op, SysuOp.getWidth))._1
  sysuP.bits.imm := exBits.imm

  csr.io.read_addr := exBits.csr_addr

  // FU write-back ports accept always; fill/flush propagate from the branch flush.
  cluster.io.out.foreach(_.ready := true.B)
  cluster.io.out.foreach(_.flush := io.in.flush)
  cluster.io.agu_ls_write.ready := true.B
  cluster.io.agu_ls_write.flush := io.in.flush

  // LSU request buffer (AGU write captured once; MemUnit is the bus side).

  val lsuOp = LsuOp.safe(FuDecode.take(exBits.fu_op, LsuOp.getWidth))._1
  val isLoad = LsuOp.isLoad(lsuOp)
  val memReqValid = RegInit(false.B)
  val memReqBits = Reg(new RobMemReq(robIdWidth, prfAddrWidth))

  when(io.in.flush) { memReqValid := false.B }.otherwise {
    when(cluster.io.agu_ls_write.fire) {
      memReqValid := true.B
      memReqBits.addr := cluster.io.agu_ls_write.bits.addr
      memReqBits.wdata := cluster.io.agu_ls_write.bits.wdata
      memReqBits.wstrb := cluster.io.agu_ls_write.bits.wstrb
      memReqBits.lsuOp := lsuOp
      memReqBits.rob_id := 0.U
      memReqBits.p_rd := exBits.rd_index
    }.elsewhen(mem.io.mem_req.fire) { memReqValid := false.B }
  }

  mem.io.mem_req.valid := memReqValid
  mem.io.mem_req.bits := memReqBits
  mem.io.out.ready := true.B
  mem.io.out.flush := io.in.flush

  // -------- Completion --------

  val exuDone = exuUop && Mux1H(exuKinds.map(k => kindIs(k) -> cluster.io.out(exuIdx(k)).valid))
  val loadDone = exValid && isLsu && isLoad && mem.io.out.valid
  val storeDone = exValid && isLsu && !isLoad && mem.io.rob_access.valid
  val done = exValid && (exuDone || loadDone || storeDone)

  // Branch mispredict: BRU resolves flush in its S2 rob_access; redirect to its target.
  val bruRob = cluster.io.rob_access(robIdx(FuKind.Bru))
  val flushing = exValid && kindIs(FuKind.Bru) && bruRob.valid && bruRob.bits.flush
  io.in.flush := flushing
  io.redirect.valid := flushing
  io.redirect.bits := cluster.io.bru_bp_update.bits.target

  when(flushing || done) { exValid := false.B }.elsewhen(issue) { exValid := true.B }

  // -------- Write-back entry (latched on completion, retired one cycle later) --------

  val wbValid = RegInit(false.B)
  val wbBits = Reg(new MinWbEntry)
  when(done) { wbValid := true.B }.elsewhen(wbValid) { wbValid := false.B }

  // The commit cycle (wbValid) blocks issue: the ARF write lands on that edge and
  // the following cycle reads the updated value — no combinational forward needed.
  io.in.ready := !exValid && !wbValid

  val sysuRob = cluster.io.rob_access(robIdx(FuKind.Sysu))
  val exuData = Mux1H(exuKinds.map(k => kindIs(k) -> cluster.io.out(exuIdx(k)).bits.data))

  when(done) {
    wbBits.rd_index := exBits.rd_index
    wbBits.data := Mux(isLsu, Mux(isLoad, mem.io.out.bits.data, 0.U(32.W)), exuData)
    wbBits.next_pc := Mux(kindIs(FuKind.Bru), bruRob.bits.next_pc, exBits.pc + 4.U)
    wbBits.csr_type := Mux(kindIs(FuKind.Sysu), sysuRob.bits.csr_type, CsrType.None)
    wbBits.csr_data := Mux(kindIs(FuKind.Sysu), sysuRob.bits.csr_data, 0.U(32.W))
    wbBits.is_mmio := Mux(isLsu, mem.io.rob_access.bits.is_mmio, false.B)
    wbBits.is_ret.foreach(_ := exBits.is_ret.getOrElse(false.B))
  }

  // -------- Commit (wbValid cycle: ARF write-back, CSR write, external message) --------

  when(wbValid && wbBits.rd_index =/= 0.U) { arf(wbBits.rd_index) := wbBits.data }

  csr.io.csr_write.valid := wbValid
  csr.io.csr_write.bits.csr_type := wbBits.csr_type
  csr.io.csr_write.bits.data := wbBits.csr_data

  io.commit_msg.valid := wbValid
  io.commit_msg.bits.next_pc := wbBits.next_pc
  io.commit_msg.bits.rd_index := wbBits.rd_index
  io.commit_msg.bits.rd_value := wbBits.data
  io.commit_msg.bits.is_mmio := wbBits.is_mmio
  io.commit_msg.bits.csr_type := wbBits.csr_type
  io.commit_msg.bits.csr_data := wbBits.csr_data
  io.commit_msg.bits.is_ret.foreach(_ := wbBits.is_ret.getOrElse(false.B))
}
