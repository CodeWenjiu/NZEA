package nzea_core.back

import chisel3._
import chisel3.util.{Cat, Mux1H, Valid}
import nzea_rtl.PipeIO
import nzea_core.front.PrfWriteBundle
import nzea_core.back.FuCompletion

/** DIV FU op: DIV, DIVU, REM, REMU (M extension). */
object DivOp extends chisel3.ChiselEnum {
  val Div = Value((1 << 0).U)
  val Divu = Value((1 << 1).U)
  val Rem = Value((1 << 2).U)
  val Remu = Value((1 << 3).U)
}

/** DIV FU input: operands, div op; tag, waddr from IS. */
class DivInput(tagWidth: Int, waddrWidth: Int) extends Bundle {
  val opA = UInt(32.W)
  val opB = UInt(32.W)
  val divOp = DivOp()
  val pc = UInt(32.W)
  val tag = UInt(tagWidth.W)
  val waddr = UInt(waddrWidth.W)
}

/** Common IO bundle for DIV: input pipeline + ROB completion + write-back. */
class DivIO(tagWidth: Int, waddrWidth: Int) extends Bundle {
  val in = Flipped(new PipeIO(new DivInput(tagWidth, waddrWidth)))
  val completion = Output(Valid(new FuCompletion(tagWidth)))
  val out = new nzea_rtl.PipeIO(new PrfWriteBundle(waddrWidth))
}

/** Multi-cycle Radix-2 Restoring Divider. FSM: IDLE -> CALC (32 iter) -> DONE. RQ[63:32]=remainder, RQ[31:0]=quotient.
  * Each cycle: shift RQ left; if rem>=div then rem-=div, quo|=1.
  *
  * A single-entry input buffer lets [[io.in.ready]] be `!buf_valid && (state === sIdle)` so flush and `io.out.ready`
  * are not combined on the input-facing ready net. Trade-off: one extra cycle from issue handshake to consuming the
  * op from `buf` when the buffer was empty.
  */
class DIV(tagWidth: Int, waddrWidth: Int) extends Module {
  val io = IO(new DivIO(tagWidth, waddrWidth))

  val sIdle :: sCalc :: sDone :: Nil = chisel3.util.Enum(3)
  val state = RegInit(sIdle)
  val count = Reg(UInt(5.W)) // 0..31
  val rq = Reg(UInt(64.W)) // RQ[63:32]=rem, RQ[31:0]=quo
  val divReg = Reg(UInt(32.W))
  val isQuoNeg = Reg(Bool())
  val isRemNeg = Reg(Bool())
  val divOpReg = Reg(DivOp())
  val tagReg = Reg(UInt(tagWidth.W))
  val waddrReg = Reg(UInt(waddrWidth.W))
  val pcReg = Reg(UInt(32.W))
  val resultReg = Reg(UInt(32.W))

  val buf_valid = RegInit(false.B)
  val buf_bits = Reg(new DivInput(tagWidth, waddrWidth))

  /** Input-facing ready: only `state` + `buf_valid`. */
  io.in.ready := !buf_valid && (state === sIdle)

  def abs(x: UInt): UInt = Mux(x(31), (-x.asSInt).asUInt, x)

  // Fast path: RISC-V-specified constants only; no / or % (avoids combinational divider in synthesis)
  def fastPathResult(opA: UInt, opB: UInt, divOp: DivOp.Type): UInt = {
    val dz = opB === 0.U
    val ov = opA === "h80000000".U && opB === "hffffffff".U
    Mux(
      dz,
      Mux1H(divOp.asUInt, Seq("hffffffff".U, "hffffffff".U, opA, opA)),
      Mux1H(divOp.asUInt, Seq("h80000000".U, "hffffffff".U, 0.U(32.W), opA))
    )
  }

  val canIssueFromBuf = state === sIdle && buf_valid && io.out.ready && !io.out.flush
  val b = buf_bits
  val opA = b.opA
  val opB = b.opB
  val divOp = b.divOp
  val isSigned = divOp === DivOp.Div || divOp === DivOp.Rem
  val divByZero = opB === 0.U
  val divOverflow = opA === "h80000000".U && opB === "hffffffff".U
  val absA = abs(opA)
  val absB = abs(opB)
  val quoSign = opA(31) ^ opB(31)
  val remSign = opA(31)

  when(io.out.flush) {
    state := sIdle
    buf_valid := false.B
  }.elsewhen(state === sCalc) {
    val rqShifted = rq << 1
    val remHi = rqShifted(63, 32)
    val ge = remHi >= divReg
    val newRem = Mux(ge, remHi - divReg, remHi)
    val newRq = Cat(newRem, rqShifted(31, 1), Mux(ge, 1.U(1.W), 0.U(1.W)))
    rq := newRq
    count := count + 1.U
    when(count === 31.U) {
      val quoAbs = newRq(31, 0)
      val remAbs = newRq(63, 32)
      resultReg := Mux1H(
        divOpReg.asUInt,
        Seq(
          Mux(isQuoNeg, (-quoAbs.asSInt).asUInt, quoAbs),
          quoAbs,
          Mux(isRemNeg, (-remAbs.asSInt).asUInt, remAbs),
          remAbs
        )
      )
      state := sDone
    }
  }.elsewhen(state === sDone) {
    when(io.out.ready) { state := sIdle }
  }.elsewhen(canIssueFromBuf) {
    isQuoNeg := isSigned && quoSign
    isRemNeg := isSigned && remSign
    divOpReg := divOp
    tagReg := b.tag
    waddrReg := b.waddr
    pcReg := b.pc
    divReg := Mux(isSigned, absB, opB)
    buf_valid := false.B
    when(divByZero) {
      resultReg := fastPathResult(opA, opB, divOp)
      state := sDone
    }.elsewhen(divOverflow && isSigned) {
      resultReg := fastPathResult(opA, opB, divOp)
      state := sDone
    }.otherwise {
      rq := Cat(0.U(32.W), Mux(isSigned, absA, opA))
      count := 0.U
      state := sCalc
    }
  }.elsewhen(io.in.fire) {
    buf_bits := io.in.bits
    buf_valid := true.B
  }

  val doneValid = state === sDone
  val nextPc = pcReg + 4.U
  val u = FuCompletion.report(doneValid, tagReg, is_done = true.B, next_pc = nextPc)(tagWidth)
  io.completion <> u

  io.out.valid := doneValid && waddrReg =/= 0.U
  io.out.bits.waddr := waddrReg
  io.out.bits.data := resultReg
  io.in.flush := io.out.flush
}
