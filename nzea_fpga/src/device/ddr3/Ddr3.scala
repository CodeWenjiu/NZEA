package nzea_fpga.device.ddr3

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.probe.Probes
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** No build knobs: the device, the board's routing and the rate are board facts, and they are stated here as constants
  * beside the sequence that encodes them (`nzea_fpga/sim/Ddr3Smoke.sv` is the same sequence held to the vendor model).
  */
case class Ddr3Parameter() extends Parameter

given upickle.default.ReadWriter[Ddr3Parameter] = upickle.default.macroRW

class Ddr3Layers(parameter: Ddr3Parameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The controller's side of the bus: the digital pins ([[Ddr3PadNames]] names them, and [[nzea_fpga.TopIO]] wires them
  * to the pads' modules by name) and the one analog knob the bring-up measures, the capture's delay tap.
  */
class Ddr3IO(parameter: Ddr3Parameter) extends HWRecord(parameter):
    Flipped("clock", Clock())
    Flipped("resetN", Reset())

    Aligned(Ddr3PadNames.dqOut, UInt(Ddr3PadNames.dqWordWidth))
    Aligned(Ddr3PadNames.dqOe, Bool())
    Aligned(Ddr3PadNames.dqsOut, UInt(Ddr3PadNames.dqsWordWidth))
    Aligned(Ddr3PadNames.dqsOe, Bool())

    // A pair per pin: bit `2i` is a pin's first beat of the cycle, `2i + 1` its second.
    Aligned(Ddr3PadNames.addr, UInt(2 * Ddr3PadNames.addrWidth))
    Aligned(Ddr3PadNames.ba, UInt(2 * Ddr3PadNames.baWidth))
    Aligned(Ddr3PadNames.dm, UInt(2 * Ddr3PadNames.dmWidth))
    Aligned(Ddr3PadNames.rasN, UInt(2))
    Aligned(Ddr3PadNames.casN, UInt(2))
    Aligned(Ddr3PadNames.weN, UInt(2))
    Aligned(Ddr3PadNames.cke, Bool())
    Aligned(Ddr3PadNames.odt, Bool())
    Aligned(Ddr3PadNames.resetN, Bool())

    Flipped(Ddr3PadNames.dqIn, UInt(Ddr3PadNames.dqWordWidth))
    Aligned(Ddr3PadNames.delayTap, UInt(Ddr3PadNames.tapWidth * Ddr3PadNames.tapLanes))

    // ── The campaign's side ([[nzea_fpga.logic.Ddr3Soak]]) ──
    // What the device does is one round — activate, write, precharge, activate, read, judge — and *what* a round carries
    // is not the device's business: the beats and the address arrive on these ports, the campaign's registers, which it
    // holds stable for the whole round (it steps them on the round's own edge, long before the write stages them).
    Flipped("campBeats", UInt(8 * 16))
    Flipped("campBa", UInt(Ddr3PadNames.baWidth))
    Flipped("campRow", UInt(Ddr3PadNames.addrWidth))
    Flipped("campCol", UInt(Ddr3PadNames.addrWidth))
    // And what the round says back, on the round's own edge (`campRoundEdge`): its verdict, the tap it ran at (the
    // calibration's instrument, which is what a miss is attributed by), whether the sweep is still running and what it
    // locked, and the round that closes a turn of it — a *block*, in the campaign's terms.
    Aligned("campPass", Bool())
    Aligned("campRoundEdge", Bool())
    Aligned("campRoundDone", Bool())
    Aligned("campTap", UInt(Ddr3PadNames.tapWidth))
    Aligned("campCalibrating", Bool())
    Aligned("campLockedTap", UInt(Ddr3PadNames.tapWidth))

class Ddr3Probe(parameter: Ddr3Parameter) extends DVRecord[Ddr3Parameter, Ddr3Layers](parameter)

/** The board's DDR3 device: the JEDEC power-up and mode-register sequence once, then a BL8 burst written and read back,
  * over and over — the transport, and the read path's own two measurements.
  *
  * What it is *not* is the test. A round carries what the campaign's ports hand it (`campBeats`, `campBa`, `campRow`,
  * `campCol`) and answers with one bit — did the burst come back — and who asked, why, and what the bits mean is the
  * campaign's business ([[nzea_fpga.logic.Ddr3Soak]]). What stays here is what only the device side can say:
  *
  *   - the *burst itself*: the pins, the command sequence and its timings, the write staging (the burst's three words
  *     and the strobe train), the capture window, and the judgement of whether the window carries the burst that was
  *     written ([[nzea_fpga.device.ddr3.Ddr3Pads]] holds the grids and their reasons);
  *   - the read path's two facts, measured as the rounds run: the *alignment* the capture finds (the burst arrives at
  *     one of the window's offsets, and which one is a board fact) and the *tap* — the input delay the board's two byte
  *     lanes need, found by sweeping `DDR3_DELAY_TAP` one step per round and locking where the window is widest. A
  *     sweep of 32 steps times the high lane's three hypotheses is therefore the device's own instrument, not the
  *     campaign's: it is what `campTap` (the step of the round), `campCalibrating`/`campLockedTap` (whether it still
  *     runs, and what it chose), and `campRoundDone` (a whole turn of it — the campaign's *block*) are, and the
  *     campaign only reads them to attribute what it sees.
  *
  * The write path, and how its two grids relate: the device's CK and both write strobes leave the fabric on the command
  * grid, which is the strobes' nominal position against the clock, and the data leaves on the strobe grid a quarter
  * period later — so the alignment the device needs is the alignment the pads have, and the strobe's own edges land
  * where the device's write capture wants them ([[Ddr3Pads]] holds the grids and their reasons). The taps are the one
  * per-board knob in it: what the sweep moves is the *read* capture's delay, per byte lane, and nothing on the write
  * side is levelled — this device offers no `ODELAYE2` and no PHASER, so there is nothing there to level.
  *
  * The timing: one cycle is one tCK (400 MHz). A command word is one cycle wide and launches from the same grid the
  * data does, so the device registers it at the CK edge a quarter cycle in — the middle of its window — and the write's
  * first beat must appear exactly CWL cycles after the command's cycle (`nzea_fpga/sim/Ddr3Smoke.sv` holds the vendor
  * model to the same relationship for reads). The register values are the reference sequence's: MR0 = 0x0520 (BL8, CL
  * 6, WR 6, DLL reset), MR1 = 0x0044 (RZQ/6, Rtt_Nom), MR2 = MR3 = 0.
  */
@generator
object Ddr3 extends Generator[Ddr3Parameter, Ddr3Layers, Ddr3IO, Ddr3Probe]:
    override def moduleName(parameter: Ddr3Parameter): String = "Ddr3"

    def architecture(parameter: Ddr3Parameter) =
        val io = summon[Interface[Ddr3IO]]
        given ClockScope = ClockScope.posedge(io.field[Clock]("clock"))
        given ResetScope = ResetScope.asyncActiveLow(io.field[Reset]("resetN"))

        // The ports, under the names the body uses: the declarations carry the interface's names (`Ddr3PadNames`),
        // and these aliases are what the logic below reads and writes.
        val dqOut = io.field[UInt](Ddr3PadNames.dqOut)
        val dqOe = io.field[Bool](Ddr3PadNames.dqOe)
        val dqsOut = io.field[UInt](Ddr3PadNames.dqsOut)
        val dqsOe = io.field[Bool](Ddr3PadNames.dqsOe)
        val addr = io.field[UInt](Ddr3PadNames.addr)
        val ba = io.field[UInt](Ddr3PadNames.ba)
        val dm = io.field[UInt](Ddr3PadNames.dm)
        // The command side carries a pair per pin (the two beats of the cycle), so its aliases are wide values; the
        // housekeeping pins are levels and stay `Bool`s.
        val rasN = io.field[UInt](Ddr3PadNames.rasN)
        val casN = io.field[UInt](Ddr3PadNames.casN)
        val weN = io.field[UInt](Ddr3PadNames.weN)
        val cke = io.field[Bool](Ddr3PadNames.cke)
        val odt = io.field[Bool](Ddr3PadNames.odt)
        val deviceResetN = io.field[Bool](Ddr3PadNames.resetN)
        val dqIn = io.field[UInt](Ddr3PadNames.dqIn)
        val delayTap = io.field[UInt](Ddr3PadNames.delayTap)

        // The campaign's side ([[nzea_fpga.logic.Ddr3Soak]]): what the round carries, and what it reports. The beats
        // are the campaign's registers, held stable for the round; the address likewise.
        val campBeats = io.field[UInt]("campBeats")
        val campBa = io.field[UInt]("campBa")
        val campRow = io.field[UInt]("campRow")
        val campCol = io.field[UInt]("campCol")
        val campPassPort = io.field[Bool]("campPass")
        val campRoundEdge = io.field[Bool]("campRoundEdge")
        val campRoundDonePort = io.field[Bool]("campRoundDone")
        val campTapPort = io.field[UInt]("campTap")
        val campCalibrating = io.field[Bool]("campCalibrating")
        val campLockedTapPort = io.field[UInt]("campLockedTap")

        /** The campaign's beats, as the write staging and the check read them: beat `k`'s 16 bits. The port is one flat
          * word because a round is one row of a table, and the campaign is what indexes the table.
          */
        def campBeat(k: Int)(using Arena, Context, Block): Node[UInt] =
            campBeats.asBits.bits(16 * k + 15, 16 * k).asUInt

        // ---- the device's facts (see the class comment) ---------------------------------------------------------

        val mr0 = BigInt(0x0520)
        val mr1 = BigInt(0x0044)
        val mr2 = BigInt(0x0000)
        val mr3 = BigInt(0x0000)
        val cwl = 5
        val cl = 6

        // The power-up rules, in cycles — a cycle is a whole memory clock (two data beats): reset low for 200 us,
        // then 500 us before CKE, then tXPR before the first mode register set. They are the device's own numbers
        // (JESD79-3's power-up section); the vendor model's checks are what holds them (`nzea_fpga/sim/Ddr3Smoke.sv`).
        val resetCycles = 40000
        val ckeCycles = 100000
        val txprCycles = 200
        val mrdCycles = 2
        val dllkCycles = 275
        val zqinitCycles = 350
        val trcdCycles = 4
        val wrTailCycles = 2
        val trpCycles = 4
        // tRFC is 260 ns for this device (the vendor model's own `TRFC_MIN`): a refresh to the next activate has to
        // clear that, and 300 ns is the number the round's shape allows.
        val trfcCycles = 60

        // The patterns a round may write, and the addresses it runs at, are the *campaign's* table
        // ([[nzea_fpga.logic.Ddr3Soak]]): the device stages the beats it is handed and never asks what they mean. The
        // table's own rules — no row constant, a streaming row, addresses that reach every field the device decodes —
        // travel with it.

        /** A strobe word: bit `4i + k` is lane `i`'s level in slot `k` (the pads expand a level per beat). */
        def dqsWord(slots: Seq[Int]): BigInt =
            (0 until Ddr3PadNames.dqsWidth)
                .map(lane => slots.zipWithIndex.map { case (level, k) => BigInt(level) << (4 * lane + k) }.sum)
                .sum

        // The burst's three words are built at the staging below, from the round's own beats: the write latency puts
        // the data's start a beat and a half into a cycle — the first beat is centred on the serializer's *third*
        // slot — so the word the burst opens with carries two beats of nothing (the line's idle level) before the
        // round's first beat.

        // The strobe's words. The train's opening frame is low, low, then the first rising edge, then low: the
        // device wants its preamble (tWPRE, three quarters of a clock at least) before the burst's first edge, and
        // that edge is the one the toggling word that follows opens on. The toggling word is the burst itself — a
        // transition on every beat centre — the tail carries the last transition and leaves the strobe low (the
        // postamble), and the hold-low and idle levels frame it. The initial output register settles high, the
        // strobe's idle.
        //
        // The train is one level sequence, and *both* lanes' words are cut from it at the same phase: what differs
        // between the lanes is the *grid* their serializer runs on ([[Ddr3Pads]]'s strobe bullet), not the words.
        val dqsIdle = dqsWord(Seq(1, 1, 1, 1))
        val dqsLow = dqsWord(Seq(0, 0, 0, 0))
        val dqsFlat =
            Seq(1, 1, 1, 1) ++ Seq(0, 0, 1, 0) ++ Seq(1, 0, 1, 0) ++ Seq(1, 0, 0, 0) ++ Seq(0, 0, 0, 0)

        /** One lane's strobe word, cut from the train at the word the write stages at `stage` (0 = the opening frame):
          * bit `4 * lane + k` is the lane's level in slot `k`.
          */
        def dqsLaneWord(flat: Seq[Int], lane: Int, stage: Int): BigInt =
            flat.slice(4 + 4 * stage, 8 + 4 * stage)
                .zipWithIndex
                .map((level, k) => BigInt(level) << (4 * lane + k))
                .sum

        /** The two lanes' strobe word at `stage`, the same train cut at the same phase for both. */
        def dqsWordAt(stage: Int): BigInt =
            (0 until Ddr3PadNames.dqsWidth).map(lane => dqsLaneWord(dqsFlat, lane, stage)).sum

        // The window spans the worst case its own fixed phase cannot tune away: the read burst's arrival at the
        // capture register is the device's read latency plus the ISERDES's own, and measured on the board the pair
        // lands about two cycles later than the vendor's model puts them. Eleven cycles (44 samples, offsets 0..36)
        // cover both — the simulation's burst and the board's sit inside it with more than a cycle of slack either
        // side — and what the window cannot catch is a factor of two, which the tap sweep would show as a dead map.
        val captureFirst = 5
        val captureLast = 15
        val captureSamples = (captureLast - captureFirst + 1) * Ddr3PadNames.beats
        val captureBits = captureSamples * 16
        val checkOffsets = captureSamples - 8 + 1

        // ---- the state ------------------------------------------------------------------------------------------

        // The bring-up's glide path, then the loop: activate, write, precharge, activate, read, check. Every command
        // state is one cycle wide, and the waits between them are the device's own numbers.
        val sReset = 0
        val sCkeWait = 1
        val sCke = 2
        val sTxp = 3
        val sMrs2 = 4
        val sMrs3 = 5
        val sMrs1 = 6
        val sMrs0 = 7
        val sDll = 8
        val sZq = 9
        val sZqWait = 10
        val sAct1 = 11
        val sAct1Wait = 12
        val sWr = 13
        val sWrTail = 14
        val sPre = 15
        val sPreWait = 16
        val sAct2 = 17
        val sAct2Wait = 18
        val sRd = 19
        val sRound = 20
        val sRefresh = 21
        val sRefreshWait = 22
        val sPreLead = 23
        val sPreLeadWait = 24

        val state = RegInit(sReset.U(5))
        val timer = RegInit(0.U(24))
        // A cycle is this state machine's unit; counting is what it does between commands.
        timer := constAdder(timer, 1, 24)

        // ---- the round's data: what the campaign hands it ----------------------------------------------------------

        // A round carries what the campaign's ports say — its beats and its address — and the device stages them exactly
        // as it did when they were its own registers: `wordNode` below reads the beats, the command decode reads the
        // row, column and bank, and the check's expected word reads the same beats the write staged. What they *mean* —
        // which pattern, which address, and what a miss is worth — is the campaign's business
        // ([[nzea_fpga.logic.Ddr3Soak]]); it holds them stable for the whole round, so nothing here has to latch them.

        /** A cycle's data word from the campaign's beats: bit `4i + k` is lane `i`'s bit of the beat in slot `k`, with
          * the slots the burst does not fill held at zero (the burst's first beat is centred on the serializer's third
          * slot, so the words the write opens and closes with carry padding).
          */
        def wordNode(slots: Seq[Int])(using Arena, Context, Block): Node[UInt] =
            (63 to 0 by -1)
                .map(pos => {
                    val lane = pos / 4
                    val k = pos % 4
                    if slots(k) < 0 then Node(0.U(1).asBits) else campBeat(slots(k)).asBits.bit(lane).asBits
                })
                .reduce((high, low) => high ## low)
                .asUInt

        // The burst as the check reads it back: the capture's window carries its newest samples at its low bits (the
        // shift appends there), so the expected word is the round's beats with the last beat lowest — a window whose low
        // bits are the last beat is the burst read from its end — and `expectedReg` is latched from the very beats the
        // write staged (at the round's own write), so the comparison is register against register and the window's
        // offsets are the only variable in it.
        val expectedBurst: Node[UInt] =
            (0 until 8).map(k => campBeat(k).asBits).reduce((high, low) => high ## low).asUInt
        val expectedReg = RegInit(0.U(128))

        // The calibration's own record of what it saw: which (plane, step) pairs of the sweep a miss has stood at,
        // plane-major (`32 * plane + step`), so one capture holds the read window of all three of the high lane's
        // hypotheses at once. It is written only while the sweep runs — after the lock there is no sweep to record.
        val failTaps = RegInit(0.U(3 * (1 << Ddr3PadNames.tapWidth)))

        // ---- the pins -------------------------------------------------------------------------------------------

        // The command/address bus is a value, decided once per cycle from the state: a command is the cycle its state
        // says so, and every other cycle is the NOP the device expects between commands (its chip select is tied low
        // on this board, so every cycle is a command cycle). Every pin is assigned exactly once — the wide selections
        // (address, bank) are lookups, the four control lines are the command set's own decode.
        val mrsState =
            (state === sMrs2.U(5)) | (state === sMrs3.U(5)) | (state === sMrs1.U(5)) | (state === sMrs0.U(5))
        val actState = (state === sAct1.U(5)) | (state === sAct2.U(5))
        val writeState = state === sWr.U(5)
        val readState = state === sRd.U(5)
        val preState = (state === sPre.U(5)) | (state === sPreLead.U(5))
        val zqState = state === sZq.U(5)
        val refreshState = state === sRefresh.U(5)
        val levelAddress = preState | zqState

        // A command is one cycle wide even where its state waits longer than that (a mode register set and the write
        // both do): the device's chip select is tied low, so a command held for the whole wait would be a second
        // command — measured as `tCCD violation` on the burst that followed.
        //
        // "One cycle wide" is "the state's first": every state enters with the timer at zero — every transition
        // writes it — and no state lets it return there, so comparing the state with last cycle's value says the same
        // thing as comparing the wide counter with zero does, and it reads two small registers instead of the counter.
        // The difference is the cone this decode's cross-grid path has to fit into: the command serializers load on
        // the 0° grid, a quarter period before the controller's own clock (625 ps of the 5 ns this design runs at),
        // so whatever sits between the timer and the pads must settle in 4.375 ns — measured short by 86 ps with the
        // counter in the cone, and met with room once the compare is this one. (The `timer` is in the write staging's
        // and the capture window's own terms below; only the command decode reads the one-off through the state.)
        val statePrev = RegInit(sReset.U(5))
        statePrev := state
        val commandCycle = state =/= statePrev
        val mrsCommand = mrsState & commandCycle
        val actCommand = actState & commandCycle
        val writeCommand = writeState & commandCycle
        val readCommand = readState & commandCycle
        val preCommand = preState & commandCycle
        val zqCommand = zqState & commandCycle
        val refreshCommand = refreshState & commandCycle

        // The command/address side presents *two* beats per cycle, and the device reads one command per clock, so a
        // command occupies a cycle's first beat and the second beat is the deselect that closes it: every control
        // line high, the address low. `pairOf` interleaves two levels into that pair — bit `2i` a pin's first beat,
        // `2i + 1` its second — and `3` is a control line high in both beats (the deselect's own level).
        def pairOf(first: BigInt, second: BigInt, pins: Int): BigInt =
            (0 until pins).map(i => ((((second >> i) & 1) << 1) | ((first >> i) & 1)) << (2 * i)).sum

        /** The same pair from a runtime value: bit `2i` the command beat's bit `i`, bit `2i + 1` the deselect's zero.
          */
        def pairNode(value: Referable[UInt], pins: Int)(using Arena, Context, Block): Node[UInt] =
            ((2 * pins - 1) to 0 by -1)
                .map(pos => {
                    if pos % 2 == 0 then value.asBits.bit(pos / 2).asBits else Node(0.U(1).asBits)
                })
                .reduce((high, low) => high ## low)
                .asUInt
        val addrWidth = 2 * Ddr3PadNames.addrWidth
        val baWidth = 2 * Ddr3PadNames.baWidth
        val nopAddr = BigInt(0x0400)
        addr := muxLookup(state, 0.U(addrWidth))(
            Seq(
                sMrs2.U(5) -> pairOf(mr2, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                sMrs3.U(5) -> pairOf(mr3, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                sMrs1.U(5) -> pairOf(mr1, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                sMrs0.U(5) -> pairOf(mr0, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                sPre.U(5) -> pairOf(nopAddr, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                sPreLead.U(5) -> pairOf(nopAddr, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                sZq.U(5) -> pairOf(nopAddr, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                // The round's own address, the campaign's port: the activate carries the row, the write and the read
                // carry the column (A10 low there: on a READ or WRITE it means auto-precharge, and the round closes its
                // page itself).
                sAct1.U(5) -> pairNode(campRow, Ddr3PadNames.addrWidth),
                sAct2.U(5) -> pairNode(campRow, Ddr3PadNames.addrWidth),
                sWr.U(5) -> pairNode(campCol, Ddr3PadNames.addrWidth),
                sRd.U(5) -> pairNode(campCol, Ddr3PadNames.addrWidth)
            )
        )
        ba := muxLookup(state, 0.U(baWidth))(
            Seq(
                sMrs2.U(5) -> pairOf(2, 0, Ddr3PadNames.baWidth).U(baWidth),
                sMrs3.U(5) -> pairOf(3, 0, Ddr3PadNames.baWidth).U(baWidth),
                sMrs1.U(5) -> pairOf(1, 0, Ddr3PadNames.baWidth).U(baWidth),
                sMrs0.U(5) -> pairOf(0, 0, Ddr3PadNames.baWidth).U(baWidth),
                // The activated bank, the written and the read one (the campaign's port too): a READ or WRITE names the
                // bank its column applies to, so the round's own bank rides every command of its row.
                sAct1.U(5) -> pairNode(campBa, Ddr3PadNames.baWidth),
                sAct2.U(5) -> pairNode(campBa, Ddr3PadNames.baWidth),
                sWr.U(5) -> pairNode(campBa, Ddr3PadNames.baWidth),
                sRd.U(5) -> pairNode(campBa, Ddr3PadNames.baWidth)
            )
        )
        val rasCmd = mrsCommand | actCommand | preCommand | refreshCommand
        val casCmd = mrsCommand | writeCommand | readCommand | refreshCommand
        val weCmd = mrsCommand | writeCommand | preCommand | zqCommand
        // A control line is low only in the beat its command occupies; the deselect level (`1`) fills the other beat.
        // `3` is both beats high, `2` is the command's own beat low — the pair the pads widen into the device's
        // windows.
        rasN := muxCase[UInt](3.U(2), Seq(rasCmd -> 2.U(2)))
        casN := muxCase[UInt](3.U(2), Seq(casCmd -> 2.U(2)))
        weN := muxCase[UInt](3.U(2), Seq(weCmd -> 2.U(2)))
        dm := 0.U(2 * Ddr3PadNames.dmWidth)
        odt := writeState

        val ckeReg = RegInit(false.B)
        val ddrResetNReg = RegInit(false.B)
        cke := ckeReg
        deviceResetN := ddrResetNReg
        // The data and strobe pins are driven except while the device answers: a read turns the lanes around, and every
        // other cycle the fabric holds the levels itself (the strobe idles high, the data low), so the bus is never
        // left floating. The values are registered, so what leaves the pads' `ODDR`s is the register's value a
        // cycle later — every staged beat below is written one cycle before the cycle it is due in.
        val dqOutReg = RegInit(0.U(Ddr3PadNames.dqWordWidth))
        val dqsOutReg = RegInit(dqsIdle.U(Ddr3PadNames.dqsWordWidth))
        dqOut := dqOutReg
        dqOe := !readState
        dqsOut := dqsOutReg
        dqsOe := !readState

        // ---- the bring-up ---------------------------------------------------------------------------------------

        when(state === sReset.U(5)) {
            when(timer === (resetCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sCkeWait.U(5)
                ddrResetNReg := true.B
            }
        }

        when(state === sCkeWait.U(5)) {
            when(timer === (ckeCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sCke.U(5)
            }
        }

        when(state === sCke.U(5)) {
            ckeReg := true.B
            timer := 0.U(24)
            state := sTxp.U(5)
        }

        when(state === sTxp.U(5)) {
            when(timer === (txprCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sMrs2.U(5)
            }
        }

        // A mode register set's cycle is the MRS states' own (the address and bank the decode above selects); the
        // waits between them are `tMRD`, in the order this device documents (MR2 -> MR3 -> MR1 -> MR0).
        //
        // The waits are written out at every state, not shared through a helper: a `when` a helper creates and a
        // caller places inside another `when` loses its enclosing condition (measured — the transition's own wire
        // came out as `timer == 7`, the state term gone), so every guarded region here is spelled where it guards.
        when(state === sMrs2.U(5)) {
            when(timer === (mrdCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sMrs3.U(5)
            }
        }
        when(state === sMrs3.U(5)) {
            when(timer === (mrdCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sMrs1.U(5)
            }
        }
        when(state === sMrs1.U(5)) {
            when(timer === (mrdCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sMrs0.U(5)
            }
        }
        when(state === sMrs0.U(5)) {
            // tMOD closes the register writes, tDLLK the DLL reset they asked for.
            when(timer === (dllkCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sDll.U(5)
            }
        }

        when(state === sDll.U(5)) {
            timer := 0.U(24)
            state := sZq.U(5)
        }

        // The ZQ calibration's cycle is the ZQ state's own; tZQinit follows before anything else is issued.
        when(state === sZq.U(5)) {
            timer := 0.U(24)
            state := sZqWait.U(5)
        }

        val initDone = RegInit(false.B)

        when(state === sZqWait.U(5)) {
            when(timer === (zqinitCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sPreLead.U(5)
                initDone := true.B
            }
        }

        // ---- the sweep's own stepping (the read path's instrument) ------------------------------------------------

        // The tap sweep is the device's measurement, so its counter is the device's: `roundIn` walks the 32 steps of the
        // low lane's input delay and, above them, which of the high lane's three hypotheses a round weighs (the high
        // lane's tap is the low lane's plus the plane's step, below). Whatever a round *carries* — its beats and its
        // address — arrives on the campaign's ports ([[nzea_fpga.logic.Ddr3Soak]]); what the round *is* stays here.
        //
        // The three hypotheses are the low lane's tap, that tap eight steps on (625 ps: the step the strobe's own launch
        // needed on this board, so the same board fact is the one to weigh here), and eight steps back. `UG586` centres
        // the read path per byte group because each group's delay against the fabric's capture clock is its own; the
        // sweep is what says how far this board's two groups are apart.
        val roundIn = RegInit(0.U(7))
        val roundDone = roundIn === 95.U(7)
        val roundInNext = roundDone ? (0.U(7), constAdder(roundIn, 1, 7))
        // How many whole turns of the sweep have run: the calibration's end is the 64th (the campaign's pairs are 64 —
        // eight patterns times eight addresses — and a block is one pair over all three planes and every step), so the
        // count lives here while the pairs do not.
        val blocks = RegInit(0.U(32))
        val blocksNext = roundDone ? (constAdder(blocks, 1, 32), blocks)

        // What a round reports: the alignment its window found — the read path's own fact, kept beside the sweep — and
        // the verdict the check reached, held from a round's check to the next round's own edge.
        val alignedAt = RegInit(63.U(6))
        val verdict = RegInit(false.B)
        // One bit per round, newest first: the sweep advances every round, so this register is a block's own map — one
        // bit per round, in order, as the ILA reads it — and a block is one whole turn of it, so a block's map is
        // exactly this register at the block's edge.
        val recent = RegInit(0.U(96))
        val refresh = RegInit(0.U(6))

        // The round's place in the sweep, read out of its index: the low five bits are the low byte lane's tap, the
        // pair above them the high lane's hypothesis (the sweep's own note above).
        val tap = roundIn.asBits.bits(4, 0).asUInt
        val calibPlane = roundIn.asBits.bits(6, 5).asUInt
        // The high lane's tap at this round's plane: the low lane's tap plus the plane's step. The step is eight steps
        // because the `IDELAYE2`'s own is about 78 ps and a beat of this bus is 1250 ps: eight of them are the 625 ps
        // the strobe's launch needed on this board, which is the difference the sweep is weighing on the capture side.
        // The stepped columns are tables rather than an adder's output: they are constants of the tap, so the tap's own
        // path to the pads stays wiring.
        def stepped(from: Referable[UInt], delta: Int): Node[UInt] =
            muxTree(from, (0 until 32).map(i => (((i + delta) % 32 + 32) % 32).U(Ddr3PadNames.tapWidth)))
        val highTap = muxCase(
            tap,
            Seq(
                (calibPlane === 1.U(2)) -> stepped(tap, 8),
                (calibPlane === 2.U(2)) -> stepped(tap, -8)
            )
        )

        // ---- the calibration's own end -------------------------------------------------------------------------

        // The sweep is the measurement and this is what it is for. A plane's slice of `failTaps` is the set of steps a
        // miss has ever stood at, so the plane whose slice is *least* marked is the one whose lane difference this board
        // has — the two byte lanes' capture windows, each measured against the fabric's own clock — and the middle of
        // that slice's clean run is the tap they lock at. From there the sweep stops and the rounds keep running at the
        // locked taps: what `rounds`/`misses` then count is the board's own rate with a calibrated read path.
        val calibrating = RegInit(true.B)
        val planeMissEarly = RegInit(0.U(32))
        val planeMissOn = RegInit(0.U(32))
        val planeMissBack = RegInit(0.U(32))
        // The window each plane's rounds came through at, latched as the sweep passes: the first step a pass stood at and
        // the last one, so the lock's arithmetic reads two registers instead of a chain over the whole map (that chain,
        // from a 96-bit register through 192 conditions, was this design's worst path by 11.5 ns against its 5 ns clock).
        // The map is still what a capture reads the window itself from; this is only how the lock reads it.
        val planeGotEarly = RegInit(false.B)
        val planeFirstEarly = RegInit(0.U(Ddr3PadNames.tapWidth))
        val planeLastEarly = RegInit(0.U(Ddr3PadNames.tapWidth))
        val planeGotOn = RegInit(false.B)
        val planeFirstOn = RegInit(0.U(Ddr3PadNames.tapWidth))
        val planeLastOn = RegInit(0.U(Ddr3PadNames.tapWidth))
        val planeGotBack = RegInit(false.B)
        val planeFirstBack = RegInit(0.U(Ddr3PadNames.tapWidth))
        val planeLastBack = RegInit(0.U(Ddr3PadNames.tapWidth))
        // Fewest misses wins; a tie goes to the plane that needs no lane difference, which is the one the sweep lists
        // first. The comparisons are their own register deep, because a 32-bit compare's own carry chain is then the
        // whole path: the three counts are compared pairwise, and the winner is a three-way rule over those three bits.
        val onWins = RegInit(false.B)
        val backVsEarly = RegInit(false.B)
        val backVsOn = RegInit(false.B)
        when(calibrating) {
            onWins := lessThan(planeMissOn, planeMissEarly, 32)
            backVsEarly := lessThan(planeMissBack, planeMissEarly, 32)
            backVsOn := lessThan(planeMissBack, planeMissOn, 32)
        }
        // The three-way rule: the last plane wins if it beats both; else the middle one if it beats the first and the
        // last does not beat it; else the first (a plane the sweep lists earlier holds a tie).
        val lockedPlane = (backVsEarly & backVsOn) ? (
            2.U(2),
            ((onWins & !backVsOn) ? (1.U(2), 0.U(2)))
        )
        // The locked plane's window, one register deeper, and the tap their middle names, one more: each stage reads
        // registers, so no stage's cone is more than a mux and an adder.
        val lockedFirst = RegInit(0.U(Ddr3PadNames.tapWidth))
        val lockedLast = RegInit(0.U(Ddr3PadNames.tapWidth))
        val lockedTap = RegInit(0.U(Ddr3PadNames.tapWidth))
        when(calibrating) {
            lockedFirst := muxCase(
                planeFirstEarly,
                Seq((lockedPlane === 1.U(2)) -> planeFirstOn, (lockedPlane === 2.U(2)) -> planeFirstBack)
            )
            lockedLast := muxCase(
                planeLastEarly,
                Seq((lockedPlane === 1.U(2)) -> planeLastOn, (lockedPlane === 2.U(2)) -> planeLastBack)
            )
            // The middle of the window, one bit up: the sum is taken one bit wider than the taps so it cannot wrap, and
            // its low bit is dropped — half a step of overshoot is a twelfth of a beat, which no eye cares about.
            lockedTap := prefixAdder(
                (0.U(1).asBits ## lockedFirst.asBits).asUInt,
                (0.U(1).asBits ## lockedLast.asBits).asUInt,
                false.B,
                Ddr3PadNames.tapWidth + 1
            ).asBits.bits(Ddr3PadNames.tapWidth, 1).asUInt
        }
        // The high lane's tap at the locked plane, off the register: a table and a two-way mux, which is what the pads
        // take once the sweep has stopped.
        val lockedHigh = muxCase(
            lockedTap,
            Seq(
                (lockedPlane === 1.U(2)) -> stepped(lockedTap, 8),
                (lockedPlane === 2.U(2)) -> stepped(lockedTap, -8)
            )
        )
        delayTap := calibrating ? (
            (highTap.asBits ## tap.asBits).asUInt,
            (lockedHigh.asBits ## lockedTap.asBits).asUInt
        )

        when(state === sAct1.U(5)) {
            timer := 0.U(24)
            state := sAct1Wait.U(5)
        }
        when(state === sAct1Wait.U(5)) {
            when(timer === (trcdCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sWr.U(5)
            }
        }

        // The write's staging. A word the fabric stages in the cycle `timer` reads k is what the pads' serializers
        // carry during cycle k + 1 — the serializer's own `CLKDIV` is this domain's clock, so the stage is a plain
        // same-clock hand-off and the load lands where the primitive's own pipeline puts it (measured on this design's
        // own pads in xsim: the word staged in cycle k is emitted over cycle k + 1, its slot 0 opening 2.5 ns in, the
        // next slot every 1.25 ns). The command is staged the same way one state earlier — combinationally, in the
        // state's own first cycle — and its serializer's grid is 0° against this domain, so it lands one load later
        // (measured too); either way it sits on the pins during cycle 1 and the device latches it at the CK edge a
        // quarter period in, and the burst's first beat then belongs on the third slot of the pin cycle the write
        // latency names. The data's words take cycles 1, 2 and 3 with the strobe's own frames beside them — the lead
        // frame opens the preamble and carries the burst's first edge, the toggle frame the middle two, the tail frame
        // the last edge and the postamble — and the hold-low pair takes the strobe over as they close.
        when(state === sWr.U(5)) {
            when(timer === 1.U(24)) {
                dqOutReg := wordNode(Seq(-1, -1, 0, 1))
                dqsOutReg := dqsWordAt(0).U(Ddr3PadNames.dqsWordWidth)
            }
            when(timer === 2.U(24)) {
                dqOutReg := wordNode(Seq(2, 3, 4, 5))
                dqsOutReg := dqsWordAt(1).U(Ddr3PadNames.dqsWordWidth)
            }
            when(timer === 3.U(24)) {
                dqOutReg := wordNode(Seq(6, 7, -1, -1))
                dqsOutReg := dqsWordAt(2).U(Ddr3PadNames.dqsWordWidth)
            }
            when(timer === 4.U(24)) {
                dqOutReg := 0.U(Ddr3PadNames.dqWordWidth)
                dqsOutReg := dqsWordAt(3).U(Ddr3PadNames.dqsWordWidth)
                expectedReg := expectedBurst
            }
            when(timer === 6.U(24)) {
                timer := 0.U(24)
                state := sWrTail.U(5)
            }
        }

        // The write recovery: the precharge waits out the rest of tWR past the burst's end, and the strobe returns to
        // its idle level for whatever comes next (between writes nothing else drives it).
        when(state === sWrTail.U(5)) {
            when(timer === (wrTailCycles - 1).U(24)) {
                dqsOutReg := dqsIdle.U(Ddr3PadNames.dqsWordWidth)
                timer := 0.U(24)
                state := sPre.U(5)
            }
        }

        // Precharge all banks: A10 high (the address decode's own entry).
        when(state === sPre.U(5)) {
            timer := 0.U(24)
            state := sPreWait.U(5)
        }

        when(state === sPreWait.U(5)) {
            when(timer === (trpCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sAct2.U(5)
            }
        }
        when(state === sAct2.U(5)) {
            timer := 0.U(24)
            state := sAct2Wait.U(5)
        }
        when(state === sAct2Wait.U(5)) {
            when(timer === (trcdCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sRd.U(5)
            }
        }

        // The read: the command, the capture window, then the check — one cycle after the window's last shift landed,
        // because what it compares is the register's new value.
        val captured = RegInit(0.U(captureBits))

        // The window's enable, as a register of its own instead of the comparison in the enable cone: what the
        // comparison enables is the whole 704-bit capture register, and the merge chain's route across its flops was
        // the placed design's worst path (measured: 23 ps over the controller's own clock). A register's output is one
        // net, and the tool may replicate it per bank of flops freely. The comparisons keep their own cycle: the
        // register's value during cycle k is the comparison of cycle k − 1, so the window's bounds are one lower here
        // and the same cycles shift as before.
        val captureOpen = RegInit(false.B)
        captureOpen := (state === sRd.U(5)) &
            (timer >= (captureFirst - 1).U(24)) &
            (timer <= (captureLast - 1).U(24))

        // The window the check judged, latched at the check and held for the whole next round: the capture register is
        // rewritten by the next round's own shifts, and an ILA's sample instant is always some fixed phase of a round,
        // so the only way a capture can show what the compare actually read — the thing a board's read timing has to
        // be diagnosed from — is a register that does not move between checks.
        val checked = RegInit(0.U(captureBits))

        /** The capture word's bits, gathered per sample: sample `m`'s lane `i` bit is the pad ring's `ISERDES` slot
          * bit. The deserializer hands a lane's four slots over in *reverse* time order — measured on this design's own
          * ring, the cycle's last beat arrives in the first slot and its first beat in the last — which is what the
          * window wants anyway: sample 0 is the newest, so the gather is the identity and the word reads newest-first —
          * its low bits are the end of the burst.
          */
        def sampleBit(k: Int): Node[Bits] = dqIn.asBits.bit(4 * (k % 16) + k / 16).asBits

        val sampleWord: Node[Bits] = (63 to 0 by -1).map(sampleBit).reduce((high, low) => high ## low)

        // The window's offsets' matches, one bit each, in a register of their own: the window is written once per round,
        // so a cycle of latency costs nothing, and it is what shortens the cone the counters and the alignment read —
        // unregistered, the 13 comparisons' merge chain was the design's worst fabric path (measured: `captured_reg →
        // rounds_reg` missed the controller clock's setup by a nanosecond). The check below therefore runs one cycle
        // after the window closes: a cycle late is a cycle still inside the window's own quiet time.
        val matched = Reg(Vec(checkOffsets, Bool()))
        // The burst, in the two forms the capture can deliver: the whole eight beats as one 128-bit slice, or either
        // of its two 64-bit halves on its own — the board's own ISERDES hands one word of the burst over cleanly at
        // most taps and the other off the eye, so a round counts when the window carries the whole burst or either
        // half of it. The offset and the form are what the window itself reports, so neither is inferred.
        val expectedLow = expectedReg.asBits.bits(63, 0).asUInt
        val expectedHigh = expectedReg.asBits.bits(127, 64).asUInt
        for k <- 0 until checkOffsets do
            val whole = captured.asBits.bits(16 * k + 127, 16 * k)
            val low = captured.asBits.bits(16 * k + 63, 16 * k)
            val high = captured.asBits.bits(16 * k + 127, 16 * k + 64)
            matched(k) := (whole.asUInt === expectedReg) |
                (low.asUInt === expectedLow) |
                (high.asUInt === expectedHigh)

        when(state === sRd.U(5)) {
            when(captureOpen) {
                val shifted: Node[Bits] = captured.asBits.bits(captureBits - Ddr3PadNames.dqWordWidth - 1, 0)
                captured := (shifted ## sampleWord).asUInt
            }
            when(timer === (captureLast + 2).U(24)) {
                // The check: the written burst has to appear at one of the window's offsets. The first offset that
                // holds is the alignment; none means the round missed, and the window itself says what arrived. What
                // it reads is the registered matches above, so the whole check is a priority chain over a few bits.
                // The first offset that holds, as a value: a chain of selections, not of regions. Offsets are 0..36,
                // so 63 is "none": whether the round passed is exactly whether this is not that sentinel.
                checked := captured
                val aligned = muxCase(63.U(6), (0 until checkOffsets).map(k => matched(k) -> k.U(6)))
                val pass = aligned =/= 63.U(6)
                alignedAt := aligned
                // The sweep's own map is the calibration's: once the taps are locked the rounds keep running, and a map
                // of them would be a map of the sweep's leftover index instead.
                when(calibrating) {
                    recent := (recent.asBits.bits(94, 0) ## pass.asBits).asUInt
                }
                when(pass) {
                    // The window the pass stood in, per plane: its first step and its last.
                    when(calibrating & (calibPlane === 0.U(2))) {
                        when(!planeGotEarly) {
                            planeFirstEarly := tap
                            planeGotEarly := true.B
                        }
                        planeLastEarly := tap
                    }
                    when(calibrating & (calibPlane === 1.U(2))) {
                        when(!planeGotOn) {
                            planeFirstOn := tap
                            planeGotOn := true.B
                        }
                        planeLastOn := tap
                    }
                    when(calibrating & (calibPlane === 2.U(2))) {
                        when(!planeGotBack) {
                            planeFirstBack := tap
                            planeGotBack := true.B
                        }
                        planeLastBack := tap
                    }
                }.otherwise {
                    when(calibrating) {
                        // Plane-major: bit `32 * plane + step` of the pair this round stood at, and the plane's own
                        // count, which is what the lock weighs.
                        failTaps := (failTaps.asBits | (1.U(96).asBits << roundIn).bits(95, 0)).asUInt
                        when(calibPlane === 0.U(2)) {
                            planeMissEarly := constAdder(planeMissEarly, 1, 32)
                        }
                        when(calibPlane === 1.U(2)) {
                            planeMissOn := constAdder(planeMissOn, 1, 32)
                        }
                        when(calibPlane === 2.U(2)) {
                            planeMissBack := constAdder(planeMissBack, 1, 32)
                        }
                    }
                }
                // And the verdict leaves for the campaign: it holds from here to the next check, so the round's own
                // edge — the `sRound` cycle next — is where the campaign reads it. Its `rounds`/`misses` and everything
                // else it accounts are read off this one bit.
                verdict := pass
                timer := 0.U(24)
                state := sRound.U(5)
            }
        }

        when(state === sRound.U(5)) {
            refresh := constAdder(refresh, 1, 6)
            // The calibration's own end: the sweep has by now carried the campaign's 64 pairs over all three planes and
            // every step, so what the lock reads off the windows is a board's own verdict, not a moment's.
            when(calibrating & (blocksNext === 64.U(32))) {
                calibrating := false.B
            }
            // The sweep steps; the campaign's own counters step on the edge it exports (`campRoundEdge`, this cycle),
            // so both leave the round together.
            roundIn := roundInNext
            blocks := blocksNext
            timer := 0.U(24)
            // The round opens with a precharge: the read before it left its bank activated, and the refresh — when due
            // — wants every bank closed, so both paths go through the leading precharge first.
            state := sPreLead.U(5)
        }

        // A refresh every 24 rounds: rounds are ~230 ns apart here, so this sits inside tREFI (7.8 us) with margin.
        // (The window's own length is what the round grew by; the count was 32 while the window was shorter.)
        when(state === sRefresh.U(5)) {
            timer := 0.U(24)
            state := sRefreshWait.U(5)
        }

        when(state === sRefreshWait.U(5)) {
            when(timer === (trfcCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sAct1.U(5)
            }
        }

        when(state === sPreLead.U(5)) {
            timer := 0.U(24)
            state := sPreLeadWait.U(5)
        }

        when(state === sPreLeadWait.U(5)) {
            when(timer === (trpCycles - 1).U(24)) {
                timer := 0.U(24)
                when(refresh === 23.U(6)) {
                    refresh := 0.U(6)
                    state := sRefresh.U(5)
                }.otherwise {
                    state := sAct1.U(5)
                }
            }
        }

        // ---- what the campaign reads ---------------------------------------------------------------------------------

        // The round's verdict, held from the check to the round's own edge, and the sweep's state beside it: the tap a
        // round ran at is the calibration's own counter, so the campaign attributes what it sees by what this says
        // ([[nzea_fpga.logic.Ddr3Soak]]).
        campPassPort := verdict
        campRoundEdge := state === sRound.U(5)
        campRoundDonePort := roundDone
        campTapPort := tap
        campCalibrating := calibrating
        campLockedTapPort := lockedTap

        // ---- what the ILA watches -------------------------------------------------------------------------------

        // Marked where the signals live, in one step (`Probes.mark` = declare + bind): this module is instantiated
        // once, so the name is the address ([[Probes]] — a shared body's instance would be named by a `watch` at
        // whatever names it instead). Each probe's width is the signal's own, so the core's probe list, the debug
        // script, the rename map and the capture script all come from these declarations.
        Probes.mark("state")(state)
        Probes.mark("initDone")(initDone)
        Probes.mark("alignedAt")(alignedAt)
        Probes.mark("tap")(tap)
        // Which of the high byte lane's tap hypotheses this round weighed: 0 is both lanes at the same tap, 1 the high
        // lane eight steps on, 2 eight steps back. Read beside `failTaps`, it is which hypothesis a capture is of.
        Probes.mark("calibPlane")(calibPlane)
        // The calibration's own conclusion, held after it: whether the sweep is still running, which plane it chose, and
        // the tap it locked the low lane at (the high lane's follows from the plane).
        Probes.mark("calibrating")(calibrating)
        Probes.mark("lockedPlane")(lockedPlane)
        Probes.mark("lockedTap")(lockedTap)
        Probes.mark("recent")(recent)
        Probes.mark("captured")(captured)
        Probes.mark("checked")(checked)
        Probes.mark("failTaps")(failTaps)
        // The sweep's own index — where the tap turn stands — and what the round ran at; the campaign's account of what
        // *it* ran (`combo`, `blocks`, `passMask`, …) is its own, marked where its registers live
        // ([[nzea_fpga.logic.Ddr3Soak]]).
        Probes.mark("roundIn")(roundIn)
        // Measured in the bring-up, not for the round's judgement: the state machine's own cycle counter beside the
        // capture's input word, so an ILA capture can say *which* cycle the read burst reached the fabric in — the
        // board's capture chain delivers it a few cycles later than the vendor's model does.
        Probes.mark("timer")(timer)
        Probes.mark("dqIn")(dqIn)
