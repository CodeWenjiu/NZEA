package nzea_core.backend

import chisel3._
import chisel3.util.{Cat, Enum, Mux1H, Valid}
import nzea_core.PipeIO
import nzea_core.frontend.PrfWriteBundle
import nzea_core.retire.rob.Rob

/** DIV FU op: DIV, DIVU, REM, REMU (M extension). */
object DivOp extends chisel3.ChiselEnum {
  val Div  = Value((1 << 0).U)
  val Divu = Value((1 << 1).U)
  val Rem  = Value((1 << 2).U)
  val Remu = Value((1 << 3).U)
}

/** DIV FU input: operands, div op; rob_id, p_rd from IS. */
class DivInput(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val opA    = UInt(32.W)
  val opB    = UInt(32.W)
  val divOp  = DivOp()
  val pc     = UInt(32.W)
  val rob_id = UInt(robIdWidth.W)
  val p_rd   = UInt(prfAddrWidth.W)
}

/** Common IO bundle for DIV/NullDIV so EXU can use if/else without losing type. */
class DivIO(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val in         = Flipped(new PipeIO(new DivInput(robIdWidth, prfAddrWidth)))
  val rob_access = new nzea_core.retire.rob.RobAccessIO(robIdWidth)
  val out  = new nzea_core.PipeIO(new PrfWriteBundle(prfAddrWidth))
}

/** Common interface for DIV/NullDIV so EXU can use if/else without losing .io type. */
trait DivLike extends Module {
  def divIo: DivIO
}

/** Multi-cycle Radix-2 Restoring Divider. FSM: IDLE -> CALC (32 iter) -> DONE.
  * RQ[63:32]=remainder, RQ[31:0]=quotient. Each cycle: shift RQ left; if rem>=div then rem-=div, quo|=1.
  */
class DIV(robIdWidth: Int, prfAddrWidth: Int) extends Module with DivLike {
  val io = IO(new DivIO(robIdWidth, prfAddrWidth))

  val sIdle :: sCalc :: sDone :: Nil = chisel3.util.Enum(3)
  val state = RegInit(sIdle)
  val count = Reg(UInt(5.W))  // 0..31
  val rq    = Reg(UInt(64.W))  // RQ[63:32]=rem, RQ[31:0]=quo
  val divReg = Reg(UInt(32.W))
  val isQuoNeg = Reg(Bool())
  val isRemNeg = Reg(Bool())
  val divOpReg = Reg(DivOp())
  val robIdReg = Reg(UInt(robIdWidth.W))
  val pRdReg   = Reg(UInt(prfAddrWidth.W))
  val pcReg    = Reg(UInt(32.W))
  val resultReg = Reg(UInt(32.W))

  val opA = io.in.bits.opA
  val opB = io.in.bits.opB
  val divOp = io.in.bits.divOp
  val isSigned = divOp === DivOp.Div || divOp === DivOp.Rem

  val divByZero = opB === 0.U
  val divOverflow = opA === "h80000000".U && opB === "hffffffff".U
  // Fast path: RISC-V-specified constants only; no / or % (avoids combinational divider in synthesis)
  def fastPathResult(divOp: DivOp.Type): UInt = Mux(divByZero,
    Mux1H(divOp.asUInt, Seq("hffffffff".U, "hffffffff".U, opA, opA)),  // DIV/DIVU quo, REM/REMU rem
    Mux1H(divOp.asUInt, Seq("h80000000".U, "hffffffff".U, 0.U(32.W), opA)))  // overflow: DIV quo, REM rem

  def abs(x: UInt): UInt = Mux(x(31), (-x.asSInt).asUInt, x)
  val absA = abs(opA)
  val absB = abs(opB)
  val quoSign = opA(31) ^ opB(31)
  val remSign = opA(31)

  val fire = io.in.valid && io.in.ready

  io.in.ready := (state === sIdle) && !io.out.flush && io.out.ready

  when(io.out.flush) {
    state := sIdle
  }.elsewhen(state === sIdle) {
    when(fire) {
      isQuoNeg := isSigned && quoSign
      isRemNeg := isSigned && remSign
      divOpReg := divOp
      robIdReg := io.in.bits.rob_id
      pRdReg   := io.in.bits.p_rd
      pcReg    := io.in.bits.pc
      divReg   := Mux(isSigned, absB, opB)

      when(divByZero) {
        resultReg := fastPathResult(divOp)
        state := sDone
      }.elsewhen(divOverflow && isSigned) {
        resultReg := fastPathResult(divOp)
        state := sDone
      }.otherwise {
        rq    := Cat(0.U(32.W), Mux(isSigned, absA, opA))
        count := 0.U
        state := sCalc
      }
    }
  }.elsewhen(state === sCalc) {
    val rqShifted = rq << 1
    val remHi = rqShifted(63, 32)  // partial remainder after shift
    val ge = remHi >= divReg
    val newRem = Mux(ge, remHi - divReg, remHi)
    val newRq = Cat(newRem, rqShifted(31, 1), Mux(ge, 1.U(1.W), 0.U(1.W)))
    rq := newRq
    count := count + 1.U
    when(count === 31.U) {
      val quoAbs = newRq(31, 0)
      val remAbs = newRq(63, 32)
      resultReg := Mux1H(divOpReg.asUInt, Seq(
        Mux(isQuoNeg, (-quoAbs.asSInt).asUInt, quoAbs),
        quoAbs,
        Mux(isRemNeg, (-remAbs.asSInt).asUInt, remAbs),
        remAbs
      ))
      state := sDone
    }
  }.elsewhen(state === sDone) {
    when(io.out.ready) { state := sIdle }
  }

  val doneValid = state === sDone
  val nextPc = pcReg + 4.U
  val u = Rob.entryStateUpdate(doneValid, robIdReg, is_done = true.B, next_pc = nextPc)(robIdWidth)
  io.rob_access <> u

  io.out.valid := doneValid && pRdReg =/= 0.U
  io.out.bits.addr := pRdReg
  io.out.bits.data := resultReg
  io.in.flush := io.out.flush
  def divIo = io
}

/** Null DIV: same interface as DIV but does nothing. Used when isaConfig.hasM is false. */
class NullDIV(robIdWidth: Int, prfAddrWidth: Int) extends Module with DivLike {
  val io = IO(new DivIO(robIdWidth, prfAddrWidth))
  io.in.ready := true.B
  io.rob_access.valid := false.B
  io.rob_access.bits := DontCare
  io.out.valid := false.B
  io.out.bits := DontCare
  def divIo = io
}
