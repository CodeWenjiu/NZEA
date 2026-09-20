package nzea_core.min.front

import chisel3._
import chisel3.util.{Cat, Fill, Mux1H, Valid}
import nzea_rtl.PipeIO
import nzea_core.frontend.{DecodeFields, FuDecode, FuType, ImmType, RiscvInsts}
import nzea_core.backend.integer.FuOpWidth
import nzea_config.core.CoreConfig

/** Decode output: resolved indexes + decode info. Operand *values* are read here (ARF lives in the fetch stage):
  * with no register renaming the architectural indexes need no processing, and the front-end capture cycle
  * (`front.io.out` fires only while the middle is idle) reads exactly the post-commit values — equivalent to the
  * delayed read, but one stage earlier. Renaming later moves the read into a new middle stage (RAT + PRF) and the
  * payload reverts to index form; that swap is an engine variant, not a config switch.
  */
class MinIduOut(width: Int)(implicit config: CoreConfig) extends Bundle {
  val pc = UInt(32.W)
  val imm = UInt(32.W)
  val rd_index = UInt(5.W)
  val rs1_val = UInt(32.W)
  val rs2_val = UInt(32.W)
  val fu_type = FuType()
  val fu_op = UInt(FuOpWidth.Width.W)
  val fu_src = UInt(nzea_core.frontend.FuSrcWidth.Width.W)
  val csr_addr = UInt(12.W)
}

/** Decode only. The architectural register file lives here (read after decode — see [[MinIduOut]]), its write-back
  * arrives from the middle commit point over the bus; execution happens in [[MinExec]] behind a ready-operand uop.
  */
class MinIdu(width: Int)(implicit config: CoreConfig) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new nzea_core.frontend.IFUOut(width)))
    val out = new PipeIO(new MinIduOut(width))
    val arf_write = Flipped(nzea_core.min.CoreChannels.arfWrite)
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

  // Architectural register file (fetch stage): combinational read at decode, written by the middle commit point.
  // x0 is hardwired 0. Read timing equals the delayed read (the front-end capture cycle only fires while the middle
  // is idle, i.e. after the previous commit's write landed), so no hazard and no forward.
  private val arf = Reg(Vec(32, UInt(32.W)))
  when(io.arf_write.valid && io.arf_write.bits.rd_index =/= 0.U) {
    arf(io.arf_write.bits.rd_index) := io.arf_write.bits.data
  }

  val inst = io.in.bits.inst
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  val immB = Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  val immU = Cat(inst(31, 12), 0.U(12.W))
  val immJ = Cat(Fill(11, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))
  val immZ = Cat(0.U(27.W), inst(19, 15)) // zimm for CSR
  val imm = Mux1H(immType.asUInt, Seq(immI, immS, immB, immU, immJ, immZ))

  // -------- Operand indexes (values are read in the execute stage) --------

  io.out.valid := io.in.valid && io.out.ready
  io.out.bits.pc := io.in.bits.pc
  io.out.bits.imm := imm
  io.out.bits.rd_index := rd_index
  io.out.bits.rs1_val := Mux(rs1_index === 0.U, 0.U(32.W), arf(rs1_index))
  io.out.bits.rs2_val := Mux(rs2_index === 0.U, 0.U(32.W), arf(rs2_index))
  io.out.bits.fu_type := fuType
  io.out.bits.fu_op := fuOp
  io.out.bits.fu_src := fuSrc
  io.out.bits.csr_addr := inst(31, 20)
  io.in.ready := io.out.ready
}
