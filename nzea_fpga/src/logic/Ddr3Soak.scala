package nzea_fpga.logic

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_fpga.device.ddr3.Ddr3BusShape
import nzea_rtl.bus.LiteBusRW
import nzea_rtl.probe.Probes
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class Ddr3SoakParameter() extends Parameter

given upickle.default.ReadWriter[Ddr3SoakParameter] = upickle.default.macroRW

class Ddr3SoakLayers(parameter: Ddr3SoakParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The soak's ports: the domain it runs in — the bus's own, clock and reset handed over by
  * [[nzea_fpga.device.ddr3.Ddr3Side]] — and the master end of the memory's bus ([[nzea_fpga.device.ddr3.Ddr3BusShape]],
  * which the device states and this imports rather than restating).
  *
  * The master leaves its first request standing from reset: the device's `bus.req.ready` is never asserted before its
  * bring-up is done, so waiting for the device and waiting for a ready are the same wait.
  */
class Ddr3SoakIO(parameter: Ddr3SoakParameter) extends HWRecord(parameter):
    Flipped("clock", Clock())
    Flipped("resetN", Reset())

    Aligned(
        "bus",
        new LiteBusRW(
            Ddr3BusShape.addrWidth,
            Ddr3BusShape.dataWidth,
            Ddr3BusShape.userWidth,
            Ddr3BusShape.idWidth
        )
    )

class Ddr3SoakProbe(parameter: Ddr3SoakParameter) extends DVRecord[Ddr3SoakParameter, Ddr3SoakLayers](parameter)

/** The board's DDR3 soak: the *test*, where [[nzea_fpga.device.ddr3.Ddr3]] is the transport — a LiteBus master that
  * writes the memory and reads it back, and the account of what came back wrong.
  *
  * The plan, one step per access, one request outstanding at a time (the device answers one burst per ~230 ns and the
  * master is not its bottleneck):
  *
  *   - the *walk*: sixteen addresses, one per step ([[addrTable]]) — every field the device decodes moved: the column's
  *     bits, the banks, the row's low bits, the top row — each written and read back;
  *   - the *patterns*: eight data words at one address ([[patternTable]]) — the all-zeros, the all-ones, the
  *     alternating levels and two mixed walks a stuck or crossed lane shows itself in;
  *   - the *LFSR*: eight steps of a maximal-length sequence at eight addresses, each step's eight successive states
  *     staged as one burst's eight beats — data no table could hold, so a *pattern* failure cannot be mistaken for a
  *     table's;
  *   - the *mask*: at one address, a full write of the base pattern and then a partial one ([[maskTable]]'s strobe), so
  *     every byte the strobe does not cover has to come back as the base left it — the write mask's own test, and the
  *     only one that judges two writes by one read;
  *   - the *account*: `errors` counts the read-backs that did not match (the plan's verdict is zero), with the first
  *     one's address, expectation and answer kept beside it (`firstBad*`), and `done` is the plan's own end.
  *
  * The expectation a read-back is compared against is *built*, not looked up: every write updates it byte by byte — the
  * bytes the strobe covers take the write's, the rest keep what they had — so a masked write's expectation is the
  * base's where the strobe is clear, which is exactly the rule the device is held to.
  */
