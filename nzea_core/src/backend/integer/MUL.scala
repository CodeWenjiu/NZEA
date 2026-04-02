package nzea_core.backend.integer

import chisel3._
import chisel3.util.{Cat, Fill, Mux1H, MuxCase, Valid}
import nzea_core.{PipeIO, PipelineConnect}
import nzea_core.frontend.PrfWriteBundle
import nzea_core.retire.rob.Rob

/** MUL FU op: MUL, MULH, MULHSU, MULHU (M extension). */
object MulOp extends chisel3.ChiselEnum {
  val Mul    = Value((1 << 0).U)
  val Mulh   = Value((1 << 1).U)
  val Mulhsu = Value((1 << 2).U)
  val Mulhu  = Value((1 << 3).U)
}

/** MUL FU input: operands, mul op; rob_id, p_rd from IS. */
class MulInput(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val opA    = UInt(32.W)
  val opB    = UInt(32.W)
  val mulOp  = MulOp()
  val pc     = UInt(32.W)
  val rob_id = UInt(robIdWidth.W)
  val p_rd   = UInt(prfAddrWidth.W)
}

/** Stage 1 output: 17 radix-4 Booth partial products (64b: each lane ≡ true lane mod 2^64) + passthrough. */
class MulS1Out(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val pp     = Vec(17, UInt(64.W))
  val mulOp  = MulOp()
  val rob_id = UInt(robIdWidth.W)
  val p_rd   = UInt(prfAddrWidth.W)
  val pc     = UInt(32.W)
}

/** Stage 2 output: sum + carry + passthrough (64b CSA result). */
class MulS2Out(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val sum    = UInt(64.W)
  val carry  = UInt(64.W)
  val mulOp  = MulOp()
  val rob_id = UInt(robIdWidth.W)
  val p_rd   = UInt(prfAddrWidth.W)
  val pc     = UInt(32.W)
}

/** Radix-4 modified Booth: 3-bit digit (b2,b1,b0) → signed multiple {-2A,-A,0,+A,+2A} of sign/zero-extended 34b multiplicand. */
object BoothRadix4 {
  def signedPartial(b: UInt, a34: UInt): SInt = {
    val a  = a34.asSInt
    val a2 = (a << 1).asSInt
    MuxCase(
      0.S(40.W),
      Seq(
        (b === "b000".U || b === "b111".U) -> 0.S(40.W),
        (b === "b001".U || b === "b010".U) -> a.pad(40),
        (b === "b011".U)                  -> a2.pad(40),
        (b === "b100".U)                  -> (-a2).pad(40),
        (b === "b101".U || b === "b110".U) -> (-a).pad(40)
      )
    )
  }
}

/** Wallace tree: reduce partial products of width [[w]] to sum + carry. */
object WallaceTree {
  def fa(w: Int, a: UInt, b: UInt, c: UInt): (UInt, UInt) = {
    val sum   = a ^ b ^ c
    val carry = (a & b) | (a & c) | (b & c)
    (sum, Cat(carry(w - 2, 0), 0.U(1.W)))
  }
  def ha(w: Int, a: UInt, b: UInt): (UInt, UInt) = {
    val sum   = a ^ b
    val carry = a & b
    (sum, Cat(carry(w - 2, 0), 0.U(1.W)))
  }
  def reduce(w: Int, pp: Seq[UInt]): (UInt, UInt) = {
    require(pp.nonEmpty)
    var rows = pp.toVector
    while (rows.size > 2) {
      val nextRows = scala.collection.mutable.ArrayBuffer[UInt]()
      var i = 0
      while (i + 2 < rows.size) {
        val (s, c) = fa(w, rows(i), rows(i + 1), rows(i + 2))
        nextRows += s
        nextRows += c
        i += 3
      }
      if (i + 1 < rows.size) {
        val (s, c) = ha(w, rows(i), rows(i + 1))
        nextRows += s
        nextRows += c
        i += 2
      } else if (i < rows.size) {
        nextRows += rows(i)
      }
      rows = nextRows.toVector
    }
    if (rows.size == 1) (rows(0), 0.U(w.W))
    else (rows(0), rows(1))
  }
}

/** Stage 0: Radix-4 Booth partial product generation.
  * Each row is ((signedPartial << 2i) mod 2^64); sum of rows mod 2^64 equals the 32×32 product (low 64b).
  */
class MULStage0(robIdWidth: Int, prfAddrWidth: Int) extends Module {
  private val ppW = 64

  val io = IO(new Bundle {
    val in  = Flipped(new PipeIO(new MulInput(robIdWidth, prfAddrWidth)))
    val out = new PipeIO(new MulS1Out(robIdWidth, prfAddrWidth))
  })

  val b = io.in.bits
  // RV32M: MUL/MULH signed×signed; MULHSU signed rs1 × unsigned rs2; MULHU unsigned×unsigned
  val aSigned = (b.mulOp === MulOp.Mul) || (b.mulOp === MulOp.Mulh) || (b.mulOp === MulOp.Mulhsu)
  val bSigned = (b.mulOp === MulOp.Mul) || (b.mulOp === MulOp.Mulh)
  val aExt = Mux(aSigned, Cat(Fill(32, b.opA(31)), b.opA), Cat(0.U(32.W), b.opA))
  val bExt = Mux(bSigned, Cat(Fill(32, b.opB(31)), b.opB), Cat(0.U(32.W), b.opB))
  val a34 = aExt(33, 0)
  val b34 = bExt(33, 0)

