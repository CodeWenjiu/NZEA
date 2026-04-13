package nzea_core.backend.integer.nnu

import chisel3._
import chisel3.util.{Cat, Fill, MuxLookup, Valid}
import chisel3.util.experimental.loadMemoryFromFile
import firrtl.annotations.MemoryLoadFileType
import nzea_rtl.PipeIO
import nzea_core.frontend.PrfWriteBundle
import nzea_core.retire.rob.Rob

/** WJCUS0 custom-0 NN ops; aligned with remu `OP_WJCUS0` + `mnist_infer`.
  * Weights: row-wide [[SyncReadMem]] + parallel [[Vec.reduceTree]] dot; two-cycle row MAC (`rowDotLatch`)
  * matches SyncReadMem read latency. `fc*_w8.hex`: one contiguous hex line per row — see [[MnistRemuWeightBin]].
  */
/** Must use sequential encodings (0,1,2): [[FuOpField]] stores `litValue` in a shared `fu_op` field and
  * [[IntegerIssueQueue]] masks with `FuDecode.take(_, NnOp.getWidth)`. One-hot values (1,2,4) would make
  * `Load=4` truncate to 0 when `getWidth==2`, mis-decoding NN_LOAD as LoadAct and skipping GPR writeback. */
object NnOp extends chisel3.ChiselEnum {
  val LoadAct = Value
  val Start   = Value
  val Load    = Value
}

/** One high-level step of the fixed MNIST FC inference FSM (FC1 → ReLU quant → FC2 → … → FC3 logits). */
object InferPhase extends ChiselEnum {
  val Fc1RowDotAllRows = Value // 256× MAC: input image vs FC1 weights
  val Fc1MaxAbs        = Value // scan FC1 sums, find max |x|
  val Fc1ShiftAmount   = Value // normalize dynamic range (leading zeros)
  val Fc1QuantToAct    = Value // per-neuron shift / saturate / ReLU → fc1Act
  val Fc2RowDotAllRows = Value
  val Fc2MaxAbs        = Value
  val Fc2ShiftAmount   = Value
  val Fc2QuantToAct    = Value
  val Fc3RowDotLogits  = Value // 10 logits
}

/** Between row dot and writing one scaled accumulator element. */
object MacWriteStage extends ChiselEnum {
  val Idle       = Value // issue row read or wait for next row index
  val MulByScale = Value // acc * per-layer Q16 scale
  val WriteAccum = Value // >>16, write fc*Out / logits
}

/** Per-neuron quantize pipeline inside [[InferPhase.Fc1QuantToAct]] / [[InferPhase.Fc2QuantToAct]]. */
object QuantStage extends ChiselEnum {
  val LatchSrc = Value // sample fc*Out[k], k
  val Shift    = Value // arithmetic shift by shiftAmt
  val SatRelu  = Value // clamp to int8-ish range, ReLU
  val WriteAct = Value // store byte into fc*Act
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

  val (fc1MemPath, fc2MemPath, fc3MemPath) = MnistRemuWeightBin.syncReadMemInitFilePaths

  /** One row per address; read port width = Fc*Cols × 8 bit (parallel MAC vs activations). */
  val fc1W = SyncReadMem(NnuSramDims.Fc1Rows, Vec(NnuSramDims.Fc1Cols, UInt(8.W)))
  val fc2W = SyncReadMem(NnuSramDims.Fc2Rows, Vec(NnuSramDims.Fc2Cols, UInt(8.W)))
  val fc3W = SyncReadMem(NnuSramDims.Fc3Rows, Vec(NnuSramDims.Fc3Cols, UInt(8.W)))
  loadMemoryFromFile(fc1W, fc1MemPath)
  loadMemoryFromFile(fc2W, fc2MemPath)
  loadMemoryFromFile(fc3W, fc3MemPath)

  val scaleQ16Fc1 = MnistRemuWeightBin.fc1ScaleQ16.S(32.W)
  val scaleQ16Fc2 = MnistRemuWeightBin.fc2ScaleQ16.S(32.W)
  val scaleQ16Fc3 = MnistRemuWeightBin.fc3ScaleQ16.S(32.W)