@generator
object Ddr3Soak extends Generator[Ddr3SoakParameter, Ddr3SoakLayers, Ddr3SoakIO, Ddr3SoakProbe]:
    override def moduleName(parameter: Ddr3SoakParameter): String = "Ddr3Soak"

    /** The walk's sixteen addresses, as byte addresses (a burst is 16-byte aligned, `addr[3:0]` zero): the field each
      * one moves is named beside it, and they are one table because a step *is* one address.
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

    /** The pattern phase's eight data words, at one address. Every word moves all sixteen byte lanes and differs from
      * every other in *how* — the constant levels (a lane stuck at either), the alternating pair (an edge on every
      * lane), and the two walks (a lane crossed with its neighbour) — so a failure's pattern is a lead.
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

    /** The mask phase's byte strobes: one bit per byte of the beat, so the eight cover a single byte at either end, a
      * whole byte lane's half, the alternating pairs and the nibble-wide extremes. Every one of them is *partial* by
      * construction — a full strobe would not test what this phase is for.
      */
    private val maskTable: Seq[Int] = Seq(0x0001, 0x8000, 0x00ff, 0xff00, 0x5555, 0xaaaa, 0x0f0f, 0xf0f0)

    /** The mask phase's two words: the base the full write stages, and the inverse the masked one does — so a byte the
      * strobe covers must have *changed* and a byte it does not must not have.
      */
    private val baseWord = BigInt("55555555555555555555555555555555", 16)
    private val maskedWord = BigInt("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 16)

    /** The plan's length, and the step past it: the walk, the patterns, the LFSR's steps and the masks. */
    private val planSteps = addrTable.size + patternTable.size + 8 + maskTable.size

    /** The walk's data word as a constant, for the step counter's reset value: beat `k` carries the step's value in its
      * high bits and a one-hot byte low ([[walkWord]] states the same word as a value).
      */
    private def walkWordConst(step: Int): BigInt =
        (7 to 0 by -1)
            .map(k => (BigInt(step) << 8) | (BigInt(1) << k))
            .reduce((high, low) => (high << 16) | low)

    def architecture(parameter: Ddr3SoakParameter) =
        val io = summon[Interface[Ddr3SoakIO]]
        given ClockScope = ClockScope.posedge(io.field[Clock]("clock"))
        given ResetScope = ResetScope.asyncActiveLow(io.field[Reset]("resetN"))

        val bus = io.field[LiteBusRW]("bus")

        // The fixed addresses the phases after the walk run at (the walk's own are [[addrTable]]'s): row 1 for the
        // patterns, row 2 (with the step's own column) for the LFSR, row 3 for the masks.
        val patternAddr = BigInt(0x4000)
        val lfsrAddress = BigInt(0x8000)
        val maskAddr = BigInt(0xc000)
        val allStrobes = (1 << 16) - 1

        // ---- the plan's cursor and what a step carries ----------------------------------------------------------

        // The step counter, and the step's own index read out of it: the low four bits are the walk's index (the other
        // phases use the low three), the bit above them which half of the second sixteen steps (patterns or LFSR), the
        // pair above those the phase.
        val step = RegInit(0.U(6))
        val stepTop = step.asBits.bits(5, 4).asUInt
        val stepMid = step.asBits.bit(3)
        val stepIdx = step.asBits.bits(2, 0).asUInt
        val walkIdx = step.asBits.bits(3, 0).asUInt

        // What the current step carries: the address, the data the base write stages, and the expectation the read-back
        // is judged against — which the writes update, not the step's set-up (below).
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

        /** The LFSR's feedback: the register's own bits 15, 13, 12 and 10 xored — the tap set of a maximal-length
          * 16-bit sequence (`XAPP052`'s 16-bit entry: taps 16/14/13/11, which for a register that shifts *up* and takes
          * its feedback at bit 0 are the bits one below each tap).
          */
        def lfsrFeedback(value: Referable[UInt])(using Arena, Context, Block): Node[Bool] =
            value.asBits.bit(15) ^ value.asBits.bit(13) ^ value.asBits.bit(12) ^ value.asBits.bit(10)

        /** One step of the register: the word shifted one bit up, the feedback entering at the bottom. */
        def lfsrStep(value: Referable[UInt])(using Arena, Context, Block): Node[UInt] =
            val shifted = (value.asBits << 1).bits(15, 0)
            (shifted | (0.U(15).asBits ## lfsrFeedback(value).asBits)).asUInt

        // The streaming row: a 16-bit LFSR whose feedback makes a maximal-length sequence — every word of it but zero
        // appears once before it repeats, so a burst sampled twice never sees the same run by accident. Seeded to
        // something with both levels present and a mix of runs, and stepped eight times per step of its phase (a burst's
        // own width), so consecutive steps are consecutive windows of the sequence.
        val lfsr = RegInit(BigInt(0xace1).U(16))
        // The eight states a step's word is built from: the register's own and the seven above it, each one step of
        // feedback further.
        val lfsrStates: Seq[Node[UInt]] = (0 until 8).foldLeft(Seq.empty[Node[UInt]]) { (states, k) =>
            states :+ lfsrStep(if k == 0 then lfsr else states.last)
        }
        val lfsrWord: Node[UInt] =
            (7 to 0 by -1).map(k => lfsrStates(k).asBits).reduce((high, low) => high ## low).asUInt
        val lfsrNext: Node[UInt] = lfsrStep(lfsrStates.last)

        // ---- the master's shape: one request, one response, one check -------------------------------------------

        val sWrite = 0
        val sWrResp = 1
        val sMasked = 2
        val sRead = 3
        val sRdWait = 4
        val sDone = 5
        val sWidth = 3

        val state = RegInit(sWrite.U(sWidth))
        // A mask step's second write, still to be issued: set where the masked write is accepted, cleared where the
        // step's read-back is judged (every other step never sees it set).
        val maskDone = RegInit(false.B)

        // The current step's phase, read out of its index: a mask step is the pair at 2 with its low bit clear.
        val maskStep = (stepTop === 2.U(2)) & !stepMid

        // The mask the masked write stages (the step's own strobe), and what the request carries: the read and the base
        // write take the step's registers; the masked write takes the inverse pattern and the mask.
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
        // The master is ready the cycle the device answers: one request is outstanding at a time, so a response has
        // nothing to wait behind.
        bus.resp.ready := true.B

        // ---- the account ----------------------------------------------------------------------------------------

        val issued = RegInit(0.U(32))
        val errors = RegInit(0.U(32))
        val anyError = RegInit(false.B)
        val firstBadAddr = RegInit(0.U(Ddr3BusShape.addrWidth))
        val firstBadExp = RegInit(0.U(Ddr3BusShape.dataWidth))
        val firstBadGot = RegInit(0.U(Ddr3BusShape.dataWidth))
        val done = RegInit(false.B)

        // The expectation the next write leaves behind, bit for bit: the strobe's bytes take the write's, the rest keep
        // what they had. Stated as a rule rather than per phase, because it *is* the rule the device is held to — and a
        // byte's eight bits share the one strobe bit that decides them.
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
                // A mask step issues its second write before the read-back; every other step reads next.
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

        // The read-back and the check: what arrived against what the writes left behind, and then the plan's next step
        // — its address and data read out of the counter's own next value, so every phase's set-up is one mux.
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

        // ---- what the board watches -----------------------------------------------------------------------------

        // The account a board reading is made of, marked here where the registers live: the device's own probes
        // ([[nzea_fpga.device.ddr3.Ddr3]]) say what each access was, these say what the plan made of it. `phase` is the
        // master's own state, `step` the plan's cursor beside it — the first thing a failed plan is read from.
        Probes.mark("phase")(state)
        Probes.mark("step")(step)
        Probes.mark("issued")(issued)
        Probes.mark("errors")(errors)
        Probes.mark("done")(done)
        Probes.mark("firstBadAddr")(firstBadAddr)
        Probes.mark("firstBadExp")(firstBadExp)
        Probes.mark("firstBadGot")(firstBadGot)
