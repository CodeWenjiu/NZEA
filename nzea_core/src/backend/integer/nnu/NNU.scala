package nzea_core.backend.integer.nnu

import chisel3._
import chisel3.util.{Cat, Fill, Valid, is, log2Ceil, switch}
import chisel3.util.experimental.loadMemoryFromFile
import firrtl.annotations.MemoryLoadFileType
import nzea_core.PipeIO
import nzea_core.frontend.PrfWriteBundle
import nzea_core.retire.rob.Rob

/** WJCUS0 custom-0 NN ops; aligned with remu `OP_WJCUS0` + `mnist_infer`.
  * Weights live in [[SyncReadMem]] fed by [[loadMemoryFromFile]] (Hex / `$readmemh`): Scala parses `fc*_weight.bin`,
  * writes `build/nnu_mem_init/fc*_w8.hex` (ASCII hex, one byte per line). Use [[MemoryLoadFileType.Hex]] — Binary maps to
  * `$readmemb` (text `0`/`1` only), not raw bytes; Verilator rejects a raw `.bin` with a syntax error. */
/** Must use sequential encodings (0,1,2): [[FuOpField]] stores `litValue` in a shared `fu_op` field and
  * [[IntegerIssueQueue]] masks with `FuDecode.take(_, NnOp.getWidth)`. One-hot values (1,2,4) would make
  * `Load=4` truncate to 0 when `getWidth==2`, mis-decoding NN_LOAD as LoadAct and skipping GPR writeback. */
object NnOp extends chisel3.ChiselEnum {
  val LoadAct = Value
  val Start   = Value
  val Load    = Value
}

object NnuSramDims {
  val Fc1Rows = 256
  val Fc1Cols = 784
  val Fc2Rows = 128
  val Fc2Cols = 256
  val Fc3Rows = 10
  val Fc3Cols = 128
  val Fc1Depth: Int = Fc1Rows * Fc1Cols
  val Fc2Depth: Int = Fc2Rows * Fc2Cols
  val Fc3Depth: Int = Fc3Rows * Fc3Cols
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

  val fc1AddrW = log2Ceil(NnuSramDims.Fc1Depth)
  val fc2AddrW = log2Ceil(NnuSramDims.Fc2Depth)
  val fc3AddrW = log2Ceil(NnuSramDims.Fc3Depth)

  val (fc1MemPath, fc2MemPath, fc3MemPath) = MnistRemuWeightBin.syncReadMemInitFilePaths

  val fc1W = SyncReadMem(NnuSramDims.Fc1Depth, UInt(8.W))
  val fc2W = SyncReadMem(NnuSramDims.Fc2Depth, UInt(8.W))
  val fc3W = SyncReadMem(NnuSramDims.Fc3Depth, UInt(8.W))
  loadMemoryFromFile(fc1W, fc1MemPath, MemoryLoadFileType.Hex)
  loadMemoryFromFile(fc2W, fc2MemPath, MemoryLoadFileType.Hex)
  loadMemoryFromFile(fc3W, fc3MemPath, MemoryLoadFileType.Hex)

  val scaleQ16Fc1 = MnistRemuWeightBin.fc1ScaleQ16.S(32.W)
  val scaleQ16Fc2 = MnistRemuWeightBin.fc2ScaleQ16.S(32.W)
  val scaleQ16Fc3 = MnistRemuWeightBin.fc3ScaleQ16.S(32.W)

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

  /** true = accumulate cycle (weight byte valid for latched index). */
  val macHi     = RegInit(false.B)
  val macLatAddr = Reg(UInt(18.W))
  val macLatCol  = Reg(UInt(10.W))

  val robIdReg = Reg(UInt(robIdWidth.W))
  val pcReg    = Reg(UInt(32.W))
  val pRdReg   = Reg(UInt(prfAddrWidth.W))
  val loadData = Reg(UInt(32.W))

  val fire = io.in.valid && io.in.ready

  io.in.ready := (state === sIdle) && !io.out.flush && io.out.ready

  when(io.out.flush) {
    state := sIdle
    macHi := false.B
  }

  val nextPc = pcReg + 4.U

  val fc1Idx = ((row * 784.U(32.W)) + col)(fc1AddrW - 1, 0)
  val fc2Idx = ((row * 256.U(32.W)) + col)(fc2AddrW - 1, 0)
  val fc3Idx = ((row * 128.U(32.W)) + col)(fc3AddrW - 1, 0)

  val inF1 = state === sInfer && inferPhase === 0.U
  val inF4 = state === sInfer && inferPhase === 4.U
  val inF8 = state === sInfer && inferPhase === 8.U

  val fc1AddrMux = Mux(macHi, macLatAddr, fc1Idx)
  val fc2AddrMux = Mux(macHi, macLatAddr, fc2Idx)
  val fc3AddrMux = Mux(macHi, macLatAddr, fc3Idx)

  val fc1Byte = fc1W.read(fc1AddrMux, inF1)
  val fc2Byte = fc2W.read(fc2AddrMux, inF4)
  val fc3Byte = fc3W.read(fc3AddrMux, inF8)

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
      macHi      := false.B
      state      := sInfer
    }
  }

  when(state === sInfer && !io.out.flush) {
    when(!(inferPhase === 0.U || inferPhase === 4.U || inferPhase === 8.U)) {
      macHi := false.B
    }

    switch(inferPhase) {
      is(0.U) {
        when(!macHi) {
          macLatAddr := fc1Idx
          macLatCol  := col
          macHi      := true.B
        }.otherwise {
          val accNext = acc + (NNU.sext8(fc1Byte) * NNU.sext8(inputBuf(macLatCol))).pad(48)
          macHi := false.B
          when(col === 783.U) {
            fc1Out(row(7, 0)) := NNU.applyScale(accNext(31, 0).asSInt, scaleQ16Fc1)
            when(row === 255.U) {
              inferPhase := 1.U
              macHi   := false.B
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
          macHi      := false.B
        }.otherwise {
          kScan := kScan + 1.U
        }
      }
      is(4.U) {
        when(!macHi) {
          macLatAddr := fc2Idx
          macLatCol  := col(7, 0)
          macHi      := true.B
        }.otherwise {
          val accNext =
            acc + (NNU.sext8(fc2Byte) * NNU.sext8(fc1Act(macLatCol(7, 0)))).pad(48)
          macHi := false.B
          when(col === 255.U) {
            fc2Out(row(6, 0)) := NNU.applyScale(accNext(31, 0).asSInt, scaleQ16Fc2)
            when(row === 127.U) {
              inferPhase := 5.U
              macHi   := false.B
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
          macHi      := false.B
        }.otherwise {
          kScan := kScan + 1.U
        }
      }
      is(8.U) {
        when(!macHi) {
          macLatAddr := fc3Idx
          macLatCol  := col(6, 0)
          macHi      := true.B
        }.otherwise {
          val fc3ActIdx = Wire(UInt(7.W))
          fc3ActIdx := macLatCol(6, 0)
          val accNext =
            acc + (NNU.sext8(fc3Byte) * NNU.sext8(fc2Act(fc3ActIdx))).pad(48)
          macHi := false.B
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
