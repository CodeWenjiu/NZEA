package nzea_core.backend.integer.nnu

import chisel3._
import chisel3.util.{Cat, Fill, Valid, is, switch}
import chisel3.util.experimental.loadMemoryFromFile
import firrtl.annotations.MemoryLoadFileType
import nzea_core.PipeIO
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
  loadMemoryFromFile(fc1W, fc1MemPath, MemoryLoadFileType.Hex)
  loadMemoryFromFile(fc2W, fc2MemPath, MemoryLoadFileType.Hex)
  loadMemoryFromFile(fc3W, fc3MemPath, MemoryLoadFileType.Hex)

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

  /** 0=FC1 row dot (+2 cyc scale), 1=max, 2=shift, 3=quant (4 cyc/elem), 4=FC2 row dot, 5=max, 6=shift, 7=quant, 8=FC3 row dot */
  val inferPhase = Reg(UInt(4.W))

  val row = Reg(UInt(9.W))
  val kScan = Reg(UInt(9.W))

  val maxAbs    = Reg(UInt(32.W))
  val shiftAmt  = Reg(UInt(6.W))
  val shiftIter = Reg(UInt(32.W))

  /** false = issue SyncReadMem row read; true = dot product + kick scale (read data valid). */
  val rowDotLatch = RegInit(false.B)

  /** Quant sub-sequence for phase 3 / 7: 0=read vec, 1=`>> shiftAmt` reg, 2=sat+ReLU→byte reg, 3=write act RAM. */
  val quantStage      = RegInit(0.U(2.W))
  val quantPipeK      = Reg(UInt(8.W))
  val quantPipeXs     = Reg(SInt(32.W))
  val quantShiftedReg = Reg(SInt(32.W))
  val quantActByteReg = Reg(UInt(8.W))

  /** After row dot: 1=mul reg, 2=`>>16` + write fc*Out/logits. */
  val macWriteStage = RegInit(0.U(2.W))
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
    state           := sIdle
    quantStage      := 0.U
    macWriteStage   := 0.U
    rowDotLatch := false.B
  }

  val nextPc = pcReg + 4.U

  val inF1 = state === sInfer && inferPhase === 0.U
  val inF4 = state === sInfer && inferPhase === 4.U
  val inF8 = state === sInfer && inferPhase === 8.U

  val fc1RowVec = fc1W.read(row(7, 0), inF1)
  val fc2RowVec = fc2W.read(row(6, 0), inF4)
  val fc3RowVec = fc3W.read(row(3, 0), inF8)

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
      inferPhase     := 0.U
      quantStage     := 0.U
      macWriteStage  := 0.U
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

  val inRowMac =
    (inferPhase === 0.U || inferPhase === 4.U || inferPhase === 8.U) && (macWriteStage === 0.U)

  when(state === sInfer && !io.out.flush) {
    when(macWriteStage =/= 0.U) {
      when(macWriteStage === 1.U) {
        val scaleSel = Mux(inferPhase === 4.U, scaleQ16Fc2, Mux(inferPhase === 8.U, scaleQ16Fc3, scaleQ16Fc1))
        macProdReg    := (macScaleAcc * scaleSel).asSInt
        macWriteStage := 2.U
      }.otherwise {
        val scaled = (macProdReg >> 16.U)(31, 0).asSInt
        when(inferPhase === 0.U) {
          fc1Out(macScaleRow(7, 0)) := scaled
          macWriteStage := 0.U
          when(macScaleRow === 255.U) {
            inferPhase    := 1.U
            kScan         := 0.U
            maxAbs        := 0.U
            quantStage    := 0.U
            rowDotLatch   := false.B
          }.otherwise {
            row := macScaleRow + 1.U
          }
        }.elsewhen(inferPhase === 4.U) {
          fc2Out(macScaleRow(6, 0)) := scaled
          macWriteStage := 0.U
          when(macScaleRow === 127.U) {
            inferPhase    := 5.U
            kScan         := 0.U
            maxAbs        := 0.U
            quantStage    := 0.U
            rowDotLatch   := false.B
          }.otherwise {
            row := macScaleRow + 1.U
          }
        }.elsewhen(inferPhase === 8.U) {
          logits(macScaleRow(3, 0)) := scaled
          macWriteStage := 0.U
          when(macScaleRow === 9.U) {
            state := sDone
          }.otherwise {
            row := macScaleRow + 1.U
          }
        }
      }
    }.otherwise {
      when(inferPhase =/= 0.U && inferPhase =/= 4.U && inferPhase =/= 8.U) {
        rowDotLatch := false.B
      }

      when(inRowMac) {
        when(!rowDotLatch) {
          rowDotLatch := true.B
        }.otherwise {
          val dotFull = Wire(SInt(48.W))
          when(inferPhase === 0.U) {
            dotFull := NNU.dotProducts(fc1RowVec, inputAct)
          }.elsewhen(inferPhase === 4.U) {
            dotFull := NNU.dotProducts(fc2RowVec, fc1Act)
          }.otherwise {
            dotFull := NNU.dotProducts(fc3RowVec, fc2Act)
          }
          macScaleAcc   := dotFull(31, 0).asSInt
          macScaleRow   := row
          macWriteStage := 1.U
          rowDotLatch   := false.B
        }
      }

      switch(inferPhase) {
      is(1.U) {
        val mag     = NNU.absU(fc1Out(kScan(7, 0)))
        val nextMax = Mux(mag > maxAbs, mag, maxAbs)
        maxAbs := nextMax
        when(kScan === 255.U) {
          inferPhase := 2.U
          shiftIter  := nextMax
          shiftAmt   := 0.U
        }.otherwise {
          kScan := kScan + 1.U
        }
      }
      is(2.U) {
        when(shiftIter === 0.U) {
          inferPhase := 3.U
          kScan      := 0.U
          quantStage := 0.U
        }.elsewhen(shiftIter > 127.U) {
          shiftIter := shiftIter >> 1
          shiftAmt  := shiftAmt + 1.U
        }.otherwise {
          inferPhase := 3.U
          kScan      := 0.U
          quantStage := 0.U
        }
      }
      is(3.U) {
        switch(quantStage) {
          is(0.U) {
            quantPipeK  := kScan(7, 0)
            quantPipeXs := fc1Out(kScan(7, 0))
            quantStage  := 1.U
          }
          is(1.U) {
            quantShiftedReg := quantPipeXs >> shiftAmt
            quantStage      := 2.U
          }
          is(2.U) {
            val shifted = quantShiftedReg
            val sat =
              Mux(shifted > 127.S, 127.S, Mux(shifted < (-128).S, (-128).S, shifted))
            val relu = Mux(sat < 0.S, 0.S, sat)
            quantActByteReg := relu.asUInt(7, 0)
            quantStage      := 3.U
          }
          is(3.U) {
            fc1Act(quantPipeK) := quantActByteReg
            quantStage := 0.U
            when(kScan === 255.U) {
              inferPhase   := 4.U
              row          := 0.U
              quantStage   := 0.U
              rowDotLatch  := false.B
            }.otherwise {
              kScan := kScan + 1.U
            }
          }
        }
      }
      is(5.U) {
        val mag     = NNU.absU(fc2Out(kScan(6, 0)))
        val nextMax = Mux(mag > maxAbs, mag, maxAbs)
        maxAbs := nextMax
        when(kScan === 127.U) {
          inferPhase := 6.U
          shiftIter  := nextMax
          shiftAmt   := 0.U
        }.otherwise {
          kScan := kScan + 1.U
        }
      }
      is(6.U) {
        when(shiftIter === 0.U) {
          inferPhase := 7.U
          kScan      := 0.U
          quantStage := 0.U
        }.elsewhen(shiftIter > 127.U) {
          shiftIter := shiftIter >> 1
          shiftAmt  := shiftAmt + 1.U
        }.otherwise {
          inferPhase := 7.U
          kScan      := 0.U
          quantStage := 0.U
        }
      }
      is(7.U) {
        switch(quantStage) {
          is(0.U) {
            quantPipeK  := kScan(7, 0)
            quantPipeXs := fc2Out(kScan(6, 0))
            quantStage  := 1.U
          }
          is(1.U) {
            quantShiftedReg := quantPipeXs >> shiftAmt
            quantStage      := 2.U
          }
          is(2.U) {
            val shifted = quantShiftedReg
            val sat =
              Mux(shifted > 127.S, 127.S, Mux(shifted < (-128).S, (-128).S, shifted))
            val relu = Mux(sat < 0.S, 0.S, sat)
            quantActByteReg := relu.asUInt(7, 0)
            quantStage      := 3.U
          }
          is(3.U) {
            fc2Act(quantPipeK(6, 0)) := quantActByteReg
            quantStage := 0.U
            when(kScan === 127.U) {
              inferPhase   := 8.U
              row          := 0.U
              quantStage   := 0.U
              rowDotLatch  := false.B
            }.otherwise {
              kScan := kScan + 1.U
            }
          }
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

  /** Parallel int8×uint8 MAC across a row; balanced adder tree (width grows in tree, chop at caller). */
  def dotProducts(w: Vec[UInt], x: Vec[UInt]): SInt = {
    require(w.length == x.length)
    val prods = VecInit((0 until w.length).map { i =>
      (sext8(w(i)) * sext8(x(i))).asSInt.pad(56)
    })
    prods.reduceTree(_ + _)
  }
}