  /** Full input image in registers so FC1 dot can use all pixels in parallel. */
  val inputAct = Reg(Vec(NnuSramDims.Fc1Cols, UInt(8.W)))
  val fc1Out   = Reg(Vec(256, SInt(32.W)))
  val fc1Act   = Reg(Vec(256, UInt(8.W)))
  val fc2Out   = Reg(Vec(128, SInt(32.W)))
  val fc2Act   = Reg(Vec(128, UInt(8.W)))
  val logits   = Reg(Vec(10, SInt(32.W)))

  val sIdle :: sLoadActStore :: sInfer :: sDone :: Nil = chisel3.util.Enum(4)
  val state = RegInit(sIdle)

  val loadActIdx   = Reg(UInt(10.W))
  val loadActByte  = Reg(UInt(8.W))
  val loadActValid = Reg(Bool())

  val inferPhase = RegInit(InferPhase.Fc1RowDotAllRows)

  val row = Reg(UInt(9.W))
  val kScan = Reg(UInt(9.W))

  val maxAbs    = Reg(UInt(32.W))
  val shiftAmt  = Reg(UInt(6.W))
  val shiftIter = Reg(UInt(32.W))

  /** false = issue SyncReadMem row read; true = dot product + kick scale (read data valid). */
  val rowDotLatch = RegInit(false.B)

  val quantStage      = RegInit(QuantStage.LatchSrc)
  val quantPipeK      = Reg(UInt(8.W))
  val quantPipeXs     = Reg(SInt(32.W))
  val quantShiftedReg = Reg(SInt(32.W))
  val quantActByteReg = Reg(UInt(8.W))

  val macWriteStage = RegInit(MacWriteStage.Idle)
  val macProdReg    = Reg(SInt(64.W))
  val macScaleAcc   = Reg(SInt(32.W))
  val macScaleRow   = Reg(UInt(9.W))

  val robIdReg = Reg(UInt(robIdWidth.W))
  val pcReg    = Reg(UInt(32.W))
  val pRdReg   = Reg(UInt(prfAddrWidth.W))
  val loadData = Reg(UInt(32.W))

  val fire = io.in.valid && io.in.ready

  io.in.ready := (state === sIdle) && !io.out.flush && io.out.ready

  when(io.out.flush) {
    state         := sIdle
    quantStage    := QuantStage.LatchSrc
    macWriteStage := MacWriteStage.Idle
    rowDotLatch   := false.B
  }

  val nextPc = pcReg + 4.U

  val phaseIsRowDotFc1 = inferPhase === InferPhase.Fc1RowDotAllRows
  val phaseIsRowDotFc2 = inferPhase === InferPhase.Fc2RowDotAllRows
  val phaseIsRowDotFc3 = inferPhase === InferPhase.Fc3RowDotLogits

  val readFc1Row = state === sInfer && phaseIsRowDotFc1
  val readFc2Row = state === sInfer && phaseIsRowDotFc2
  val readFc3Row = state === sInfer && phaseIsRowDotFc3

  val fc1RowVec = fc1W.read(row(7, 0), readFc1Row)
  val fc2RowVec = fc2W.read(row(6, 0), readFc2Row)
  val fc3RowVec = fc3W.read(row(3, 0), readFc3Row)

  when(state === sIdle && fire) {
    robIdReg := io.in.bits.rob_id
    pcReg    := io.in.bits.pc
    pRdReg   := io.in.bits.p_rd

    when(io.in.bits.nnOp === NnOp.LoadAct) {
      val idxS = io.in.bits.rs1.asSInt
      loadActValid := (idxS >= 0.S) && (idxS < 784.S)
      loadActIdx   := idxS.asUInt(9, 0)
      loadActByte  := io.in.bits.rs2(7, 0)
      state        := sLoadActStore
    }.elsewhen(io.in.bits.nnOp === NnOp.Load) {
      val kS        = io.in.bits.rs1.asSInt
      val kClampedS = Mux(kS < 0.S, 0.S, Mux(kS > 9.S, 9.S, kS))
      loadData := logits(kClampedS.asUInt(3, 0)).asUInt
      state := sDone
    }.elsewhen(io.in.bits.nnOp === NnOp.Start) {
      row            := 0.U
      inferPhase     := InferPhase.Fc1RowDotAllRows
      quantStage     := QuantStage.LatchSrc
      macWriteStage  := MacWriteStage.Idle
      rowDotLatch    := false.B
      state          := sInfer
    }
  }

  when(state === sLoadActStore && !io.out.flush) {
    when(loadActValid) {
      inputAct(loadActIdx) := loadActByte
    }
    state := sDone
  }

