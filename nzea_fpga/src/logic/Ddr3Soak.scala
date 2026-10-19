package nzea_fpga.logic

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_fpga.{BoardLogic, Clocks, LogicNeeds, Logics}
import nzea_fpga.device.ddr3.{Ddr3BusShape, Ddr3PadNames, Ddr3Side, Ddr3SideParameter}
import nzea_rtl.probe.Probes
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class Ddr3SoakParameter() extends Parameter

given upickle.default.ReadWriter[Ddr3SoakParameter] = upickle.default.macroRW

class Ddr3SoakLayers(parameter: Ddr3SoakParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The soak's ports: the pins, the grids and the domain its device needs, in one statement
  * ([[nzea_fpga.device.ddr3.Ddr3Side.needs]]) — the level above declares them and wires them ([[nzea_fpga.Logics]]).
  *
  * The master leaves its first request standing from reset: the device's `bus.req.ready` is never asserted before its
  * bring-up is done, so waiting for the device and waiting for a ready are the same wait.
  */
class Ddr3SoakIO(parameter: Ddr3SoakParameter) extends HWRecord(parameter):
    Logics.declare(this, Ddr3Soak.needs)

class Ddr3SoakProbe(parameter: Ddr3SoakParameter) extends DVRecord[Ddr3SoakParameter, Ddr3SoakLayers](parameter)

/** The board's DDR3 soak: the *test*, where [[nzea_fpga.device.ddr3.Ddr3]] is the transport — a LiteBus master that
  * writes the memory and reads it back, one request outstanding at a time (the device answers one burst per ~230 ns;
  * the master is not its bottleneck), and the account of what came back wrong.
  *
  * It carries the bus's board side itself: the device is instantiated here, on the pins and grids this logic states
  * ([[needs]]), so the level above knows neither the transport nor a pad of it.
  *
  * The expectation a read-back is compared against is *built*, not looked up: every write updates it byte by byte — the
  * bytes the strobe covers take the write's, the rest keep what they had — which is exactly the rule the device is held
  * to.
  */
@generator
object Ddr3Soak extends Generator[Ddr3SoakParameter, Ddr3SoakLayers, Ddr3SoakIO, Ddr3SoakProbe], BoardLogic:
    override def moduleName(parameter: Ddr3SoakParameter): String = "Ddr3Soak"

    /** What the level above must declare and wire: the bus's own statement ([[nzea_fpga.device.ddr3.Ddr3Side.needs]]).
      */
    override def needs: LogicNeeds = Ddr3Side.needs

    /** The walk's sixteen addresses, as byte addresses (a burst is 16-byte aligned, `addr[3:0]` zero): the field each
      * one moves is named beside it.
      */
    private val addrTable: Seq[BigInt] = Seq(
        0x00000000L, // row 0, bank 0, column 0 — the baseline
        0x00000010L, // the burst address's low bit (A4)
        0x00000070L, // A5..A6
        0x000000f0L, // A7
        0x000007f0L, // the column field's top bit
        0x00000800L, // bank 1
        0x00001000L, // bank 2
        0x00001800L, // bank 3
        0x00003800L, // bank 7
        0x00004000L, // row 1 (A14)
        0x00008000L, // row 2 (A15)
        0x00020000L, // row 8 (A21)
        0x01000000L, // row 0x400
        0x10000000L, // row 0x4000
        0x1fffc000L, // the top row (0x7fff), bank 0
        0x1fffd000L // the top row, bank 2
    )

    /** The pattern phase's eight data words, at one address: all sixteen byte lanes move, and the words differ in *how*
      * — the constant levels, the alternating pair, the two walks — so a failure's pattern is a lead.
      */
    private val patternTable: Seq[BigInt] = Seq(
        BigInt("00000000000000000000000000000000", 16),
        BigInt("ffffffffffffffffffffffffffffffff", 16),
        BigInt("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 16),
        BigInt("55555555555555555555555555555555", 16),
        BigInt("0123456789abcdef0123456789abcdef", 16),
        BigInt("fedcba9876543210fedcba9876543210", 16),
        BigInt("00ff00ff00ff00ff00ff00ff00ff00ff", 16),
        BigInt("ff00ff00ff00ff00ff00ff00ff00ff00", 16)
    )

    /** The mask phase's byte strobes: one bit per byte of the beat, all *partial* by construction — a full strobe would
      * not test what this phase is for.
      */
    private val maskTable: Seq[Int] = Seq(0x0001, 0x8000, 0x00ff, 0xff00, 0x5555, 0xaaaa, 0x0f0f, 0xf0f0)

    /** The mask phase's two words: the base the full write stages, and the inverse the masked one does — a byte the
      * strobe covers must have *changed*, one it does not must not have.
      */
    private val baseWord = BigInt("55555555555555555555555555555555", 16)
    private val maskedWord = BigInt("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 16)

    /** The plan's length, and the step past it. */
    private val planSteps = addrTable.size + patternTable.size + 8 + maskTable.size

    /** The walk's data word as a constant, for the step counter's reset value ([[walkWord]] states the same word as a
      * value).
      */
    private def walkWordConst(step: Int): BigInt =
        (7 to 0 by -1)
            .map(k => (BigInt(step) << 8) | (BigInt(1) << k))
            .reduce((high, low) => (high << 16) | low)

    def architecture(parameter: Ddr3SoakParameter) =
        val io = summon[Interface[Ddr3SoakIO]]

        val ddr3 = Ddr3Side.instantiate(
            Ddr3SideParameter(
                dq = io.field[Analog](Ddr3PadNames.dq),
                dqsP = io.field[Analog](Ddr3PadNames.dqsP),
                dqsN = io.field[Analog](Ddr3PadNames.dqsN),
                ckP = io.field[Bool](Ddr3PadNames.ckP),
                ckN = io.field[Bool](Ddr3PadNames.ckN),
                addr = io.field[UInt](Ddr3PadNames.addr),
                ba = io.field[UInt](Ddr3PadNames.ba),
                dm = io.field[UInt](Ddr3PadNames.dm),
                rasN = io.field[UInt](Ddr3PadNames.rasN),
                casN = io.field[UInt](Ddr3PadNames.casN),
                weN = io.field[UInt](Ddr3PadNames.weN),
                cke = io.field[Bool](Ddr3PadNames.cke),
                odt = io.field[Bool](Ddr3PadNames.odt),
                resetN = io.field[Bool](Ddr3PadNames.resetN),
                rawResetN = io.field[Bool](Ddr3Side.ButtonPin),
                reset = io.field[Reset](Logics.ResetPort),
                tapsReady = io.field[Bool](Ddr3Side.TapsReadyReason),
                clocks = Clocks.ports(io, Ddr3Side.needs.grids)
            )
        )
        io.field[Bool](Ddr3Side.ReadyReason) := ddr3.ready
        given ClockScope = ClockScope.posedge(ddr3.clock)
        given ResetScope = ResetScope.asyncActiveLow(ddr3.resetN)

        val bus = ddr3.bus

        val patternAddr = BigInt(0x4000)
        val lfsrAddress = BigInt(0x8000)
        val maskAddr = BigInt(0xc000)
        val allStrobes = (1 << 16) - 1

        // The step counter's own layout: bits[3:0] are the walk's index (the other phases use [2:0]), the bit above them
        // which half of the second sixteen steps (patterns or LFSR), the pair above those the phase.
        val step = RegInit(0.U(6))
        val stepTop = step.asBits.bits(5, 4).asUInt
        val stepMid = step.asBits.bit(3)
        val stepIdx = step.asBits.bits(2, 0).asUInt
        val walkIdx = step.asBits.bits(3, 0).asUInt

        val addrReg = RegInit(addrTable.head.U(Ddr3BusShape.addrWidth))
        val dataReg = RegInit(walkWordConst(0).U(Ddr3BusShape.dataWidth))
        val expectReg = RegInit(0.U(Ddr3BusShape.dataWidth))

        /** Beat `k` of the walk's data: the step's own value in the beat's high bits and a one-hot byte low, so the
          * word says which step wrote it and every byte lane carries a different level.
          */
        def walkWord(value: Referable[UInt])(using Arena, Context, Block): Node[UInt] =
            (7 to 0 by -1)
                .map(k => (0.U(2).asBits ## value.asBits.bits(5, 0) ## (BigInt(1) << k).U(8).asBits))
                .reduce((high, low) => high ## low)
                .asUInt

        /** The LFSR's feedback: bits 15, 13, 12 and 10 xored — `XAPP052`'s 16-bit entry is taps 16/14/13/11, which for
          * a register that shifts *up* and takes its feedback at bit 0 are the bits one below each tap.
          */
        def lfsrFeedback(value: Referable[UInt])(using Arena, Context, Block): Node[Bool] =
            value.asBits.bit(15) ^ value.asBits.bit(13) ^ value.asBits.bit(12) ^ value.asBits.bit(10)

        def lfsrStep(value: Referable[UInt])(using Arena, Context, Block): Node[UInt] =
            val shifted = (value.asBits << 1).bits(15, 0)
            (shifted | (0.U(15).asBits ## lfsrFeedback(value).asBits)).asUInt

        // A 16-bit LFSR whose feedback makes a maximal-length sequence, so a burst sampled twice never sees the same run
        // by accident. Seeded non-zero (0xace1, with both levels present), stepped eight times per step of its phase (a
        // burst's own width), so consecutive steps are consecutive windows of the sequence.
        val lfsr = RegInit(BigInt(0xace1).U(16))
        // The eight states a step's word is built from: the register's own and the seven above it, each one step of
        // feedback further.
        val lfsrStates: Seq[Node[UInt]] = (0 until 8).foldLeft(Seq.empty[Node[UInt]]) { (states, k) =>
            states :+ lfsrStep(if k == 0 then lfsr else states.last)
        }
        val lfsrWord: Node[UInt] =
            (7 to 0 by -1).map(k => lfsrStates(k).asBits).reduce((high, low) => high ## low).asUInt
        val lfsrNext: Node[UInt] = lfsrStep(lfsrStates.last)

        val sWrite = 0
        val sWrResp = 1
        val sMasked = 2
        val sRead = 3
        val sRdWait = 4
        val sDone = 5
        val sWidth = 3

        val state = RegInit(sWrite.U(sWidth))
        val maskDone = RegInit(false.B)

        val maskStep = (stepTop === 2.U(2)) & !stepMid

        val maskedWrite = state === sMasked.U(sWidth)
        val maskedStrobe = muxTree(stepIdx, maskTable.map(_.U(Ddr3BusShape.dataWidth / 8)))
        val issueData = maskedWrite ? (maskedWord.U(Ddr3BusShape.dataWidth), dataReg)
        val issueStrobe = maskedWrite ? (maskedStrobe, allStrobes.U(Ddr3BusShape.dataWidth / 8))
        val writing = (state === sWrite.U(sWidth)) | maskedWrite
        val issuing = writing | (state === sRead.U(sWidth))

        bus.req.valid := issuing
        bus.req.bits.addr := addrReg
        bus.req.bits.wdata := issueData
        bus.req.bits.wstrb := issueStrobe
        bus.req.bits.wen := writing
        bus.req.bits.user := 0.U(Ddr3BusShape.userWidth)
        bus.req.bits.id := 0.U(Ddr3BusShape.idWidth)
        // One request outstanding at a time, so a response has nothing to wait behind.
        bus.resp.ready := true.B

        val issued = RegInit(0.U(32))
        val errors = RegInit(0.U(32))
        val anyError = RegInit(false.B)
        val firstBadAddr = RegInit(0.U(Ddr3BusShape.addrWidth))
        val firstBadExp = RegInit(0.U(Ddr3BusShape.dataWidth))
        val firstBadGot = RegInit(0.U(Ddr3BusShape.dataWidth))
        val done = RegInit(false.B)

        // The expectation the next write leaves behind, bit for bit: the strobe's bytes take the write's, the rest keep
        // what they had — the rule the device is held to, stated once rather than per phase.
        val expectNext: Node[UInt] = (Ddr3BusShape.dataWidth - 1 to 0 by -1)
            .map(bit =>
                issueStrobe.asBits.bit(bit / 8) ? (
                    issueData.asBits.bit(bit).asBits,
                    expectReg.asBits.bit(bit).asBits
                )
            )
            .reduce((high, low) => high ## low)
            .asUInt

        when(issuing & bus.req.ready) {
            issued := constAdder(issued, 1, 32)
        }
        when(writing & bus.req.ready) {
            expectReg := expectNext
        }

        when(state === sWrite.U(sWidth)) {
            when(bus.req.ready) {
                state := sWrResp.U(sWidth)
            }
        }

        when(state === sWrResp.U(sWidth)) {
            when(bus.resp.valid) {
                state := (maskStep & !maskDone) ? (sMasked.U(sWidth), sRead.U(sWidth))
            }
        }

        when(state === sMasked.U(sWidth)) {
            when(bus.req.ready) {
                maskDone := true.B
                state := sWrResp.U(sWidth)
            }
        }

        when(state === sRead.U(sWidth)) {
            when(bus.req.ready) {
                state := sRdWait.U(sWidth)
            }
        }

        // The read-back against what the writes left behind, then the plan's next step: its address and data are read out
        // of the counter's own next value, so every phase's set-up is one mux.
        when(state === sRdWait.U(sWidth)) {
            when(bus.resp.valid) {
                val got = bus.resp.bits.data
                when(got =/= expectReg) {
                    errors := constAdder(errors, 1, 32)
                    when(!anyError) {
                        anyError := true.B
                        firstBadAddr := addrReg
                        firstBadExp := expectReg
                        firstBadGot := got
                    }
                }

                val stepNext = constAdder(step, 1, 6)
                val nextTop = stepNext.asBits.bits(5, 4).asUInt
                val nextMid = stepNext.asBits.bit(3)
                val nextIdx = stepNext.asBits.bits(2, 0).asUInt
                val patternStep = (nextTop === 1.U(2)) & !nextMid
                val lfsrStepSel = (nextTop === 1.U(2)) & nextMid
                val maskStepSel = (nextTop === 2.U(2)) & !nextMid

                addrReg := muxCase(
                    muxTree(stepNext.asBits.bits(3, 0).asUInt, addrTable.map(_.U(Ddr3BusShape.addrWidth))),
                    Seq(
                        patternStep -> patternAddr.U(Ddr3BusShape.addrWidth),
                        lfsrStepSel -> constAdder(
                            (nextIdx.asBits ## 0.U(4).asBits).asUInt,
                            lfsrAddress,
                            Ddr3BusShape.addrWidth
                        ),
                        maskStepSel -> maskAddr.U(Ddr3BusShape.addrWidth)
                    )
                )
                dataReg := muxCase(
                    walkWord(stepNext),
                    Seq(
                        patternStep -> muxTree(nextIdx, patternTable.map(_.U(Ddr3BusShape.dataWidth))),
                        lfsrStepSel -> lfsrWord,
                        maskStepSel -> baseWord.U(Ddr3BusShape.dataWidth)
                    )
                )
                lfsr := lfsrNext
                step := stepNext
                maskDone := false.B
                when(stepNext === planSteps.U(6)) {
                    done := true.B
                    state := sDone.U(sWidth)
                }.otherwise {
                    state := sWrite.U(sWidth)
                }
            }
        }

        // `phase` is the master's own state and `step` the plan's cursor — the first thing a failed plan is read from.
        Probes.mark("phase")(state)
        Probes.mark("step")(step)
        Probes.mark("issued")(issued)
        Probes.mark("errors")(errors)
        Probes.mark("done")(done)
        Probes.mark("firstBadAddr")(firstBadAddr)
        Probes.mark("firstBadExp")(firstBadExp)
        Probes.mark("firstBadGot")(firstBadGot)
