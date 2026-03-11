package nzea_core.frontend

import chisel3._
import chisel3.util.{Mux1H, MuxLookup, Valid}
import nzea_core.PipeIO
import nzea_config.NzeaConfig
import nzea_core.backend.{AluInput, AluOp, AguInput, BruInput, BruOp, LsuOp, SysuInput}
import nzea_core.retire.rob.{RobEnqIO, RobMemType}

/** PRF write port: addr, data. Shared by all FU completions. */
class PrfWriteBundle(prfAddrWidth: Int) extends Bundle {
  val addr = UInt(prfAddrWidth.W)
  val data = UInt(32.W)
}

/** ISU: Issue Unit. Physical reg file + operand read; dispatches to ALU/BRU/AGU/SYSU.
  *
  * Data flow:
  * - PRF: regs + ready; write from FU completion (prf_write); clear when instr arrives at ISU (p_rd).
  * - prf_write: Vec of size numPrfWritePorts, auto-sized when connecting FUs.
  * - Operand read: PRF(p_rs1), PRF(p_rs2); stall when !ready.
  * - Dispatch: can_dispatch when !stall and rob_enq ready and FU ready.
  */
class ISU(addrWidth: Int, numPrfWritePorts: Int)(implicit config: NzeaConfig) extends Module {
  private val robDepth     = config.robDepth
  private val robIdWidth   = chisel3.util.log2Ceil(robDepth.max(2))
  private val prfAddrWidth = config.prfAddrWidth
  private val prfDepth     = config.prfDepth

  // -------- IO --------

  val io = IO(new Bundle {
    val in             = Flipped(new PipeIO(new IDUOut(addrWidth, prfAddrWidth)))
    val rob_enq        = Flipped(new RobEnqIO(robIdWidth, prfAddrWidth))
    val prf_write      = Input(Vec(numPrfWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    val prf_read_addr  = Input(UInt(prfAddrWidth.W))
    val prf_read_data  = Output(UInt(32.W))
    val alu            = new PipeIO(new AluInput(robIdWidth, prfAddrWidth))
    val bru            = new PipeIO(new BruInput(robIdWidth, prfAddrWidth))
    val agu            = new PipeIO(new AguInput(robIdWidth, prfAddrWidth))
    val sysu           = new PipeIO(new SysuInput(robIdWidth, prfAddrWidth))
  })

  val outs   = Seq(io.alu, io.bru, io.agu, io.sysu)
  val fuTypes = Seq(FuType.ALU, FuType.BRU, FuType.LSU, FuType.SYSU)

  // -------- Physical Register File (banked for timing) --------
  // 4 banks of 16 entries: addr[5:4]=bank, addr[3:0]=index within bank
  private val numBanks  = 4
  private val bankDepth = 16
  require(prfDepth == numBanks * bankDepth, s"prfDepth=$prfDepth must equal numBanks*bankDepth")

  val bank_regs  = RegInit(VecInit(Seq.tabulate(numBanks)(_ => VecInit(Seq.fill(bankDepth)(0.U(32.W))))))
  val bank_ready = RegInit(VecInit(Seq.tabulate(numBanks)(b =>
    VecInit(Seq.tabulate(bankDepth)(i => (b * bankDepth + i) < 32).map(_.B))
  )))

  for (i <- 0 until numPrfWritePorts) {
    when(io.prf_write(i).valid) {
      val addr = io.prf_write(i).bits.addr
      val bank = addr(prfAddrWidth - 1, prfAddrWidth - 2)
      val idx  = addr(prfAddrWidth - 3, 0)
      bank_regs(bank)(idx)  := io.prf_write(i).bits.data
      bank_ready(bank)(idx) := true.B
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

  val (prf_read_val, _) = readPrf(io.prf_read_addr)
  io.prf_read_data := prf_read_val

  val rs1_addr = io.in.bits.p_rs1
  val rs2_addr = io.in.bits.p_rs2
  val (rs1_val, rs1_ready) = readPrf(rs1_addr)
  val (rs2_val, rs2_ready) = readPrf(rs2_addr)
  val stall    = io.in.valid && (!rs1_ready || !rs2_ready)

  // -------- Flush --------

  io.in.flush := outs.map(_.flush).reduce(_ || _)

  // -------- Dispatch --------

  val fu_type = io.in.bits.fu_type
  val fu_src  = io.in.bits.fu_src
  val imm     = io.in.bits.imm
  val pc      = io.in.bits.pc
  val rob_id  = io.rob_enq.rob_id
  val can_dispatch = io.in.valid && !stall
  val can_enq = can_dispatch && io.in.ready

  io.rob_enq.req.valid := can_enq
  io.rob_enq.req.bits.rd_index := Mux(fu_type === FuType.SYSU, 0.U(5.W), io.in.bits.rd_index)
  io.rob_enq.req.bits.might_flush := (fu_type === FuType.BRU)
  val (lsuOp, _) = LsuOp.safe(FuDecode.take(io.in.bits.fu_op, LsuOp.getWidth))
  io.rob_enq.req.bits.mem_type := Mux(
    fu_type === FuType.LSU,
    Mux(LsuOp.isLoad(lsuOp), RobMemType.Load, RobMemType.Store),
    RobMemType.None
  )
  io.rob_enq.req.bits.p_rd := io.in.bits.p_rd
  io.rob_enq.req.bits.old_p_rd := io.in.bits.old_p_rd

  // -------- FU Outputs --------

  io.alu.valid := can_dispatch && (fu_type === FuType.ALU)
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
  val aluOp = AluOp.safe(FuDecode.take(io.in.bits.fu_op, AluOp.getWidth))._1
  io.alu.bits.opA    := aluOpA
  io.alu.bits.opB    := aluOpB
  io.alu.bits.aluOp  := aluOp
  io.alu.bits.pc     := pc
  io.alu.bits.rob_id := rob_id
  io.alu.bits.p_rd   := io.in.bits.p_rd

  io.bru.valid := can_dispatch && (fu_type === FuType.BRU)
  val (bruOp, _) = BruOp.safe(FuDecode.take(io.in.bits.fu_op, BruOp.getWidth))
  io.bru.bits.pc           := pc
  io.bru.bits.pred_next_pc := io.in.bits.pred_next_pc
  io.bru.bits.offset       := imm
  io.bru.bits.rs1          := rs1_val
  io.bru.bits.rs2          := rs2_val
  io.bru.bits.bruOp        := bruOp
  io.bru.bits.rob_id       := rob_id
  io.bru.bits.p_rd         := io.in.bits.p_rd

  io.agu.valid := can_dispatch && (fu_type === FuType.LSU)
  io.agu.bits.base      := rs1_val
  io.agu.bits.imm       := imm
  io.agu.bits.lsuOp     := lsuOp
  io.agu.bits.storeData := rs2_val
  io.agu.bits.pc        := pc
  io.agu.bits.rob_id    := rob_id
  io.agu.bits.p_rd      := io.in.bits.p_rd

  io.sysu.valid := can_dispatch && (fu_type === FuType.SYSU)
  io.sysu.bits.rob_id := rob_id
  io.sysu.bits.pc     := pc
  io.sysu.bits.p_rd   := io.in.bits.p_rd

  // -------- Back-pressure --------

  io.in.ready := !stall && io.rob_enq.req.ready && Mux1H(fuTypes.map(_ === fu_type), outs.map(_.ready))
}