  val inRowMacPhase = phaseIsRowDotFc1 || phaseIsRowDotFc2 || phaseIsRowDotFc3
  val inRowMac      = inRowMacPhase && (macWriteStage === MacWriteStage.Idle)

  /** Q16 scale for the current row-dot layer (table, not nested Mux). */
  val rowDotScaleQ16: SInt = MuxLookup(
    inferPhase.asUInt,
    scaleQ16Fc1
  )(
    Seq(
      InferPhase.Fc2RowDotAllRows.asUInt -> scaleQ16Fc2,
      InferPhase.Fc3RowDotLogits.asUInt  -> scaleQ16Fc3
    )
  )

  /** Saturate + ReLU to unsigned 8b (shared FC1/FC2 quant). */
  def satReluToU8(shifted: SInt): UInt = {
    val sat  = Mux(shifted > 127.S, 127.S, Mux(shifted < (-128).S, (-128).S, shifted))
    val relu = Mux(sat < 0.S, 0.S, sat)
    relu.asUInt(7, 0)
  }

  /** Row-MAC pipeline: mul by Q16, then >>16 into fc*Out / logits. */
  def stepRowMacPipeline(): Unit = {
    when(macWriteStage === MacWriteStage.MulByScale) {
      macProdReg    := (macScaleAcc * rowDotScaleQ16).asSInt
      macWriteStage := MacWriteStage.WriteAccum
    }
    when(macWriteStage === MacWriteStage.WriteAccum) {
      val scaled = (macProdReg >> 16.U)(31, 0).asSInt
      when(phaseIsRowDotFc1) {
        fc1Out(macScaleRow(7, 0)) := scaled
        macWriteStage := MacWriteStage.Idle
        when(macScaleRow === 255.U) {
          inferPhase  := InferPhase.Fc1MaxAbs
          kScan       := 0.U
          maxAbs      := 0.U
          quantStage  := QuantStage.LatchSrc
          rowDotLatch := false.B
        }.otherwise {
          row := macScaleRow + 1.U
        }
      }.elsewhen(phaseIsRowDotFc2) {
        fc2Out(macScaleRow(6, 0)) := scaled
        macWriteStage := MacWriteStage.Idle
        when(macScaleRow === 127.U) {
          inferPhase  := InferPhase.Fc2MaxAbs
          kScan       := 0.U
          maxAbs      := 0.U
          quantStage  := QuantStage.LatchSrc
          rowDotLatch := false.B
        }.otherwise {
          row := macScaleRow + 1.U
        }
      }.elsewhen(phaseIsRowDotFc3) {
        logits(macScaleRow(3, 0)) := scaled
        macWriteStage := MacWriteStage.Idle
        when(macScaleRow === 9.U) {
          state := sDone
        }.otherwise {
          row := macScaleRow + 1.U
        }
      }
    }
  }

  /** Two-cycle row read + dot: first cycle assert read, second cycle consume + start scale. */
  def stepRowDotRead(): Unit = {
    when(!inRowMacPhase) {
      rowDotLatch := false.B
    }
    when(inRowMac) {
      when(!rowDotLatch) {
        rowDotLatch := true.B
      }.otherwise {
        val dotFull = Wire(SInt(48.W))
        when(phaseIsRowDotFc1) {
          dotFull := NNU.dotProducts(fc1RowVec, inputAct)
        }.elsewhen(phaseIsRowDotFc2) {
          dotFull := NNU.dotProducts(fc2RowVec, fc1Act)
        }.otherwise {
          dotFull := NNU.dotProducts(fc3RowVec, fc2Act)
        }
        macScaleAcc   := dotFull(31, 0).asSInt
        macScaleRow   := row
        macWriteStage := MacWriteStage.MulByScale
        rowDotLatch   := false.B
      }
    }
  }

  /** FC1+FC2: scan max |activation| before per-layer shift. */
  def stepMaxAbsScan(): Unit = {
    val inMax = (inferPhase === InferPhase.Fc1MaxAbs) || (inferPhase === InferPhase.Fc2MaxAbs)
    when(inMax) {
      val useFc1 = inferPhase === InferPhase.Fc1MaxAbs
      val mag    = Mux(useFc1, NNU.absU(fc1Out(kScan(7, 0))), NNU.absU(fc2Out(kScan(6, 0))))
      val nextMx = Mux(mag > maxAbs, mag, maxAbs)
      maxAbs := nextMx
      val kEnd = Mux(useFc1, kScan === 255.U, kScan === 127.U)
      when(kEnd) {
        inferPhase := Mux(useFc1, InferPhase.Fc1ShiftAmount, InferPhase.Fc2ShiftAmount)
        shiftIter  := nextMx
        shiftAmt   := 0.U
      }.otherwise {
        kScan := kScan + 1.U
      }
    }
  }