  val pp = Wire(Vec(17, UInt(ppW.W)))
  for (i <- 0 until 17) {
    val b0 = if (i != 0) b34(2 * i - 1) else 0.U
    val b1 = b34(2 * i)
    val b2 = b34(2 * i + 1)
    val partial = BoothRadix4.signedPartial(Cat(b2, b1, b0), a34)
    val s64     = partial.pad(ppW).asSInt
    pp(i) := (s64 << (2 * i).U)(ppW - 1, 0).asUInt
  }

  io.out.valid := io.in.valid
  io.out.bits.pp := pp
  io.out.bits.mulOp := b.mulOp
  io.out.bits.rob_id := b.rob_id
  io.out.bits.p_rd := b.p_rd
  io.out.bits.pc := b.pc
  io.in.ready := io.out.ready
  io.in.flush := io.out.flush
}

/** Stage 1: Wallace tree compression. */
class MULStage1(robIdWidth: Int, prfAddrWidth: Int) extends Module {
  private val w = 64

  val io = IO(new Bundle {
    val in  = Flipped(new PipeIO(new MulS1Out(robIdWidth, prfAddrWidth)))
    val out = new PipeIO(new MulS2Out(robIdWidth, prfAddrWidth))
  })

  val (sum, carry) = WallaceTree.reduce(w, io.in.bits.pp.toSeq)

  io.out.valid := io.in.valid
  io.out.bits.sum := sum
  io.out.bits.carry := carry
  io.out.bits.mulOp := io.in.bits.mulOp
  io.out.bits.rob_id := io.in.bits.rob_id
  io.out.bits.p_rd := io.in.bits.p_rd
  io.out.bits.pc := io.in.bits.pc
  io.in.ready := io.out.ready
  io.in.flush := io.out.flush
}

/** Stage 2: Final CPA, output to ROB and PRF. */
class MULStage2(robIdWidth: Int, prfAddrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new MulS2Out(robIdWidth, prfAddrWidth)))
    val flush      = Input(Bool())
    val rob_access = Output(Valid(new nzea_core.retire.rob.RobEntryStateUpdate(robIdWidth)))
    val out        = new PipeIO(new PrfWriteBundle(prfAddrWidth))
  })

  val b = io.in.bits
  val product64 = b.sum + b.carry // mod 2^64 (32×32 product fits in 64b)
  val mul       = product64(31, 0)
  val mulh      = product64(63, 32)
  val mulhsu    = product64(63, 32)
  val mulhu     = product64(63, 32)
  val result    = Mux1H(b.mulOp.asUInt, Seq(mul, mulh, mulhsu, mulhu))

  val next_pc = b.pc + 4.U
  val u       = Rob.entryStateUpdate(io.in.valid, b.rob_id, is_done = true.B, next_pc = next_pc)(robIdWidth)
  io.rob_access <> u
  io.out.valid := u.valid
  io.out.bits.addr := b.p_rd
  io.out.bits.data := result
  io.in.ready := io.out.ready
  io.in.flush := io.flush
}

/** Common IO bundle for MUL/NullMUL so IntegerExecutionCluster can use if/else without losing type. */
class MulIO(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val in         = Flipped(new PipeIO(new MulInput(robIdWidth, prfAddrWidth)))
  val rob_access = Output(Valid(new nzea_core.retire.rob.RobEntryStateUpdate(robIdWidth)))
  val out        = new PipeIO(new PrfWriteBundle(prfAddrWidth))
}

/** Common interface for MUL/NullMUL so IntegerExecutionCluster can use if/else without losing .io type. */
trait MulLike extends Module {
  def mulIo: MulIO
}

/** 3-Stage Pipelined Multiplier: Booth -> Wallace -> CPA. Uses PipelineConnect for automatic pipeline management. */
class MUL(robIdWidth: Int, prfAddrWidth: Int) extends Module with MulLike {
  val io = IO(new MulIO(robIdWidth, prfAddrWidth))

  val s0 = Module(new MULStage0(robIdWidth, prfAddrWidth))
  val s1 = Module(new MULStage1(robIdWidth, prfAddrWidth))
  val s2 = Module(new MULStage2(robIdWidth, prfAddrWidth))

  io.in <> s0.io.in
  io.in.flush := io.out.flush
  s2.io.flush := io.out.flush
  PipelineConnect(s0.io.out, s1.io.in)
  PipelineConnect(s1.io.out, s2.io.in)
  io.rob_access <> s2.io.rob_access
  io.out.valid := s2.io.out.valid
  io.out.bits := s2.io.out.bits
  s2.io.out.ready := io.out.ready
  s2.io.out.flush := io.out.flush
  def mulIo = io
}

/** Null MUL: same interface as MUL but does nothing. Used when isaConfig.hasM is false. */
class NullMUL(robIdWidth: Int, prfAddrWidth: Int) extends Module with MulLike {
  val io = IO(new MulIO(robIdWidth, prfAddrWidth))
  io.in.ready := true.B
  io.in.flush := io.out.flush
  io.rob_access.valid := false.B
  io.rob_access.bits := DontCare
  io.out.valid := false.B
  io.out.bits := DontCare
  def mulIo = io
}
