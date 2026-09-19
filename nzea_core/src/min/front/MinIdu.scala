package nzea_core.min.front

import chisel3._
import chisel3.util.{Cat, Fill, Mux1H, Valid}
import nzea_rtl.PipeIO
import nzea_core.frontend.{DecodeFields, FuDecode, FuType, ImmType, RiscvInsts}
import nzea_core.backend.integer.FuOpWidth
import nzea_config.core.CoreConfig
import nzea_core.PayloadFields

/** Decode output: resolved indexes + decode info, operands NOT read here — the ARF lives in the execute stage and is
  * read after issue (single-flight: the previous instruction has already committed by then, so no hazard and no forward
  * needed).
  */
class MinIduOut(width: Int)(implicit config: CoreConfig) extends Bundle {
  val pc = UInt(32.W)
  val pred_next_pc = UInt(32.W)
  val imm = UInt(32.W)
  val rd_index = UInt(5.W)
  val rs1_index = UInt(5.W)
  val rs2_index = UInt(5.W)
  val fu_type = FuType()
  val fu_op = UInt(FuOpWidth.Width.W)
  val fu_src = UInt(nzea_core.frontend.FuSrcWidth.Width.W)
  val csr_addr = UInt(12.W)
  val is_ret = PayloadFields.retCommit
}

/** Decode only. The register file lives in [[MinMiddle]] (read after issue, written on commit), so decode never sees
  * stale operands; execution happens in [[MinExec]] behind a ready-operand uop.
  */
class MinIdu(width: Int)(implicit config: CoreConfig) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new nzea_core.frontend.IFUOut(width)))
    val out = new PipeIO(new MinIduOut(width))
  })

  io.in.flush := io.out.flush

  // -------- Decode --------

  val decoded = DecodeFields.decodeAll(RiscvInsts.all, io.in.bits.inst, DecodeFields.allWithDefaults)
  val (immType, _) = ImmType.safe(decoded(0))
  val (fuType, _) = FuType.safe(FuDecode.take(decoded(1), FuType.getWidth))
  val fuOp = decoded(2)
  val fuSrc = decoded(3)
  val gprWr = decoded(4).asBool
  val rs1Rd = decoded(5).asBool
  val rs2Rd = decoded(6).asBool

  val rs1_index = Mux(rs1Rd, io.in.bits.inst(19, 15), 0.U(5.W))
  val rs2_index = Mux(rs2Rd, io.in.bits.inst(24, 20), 0.U(5.W))
  val rd_index = Mux(gprWr, io.in.bits.inst(11, 7), 0.U(5.W))

  val inst = io.in.bits.inst
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  val immB = Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  val immU = Cat(inst(31, 12), 0.U(12.W))
  val immJ = Cat(Fill(11, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))
  val immZ = Cat(0.U(27.W), inst(19, 15)) // zimm for CSR
  val imm = Mux1H(immType.asUInt, Seq(immI, immS, immB, immU, immJ, immZ))

  // -------- Operand indexes (values are read in the execute stage) --------

  // RAS classification (commit-chain only): JAL/JALR returning via x1.
  val isJalr = inst(6, 0) === 0x67.U(7.W)
  val isRet = isJalr && inst(19, 15) === 1.U && inst(11, 7) =/= 1.U

  io.out.valid := io.in.valid && io.out.ready
  io.out.bits.pc := io.in.bits.pc
  io.out.bits.pred_next_pc := io.in.bits.pred_next_pc
  io.out.bits.imm := imm
  io.out.bits.rd_index := rd_index
  io.out.bits.rs1_index := rs1_index
  io.out.bits.rs2_index := rs2_index
  io.out.bits.fu_type := fuType
  io.out.bits.fu_op := fuOp
  io.out.bits.fu_src := fuSrc
  io.out.bits.csr_addr := inst(31, 20)
  io.out.bits.is_ret.foreach(_ := isRet)
  io.in.ready := io.out.ready
}