  /** FC1+FC2: derive per-layer shift count from max magnitude. */
  def stepShiftAmount(): Unit = {
    val inShift = (inferPhase === InferPhase.Fc1ShiftAmount) || (inferPhase === InferPhase.Fc2ShiftAmount)
    when(inShift) {
      val useFc1    = inferPhase === InferPhase.Fc1ShiftAmount
      val nextQuant = Mux(useFc1, InferPhase.Fc1QuantToAct, InferPhase.Fc2QuantToAct)
      when(shiftIter === 0.U) {
        inferPhase := nextQuant
        kScan      := 0.U
        quantStage := QuantStage.LatchSrc
      }.elsewhen(shiftIter > 127.U) {
        shiftIter := shiftIter >> 1
        shiftAmt  := shiftAmt + 1.U
      }.otherwise {
        inferPhase := nextQuant
        kScan      := 0.U
        quantStage := QuantStage.LatchSrc
      }
    }
  }

  /** FC1+FC2: quant sub-pipeline; next [[QuantStage]] from MuxLookup for Latch→Shift→SatRelu→WriteAct. */
  def stepQuantToAct(): Unit = {
    val inQuant = (inferPhase === InferPhase.Fc1QuantToAct) || (inferPhase === InferPhase.Fc2QuantToAct)
    when(inQuant) {
      val useFc1 = inferPhase === InferPhase.Fc1QuantToAct
      val srcXs  = Mux(useFc1, fc1Out(kScan(7, 0)), fc2Out(kScan(6, 0)))
      val kLast  = Mux(useFc1, kScan === 255.U, kScan === 127.U)
      val nextRowDot =
        Mux(useFc1, InferPhase.Fc2RowDotAllRows, InferPhase.Fc3RowDotLogits)

      val nextQuantAfterLatch = MuxLookup(
        quantStage.asUInt,
        quantStage
      )(
        Seq(
          QuantStage.LatchSrc.asUInt -> QuantStage.Shift,
          QuantStage.Shift.asUInt    -> QuantStage.SatRelu,
          QuantStage.SatRelu.asUInt  -> QuantStage.WriteAct
        )
      )

      when(quantStage === QuantStage.LatchSrc) {
        quantPipeK  := kScan(7, 0)
        quantPipeXs := srcXs
        quantStage  := nextQuantAfterLatch
      }
      when(quantStage === QuantStage.Shift) {
        quantShiftedReg := quantPipeXs >> shiftAmt
        quantStage      := nextQuantAfterLatch
      }
      when(quantStage === QuantStage.SatRelu) {
        quantActByteReg := satReluToU8(quantShiftedReg)
        quantStage      := nextQuantAfterLatch
      }
      when(quantStage === QuantStage.WriteAct) {
        when(useFc1) {
          fc1Act(quantPipeK) := quantActByteReg
        }.otherwise {
          fc2Act(quantPipeK(6, 0)) := quantActByteReg
        }
        quantStage := QuantStage.LatchSrc
        when(kLast) {
          inferPhase  := nextRowDot
          row         := 0.U
          quantStage  := QuantStage.LatchSrc
          rowDotLatch := false.B
        }.otherwise {
          kScan := kScan + 1.U
        }
      }
    }
  }

  when(state === sInfer && !io.out.flush) {
    when(macWriteStage =/= MacWriteStage.Idle) {
      stepRowMacPipeline()
    }.otherwise {
      stepRowDotRead()
      stepMaxAbsScan()
      stepShiftAmount()
      stepQuantToAct()
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

  /** Parallel int8×uint8 MAC across a row; balanced adder tree (width grows in tree, chop at caller). */
  def dotProducts(w: Vec[UInt], x: Vec[UInt]): SInt = {
    require(w.length == x.length)
    val prods = VecInit((0 until w.length).map { i =>
      (sext8(w(i)) * sext8(x(i))).asSInt.pad(56)
    })
    prods.reduceTree(_ + _)
  }
}
