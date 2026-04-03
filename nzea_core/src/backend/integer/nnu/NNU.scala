package nzea_core.backend.integer.nnu

import chisel3._
import chisel3.util.{Cat, Fill, Valid, is, log2Ceil, switch}
import nzea_core.PipeIO
import nzea_core.frontend.PrfWriteBundle
import nzea_core.retire.rob.Rob

/** WJCUS0 custom-0 NN ops; aligned with remu `OP_WJCUS0` + `mnist_infer`. NN_START = multi-cycle, one MAC/cycle. */
object NnOp extends chisel3.ChiselEnum {
  val LoadAct = Value((1 << 0).U)
  val Start   = Value((1 << 1).U)
  val Load    = Value((1 << 2).U)
}

class NnInput(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val nnOp   = NnOp()
  val rs1    = UInt(32.W)
  val rs2    = UInt(32.W)
  val pc     = UInt(32.W)
  val rob_id = UInt(robIdWidth.W)
  val p_rd   = UInt(prfAddrWidth.W)
}

class NNU(robIdWidth: Int, prfAddrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new NnInput(robIdWidth, prfAddrWidth)))
    val rob_access = Output(Valid(new nzea_core.retire.rob.RobEntryStateUpdate(robIdWidth)))
    val out        = new PipeIO(new PrfWriteBundle(prfAddrWidth))
  })

  val fc1Blob = MnistWeightLoader.fc1
  val fc2Blob = MnistWeightLoader.fc2
  val fc3Blob = MnistWeightLoader.fc3

  val fc1W = VecInit(fc1Blob.bytes.toIndexedSeq.map(b => (b & 0xff).U(8.W)))
  val fc2W = VecInit(fc2Blob.bytes.toIndexedSeq.map(b => (b & 0xff).U(8.W)))
  val fc3W = VecInit(fc3Blob.bytes.toIndexedSeq.map(b => (b & 0xff).U(8.W)))

  val scaleQ16Fc1 = fc1Blob.scaleQ16.S
  val scaleQ16Fc2 = fc2Blob.scaleQ16.S
  val scaleQ16Fc3 = fc3Blob.scaleQ16.S

  val fc1AddrW = log2Ceil(fc1Blob.bytes.length)
  val fc2AddrW = log2Ceil(fc2Blob.bytes.length)
  val fc3AddrW = log2Ceil(fc3Blob.bytes.length)

  val inputBuf = Reg(Vec(784, UInt(8.W)))
  val fc1Out   = Reg(Vec(256, SInt(32.W)))
  val fc1Act   = Reg(Vec(256, UInt(8.W)))
  val fc2Out   = Reg(Vec(128, SInt(32.W)))
  val fc2Act   = Reg(Vec(128, UInt(8.W)))
  val logits   = Reg(Vec(10, SInt(32.W)))

  val sIdle :: sInfer :: sDone :: Nil = chisel3.util.Enum(3)
  val state = RegInit(sIdle)

  /** 0=FC1 MAC, 1=FC1 max|.|, 2=FC1 shift count, 3=FC1 quant+relu, 4=FC2 MAC, 5=FC2 max, 6=FC2 shift, 7=FC2 quant+relu, 8=FC3 MAC */
  val inferPhase = Reg(UInt(4.W))

  val row   = Reg(UInt(9.W))
  val col   = Reg(UInt(10.W))
  val acc   = Reg(SInt(48.W))
  val kScan = Reg(UInt(9.W))

  val maxAbs    = Reg(UInt(32.W))
  val shiftAmt  = Reg(UInt(6.W))
  val shiftIter = Reg(UInt(32.W))

  val robIdReg = Reg(UInt(robIdWidth.W))
  val pcReg    = Reg(UInt(32.W))
  val pRdReg   = Reg(UInt(prfAddrWidth.W))
  val loadData = Reg(UInt(32.W))

  val fire = io.in.valid && io.in.ready

  io.in.ready := (state === sIdle) && !io.out.flush && io.out.ready

  when(io.out.flush) {
    state := sIdle
  }

  val nextPc = pcReg + 4.U

  val fc1Idx = ((row * 784.U(32.W)) + col)(fc1AddrW - 1, 0)
  val fc2Idx = ((row * 256.U(32.W)) + col)(fc2AddrW - 1, 0)
  val fc3Idx = ((row * 128.U(32.W)) + col)(fc3AddrW - 1, 0)

  val wFc1 = fc1W(fc1Idx)
  val wFc2 = fc2W(fc2Idx)
  val wFc3 = fc3W(fc3Idx)

  val aFc1 = inputBuf(col)
  val aFc2 = fc1Act(col(7, 0))
  val aFc3 = fc2Act(col(6, 0))

  val prodFc1 = NNU.sext8(wFc1) * NNU.sext8(aFc1)
  val prodFc2 = NNU.sext8(wFc2) * NNU.sext8(aFc2)
  val prodFc3 = NNU.sext8(wFc3) * NNU.sext8(aFc3)

  when(state === sIdle && fire) {
    robIdReg := io.in.bits.rob_id
    pcReg    := io.in.bits.pc
    pRdReg   := io.in.bits.p_rd

    when(io.in.bits.nnOp === NnOp.LoadAct) {
      val idxS = io.in.bits.rs1.asSInt
      when(idxS >= 0.S && idxS < 784.S) {
        inputBuf(idxS.asUInt(9, 0)) := io.in.bits.rs2(7, 0)
      }
      state := sDone
    }.elsewhen(io.in.bits.nnOp === NnOp.Load) {
      val kS        = io.in.bits.rs1.asSInt
      val kClampedS = Mux(kS < 0.S, 0.S, Mux(kS > 9.S, 9.S, kS))
      loadData := logits(kClampedS.asUInt(3, 0)).asUInt
      state := sDone
    }.elsewhen(io.in.bits.nnOp === NnOp.Start) {
      row        := 0.U
      col        := 0.U
      acc        := 0.S
      inferPhase := 0.U
      state      := sInfer
    }
  }

  when(state === sInfer && !io.out.flush) {
    switch(inferPhase) {
      is(0.U) {
        val accNext = acc + prodFc1.pad(48)
        when(col === 783.U) {
          fc1Out(row(7, 0)) := NNU.applyScale(accNext(31, 0).asSInt, scaleQ16Fc1)
          when(row === 255.U) {
            inferPhase := 1.U
            kScan   := 0.U
            maxAbs  := 0.U
          }.otherwise {
            row := row + 1.U
            col := 0.U
            acc := 0.S
          }
        }.otherwise {
          acc := accNext
          col := col + 1.U
        }
      }
      is(1.U) {
        val mag     = NNU.absU(fc1Out(kScan(7, 0)))
        val nextMax = Mux(mag > maxAbs, mag, maxAbs)
        maxAbs := nextMax
        when(kScan === 255.U) {
          inferPhase := 2.U
          shiftIter := nextMax
          shiftAmt  := 0.U
        }.otherwise {
          kScan := kScan + 1.U
        }
      }
      is(2.U) {
        when(shiftIter === 0.U) {
          inferPhase := 3.U
          kScan      := 0.U
        }.elsewhen(shiftIter > 127.U) {
          shiftIter := shiftIter >> 1
          shiftAmt  := shiftAmt + 1.U
        }.otherwise {
          inferPhase := 3.U
          kScan      := 0.U
        }
      }
      is(3.U) {
        val k8      = kScan(7, 0)
        val x       = fc1Out(k8)
        val shifted = x >> shiftAmt
        val sat =
          Mux(shifted > 127.S, 127.S, Mux(shifted < (-128).S, (-128).S, shifted))
        val relu = Mux(sat < 0.S, 0.S, sat)
        fc1Act(k8) := relu.asUInt(7, 0)
        when(kScan === 255.U) {
          inferPhase := 4.U
          row        := 0.U
          col        := 0.U
          acc        := 0.S
        }.otherwise {
          kScan := kScan + 1.U
        }
      }
      is(4.U) {
        val accNext = acc + prodFc2.pad(48)
        when(col === 255.U) {
          fc2Out(row(6, 0)) := NNU.applyScale(accNext(31, 0).asSInt, scaleQ16Fc2)
          when(row === 127.U) {
            inferPhase := 5.U
            kScan   := 0.U
            maxAbs  := 0.U
          }.otherwise {
            row := row + 1.U
            col := 0.U
            acc := 0.S
          }
        }.otherwise {
          acc := accNext
          col := col + 1.U
        }
      }
      is(5.U) {
        val mag     = NNU.absU(fc2Out(kScan(6, 0)))
        val nextMax = Mux(mag > maxAbs, mag, maxAbs)
        maxAbs := nextMax
        when(kScan === 127.U) {
          inferPhase := 6.U
          shiftIter := nextMax
          shiftAmt  := 0.U
        }.otherwise {
          kScan := kScan + 1.U
        }
      }
      is(6.U) {
        when(shiftIter === 0.U) {
          inferPhase := 7.U
          kScan      := 0.U
        }.elsewhen(shiftIter > 127.U) {
          shiftIter := shiftIter >> 1
          shiftAmt  := shiftAmt + 1.U
        }.otherwise {
          inferPhase := 7.U
          kScan      := 0.U
        }
      }
      is(7.U) {
        val k7      = kScan(6, 0)
        val x       = fc2Out(k7)
        val shifted = x >> shiftAmt
        val sat =
          Mux(shifted > 127.S, 127.S, Mux(shifted < (-128).S, (-128).S, shifted))
        val relu = Mux(sat < 0.S, 0.S, sat)
        fc2Act(k7) := relu.asUInt(7, 0)
        when(kScan === 127.U) {
          inferPhase := 8.U
          row        := 0.U
          col        := 0.U
          acc        := 0.S
        }.otherwise {
          kScan := kScan + 1.U
        }
      }
      is(8.U) {
        val accNext = acc + prodFc3.pad(48)
        when(col === 127.U) {
          logits(row(3, 0)) := NNU.applyScale(accNext(31, 0).asSInt, scaleQ16Fc3)
          when(row === 9.U) {
            state := sDone
          }.otherwise {
            row := row + 1.U
            col := 0.U
            acc := 0.S
          }
        }.otherwise {
          acc := accNext
          col := col + 1.U
        }
      }
    }
  }

  when(state === sDone && io.out.ready && !io.out.flush) {
    state := sIdle
  }

  val uDone = Rob.entryStateUpdate(state === sDone, robIdReg, is_done = true.B, next_pc = nextPc)(robIdWidth)
  io.rob_access <> uDone

  io.out.valid := state === sDone && pRdReg =/= 0.U
  io.out.bits.addr := pRdReg
  io.out.bits.data := loadData
  io.in.flush := io.out.flush
}

object NNU {
  def sext8(u: UInt): SInt = Cat(Fill(24, u(7)), u).asSInt

  def applyScale(acc: SInt, scaleQ16: SInt): SInt = {
    val prod = acc.pad(64) * scaleQ16.pad(64)
    (prod >> 16.U)(31, 0).asSInt
  }

  def absU(x: SInt): UInt = Mux(x < 0.S, (-x).asUInt, x.asUInt)
}

