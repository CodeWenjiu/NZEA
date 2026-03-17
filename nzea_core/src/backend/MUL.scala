package nzea_core.backend

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

/** Stage 1 output: 18 partial products (17 Booth + 1 compensation) + passthrough. */
class MulS1Out(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val pp     = Vec(18, UInt(64.W))
  val mulOp  = MulOp()
  val rob_id = UInt(robIdWidth.W)
  val p_rd   = UInt(prfAddrWidth.W)
  val pc     = UInt(32.W)
}

/** Stage 2 output: sum + carry + passthrough. */
class MulS2Out(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val sum    = UInt(64.W)
  val carry  = UInt(64.W)
  val mulOp  = MulOp()
  val rob_id = UInt(robIdWidth.W)
  val p_rd   = UInt(prfAddrWidth.W)
  val pc     = UInt(32.W)
}

/** Radix-4 Booth encoding: 3 bits (b2,b1,b0) -> 0, ±A, ±2A.
  * a34: 34-bit multiplicand (sign-extended for signed mul). Returns (raw_34, is_neg).
  * For neg: returns ~value only; +1 is deferred to compensation vector (avoids 17 adders in Stage 0).
  */
object BoothRadix4 {
  def encode(b: UInt, a34: UInt): (UInt, Bool) = {
    val a2  = (a34 << 1.U)(33, 0)
    val neg = (b === "b100".U) || (b === "b101".U) || (b === "b110".U)
    val sel = MuxCase(0.U(3.W), Seq(
      (b === "b001".U || b === "b010".U) -> 1.U,
      (b === "b011".U) -> 2.U,
      (b === "b100".U) -> 3.U,
      (b === "b101".U || b === "b110".U) -> 4.U
    ))
    val posVal = Mux(sel === 1.U, a34, Mux(sel === 2.U, a2, 0.U(34.W)))
    val negVal = Mux(sel === 3.U, (~a2).asUInt, Mux(sel === 4.U, (~a34).asUInt, 0.U(34.W)))
    val raw = Mux(neg, negVal, posVal)
    (raw, neg && (sel =/= 0.U))
  }
}

/** Wallace tree: reduce 17 partial products (64-bit each) to sum + carry. */
object WallaceTree {
  def fa64(a: UInt, b: UInt, c: UInt): (UInt, UInt) = {
    val sum   = a ^ b ^ c
    val carry = (a & b) | (a & c) | (b & c)
    (sum, Cat(carry(62, 0), 0.U(1.W)))
  }
  def ha64(a: UInt, b: UInt): (UInt, UInt) = {
    val sum   = a ^ b
    val carry = a & b
    (sum, Cat(carry(62, 0), 0.U(1.W)))
  }
  def reduce(pp: Seq[UInt]): (UInt, UInt) = {
    require(pp.nonEmpty)
    val w = 64
    var rows = pp.toVector
    while (rows.size > 2) {
      val nextRows = scala.collection.mutable.ArrayBuffer[UInt]()
      var i = 0
      while (i + 2 < rows.size) {
        val (s, c) = fa64(rows(i), rows(i+1), rows(i+2))
        nextRows += s
        nextRows += c
        i += 3
      }
      if (i + 1 < rows.size) {
        val (s, c) = ha64(rows(i), rows(i+1))
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

/** Stage 0: Radix-4 Booth partial product generation. */
class MULStage0(robIdWidth: Int, prfAddrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(new PipeIO(new MulInput(robIdWidth, prfAddrWidth)))
    val out = new PipeIO(new MulS1Out(robIdWidth, prfAddrWidth))
  })

  val b = io.in.bits
  val aSigned = (b.mulOp === MulOp.Mulh) || (b.mulOp === MulOp.Mulhsu)
  val bSigned = (b.mulOp === MulOp.Mulh)
  val aExt = Mux(aSigned, Cat(Fill(32, b.opA(31)), b.opA), Cat(0.U(32.W), b.opA))
  val bExt = Mux(bSigned, Cat(Fill(32, b.opB(31)), b.opB), Cat(0.U(32.W), b.opB))
  val a34 = aExt(33, 0)
  val b34 = bExt(33, 0)

  val pp = Wire(Vec(18, UInt(64.W)))
  val negFlags = Wire(Vec(17, Bool()))
  val zeroProduct = (b.opA === 0.U) || (b.opB === 0.U)
  for (i <- 0 until 17) {
    val b2 = if (i < 16) b34(2*i + 1) else b34(33)
    val b1 = b34(2*i)
    val b0 = if (i == 0) 0.U else b34(2*i - 1)
    val (raw, neg) = BoothRadix4.encode(Cat(b2, b1, b0), a34)
    pp(i) := Mux(zeroProduct, 0.U, Cat(Fill(30, neg), raw) << (2 * i))
    negFlags(i) := neg
  }
  val compensation = (0 until 17).map(i => Mux(negFlags(i), 1.U(64.W) << (2 * i), 0.U(64.W))).reduce(_ | _)
  pp(17) := Mux(zeroProduct, 0.U, compensation)

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
  val io = IO(new Bundle {
    val in  = Flipped(new PipeIO(new MulS1Out(robIdWidth, prfAddrWidth)))
    val out = new PipeIO(new MulS2Out(robIdWidth, prfAddrWidth))
  })

  val (sum, carry) = WallaceTree.reduce(io.in.bits.pp.toSeq)

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
    val rob_access = new nzea_core.retire.rob.RobAccessIO(robIdWidth)
    val out  = new nzea_core.PipeIO(new PrfWriteBundle(prfAddrWidth))
  })

  val b = io.in.bits
  val product64 = b.sum + b.carry
  val mul    = product64(31, 0)
  val mulh   = product64(63, 32)
  val mulhsu = product64(63, 32)
  val mulhu  = product64(63, 32)
  val result = Mux1H(b.mulOp.asUInt, Seq(mul, mulh, mulhsu, mulhu))

  val next_pc = b.pc + 4.U
  val u = Rob.entryStateUpdate(io.in.valid, b.rob_id, is_done = true.B, next_pc = next_pc)(robIdWidth)
  io.rob_access <> u
  io.out.valid := u.valid && b.p_rd =/= 0.U
  io.out.bits.addr := b.p_rd
  io.out.bits.data := result
  io.in.ready := io.out.ready
  io.in.flush := io.flush
}

/** Common IO bundle for MUL/NullMUL so EXU can use if/else without losing type. */
class MulIO(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val in         = Flipped(new PipeIO(new MulInput(robIdWidth, prfAddrWidth)))
  val rob_access = new nzea_core.retire.rob.RobAccessIO(robIdWidth)
  val out  = new nzea_core.PipeIO(new PrfWriteBundle(prfAddrWidth))
}

/** Common interface for MUL/NullMUL so EXU can use if/else without losing .io type. */
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
