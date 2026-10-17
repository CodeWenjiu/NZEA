package nzea_fpga.device.ddr3

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.bus.LiteBusRW
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

/** The bus this controller answers on, stated by the device because the device *is* the bus's slave end
  * ([[nzea_fpga.logic.Ddr3Soak]] is the master that stands in for a core: it imports this rather than restating it).
  *
  * One bus beat is one BL8 burst: 128 bits = 16 bytes = 8 transfers of the x16 bus, which is exactly the shape the
  * write staging and the capture window are built around. The address is a byte address and a burst is 16-byte aligned,
  * so a request's low four bits are the burst's own and a master must keep them zero — the slave drives whole bursts
  * (there is no sub-burst read-modify-write), and `wstrb` is what narrows a write: one bit per byte of the beat,
  * reaching the device as DDR3's `DM`.
  *
  * `user`/`id` are one bit each: the DRAM has one master, and one request outstanding means there is no ordering to
  * label. A fabric in front of it ([[nzea_rtl.bus.LiteBusCrossbar]]) would widen the payload, not this end.
  */
object Ddr3BusShape:
    val addrWidth = 32
    val dataWidth = 128
    val userWidth = 1
    val idWidth = 1

    /** A burst's bytes: the address's low bits are a burst's own, and a bus beat is one whole burst. */
    val burstBytes = 16
    val burstLowBits = 4

    /** The memory's geometry, as a byte address divides: `[row[14:0], bank[2:0], col[9:0], byte[0]]`. */
    val colBits = 10
    val bankBits = 3
    val rowBits = 15

/** The controller's side of the bus: the digital pins ([[Ddr3PadNames]] names them, and [[nzea_fpga.TopIO]] wires them
  * to the pads' modules by name), the one analog knob the bring-up measures — the capture's delay tap — and its front
  * door, the LiteBus slave end a master reads and writes the memory through ([[Ddr3BusShape]]).
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
    // The data mask is one level per transfer (the data grid's four slots a cycle), one pin per byte lane: bit
    // `4 * lane + slot` masks the byte lane `lane` carries in the cycle's transfer `slot` ([[Ddr3Pads]]' slot pads).
    Aligned(Ddr3PadNames.dm, UInt(4 * Ddr3PadNames.dmWidth))
    Aligned(Ddr3PadNames.rasN, UInt(2))
    Aligned(Ddr3PadNames.casN, UInt(2))
    Aligned(Ddr3PadNames.weN, UInt(2))
    Aligned(Ddr3PadNames.cke, Bool())
    Aligned(Ddr3PadNames.odt, Bool())
    Aligned(Ddr3PadNames.resetN, Bool())

    Flipped(Ddr3PadNames.dqIn, UInt(Ddr3PadNames.dqWordWidth))
    Aligned(Ddr3PadNames.delayTap, UInt(Ddr3PadNames.tapWidth * Ddr3PadNames.tapLanes))

    // The front door: the slave end of the bus ([[Ddr3BusShape]]).
    Flipped(
        "bus",
        new LiteBusRW(Ddr3BusShape.addrWidth, Ddr3BusShape.dataWidth, Ddr3BusShape.userWidth, Ddr3BusShape.idWidth)
    )

    /** The bring-up is behind us: the sweep locked its tap *and* the round run at that tap afterwards came back whole,
      * so the bus answers from here on. A master waits for this — or simply leaves its request standing, since
      * `bus.req.ready` is never asserted before it (`alignGot` and the lock are what it means).
      */
    Aligned("calibrated", Bool())

class Ddr3Probe(parameter: Ddr3Parameter) extends DVRecord[Ddr3Parameter, Ddr3Layers](parameter)

/** The board's DDR3 device, as memory a bus can read and write: the JEDEC power-up and mode-register sequence once,
  * then its own bring-up — the capture's two board facts measured with the device's *own* pattern — and then a LiteBus
  * slave that turns one request into one BL8 burst ([[Ddr3BusShape]]).
  *
  * The one access shape: precharge, activate, and the burst. A write stages the request's sixteen bytes (the strobe
  * train and the data mask beside them), a read captures the burst and hands back the sixteen bytes that arrived. There
  * is no page state: every access closes the row it opened, so consecutive bursts to one page pay tRP and tRCD again —
  * the page policy is a later step, and correctness is what this one is for. A refresh is inserted between requests (a
  * request is never interrupted inside its own burst) when the refresh timer says tREFI is due.
  *
  * The bring-up is the device's own business, and so is its pattern: a controller that cannot calibrate itself is not a
  * controller (this is what `UG586`'s PHY does in its own words). The tap sweep — 32 steps of the low lane's input
  * delay over the high lane's three hypotheses — writes a fixed 128-bit pattern and checks it, and the tap it locks is
  * the middle of the run of rounds the burst came back **whole** on: half a burst is not data a master could use, so it
  * does not count as a pass. One round then runs *at the locked taps*, and its window is what the read path keeps: the
  * *alignment* — the sample offset inside the capture window the burst arrives at — moves with the tap by up to a
  * couple of samples, so it is that round's offset the bus's reads slice with, and that round's own whole-burst verdict
  * is what `calibrated` means (a board whose locked tap does not carry the burst leaves the bus shut, and a capture
  * shows which window was empty). Both latches are probes.
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

    /** The pattern the bring-up writes: eight *distinct* transfers, each the complement of the one before — a lane's
      * level then alternates every transfer, the densest timing a bus can carry, while no four consecutive transfers
      * repeat anywhere in the eight (a window holds at most one burst, so the alignment, the halves and the loopback
      * are all unambiguous). Distinctness is not decoration: an alternating two-value pattern is *blind* to a one-beat
      * slip inside a four-transfer word — its halves still match — which is exactly the defect the window probes caught
      * on the board (seven of eight transfers clean, one repeated), and that blindness hid it through a whole bring-up.
      * Its job is to find where *this* comes back, not to weigh patterns — that is the master's job
      * ([[nzea_fpga.logic.Ddr3Soak]]).
      */
    private val calBeats: Seq[BigInt] =
        Seq(0x1234, 0xedcb, 0x0f3c, 0xf0c3, 0x5a96, 0xa569, 0x2d4b, 0xd2b4).map(BigInt(_))

    /** The same pattern as one 128-bit word, beat 0 at the top — the shape the capture window compares against. */
    private val calExpected: BigInt = calBeats.reduceLeft((high, low) => (high << 16) | low)

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
        val calibratedPort = io.field[Bool]("calibrated")

        val bus = io.field[LiteBusRW]("bus")

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
        // clear that, and 300 ns is the number the access's shape allows.
        val trfcCycles = 60
        // One refresh every 7.5 us of bus time: inside tREFI (7.8 us) with room for the refresh's own tRFC. The timer
        // counts cycles and is read only in the idle state — a request that has been accepted is never interrupted; a
        // refresh that falls due mid-access waits for the next idle.
        val refreshIntervalCycles = 1500

        /** A strobe word: bit `4i + k` is lane `i`'s level in slot `k` (the pads expand a level per beat). */
        def dqsWord(slots: Seq[Int]): BigInt =
            (0 until Ddr3PadNames.dqsWidth)
                .map(lane => slots.zipWithIndex.map { case (level, k) => BigInt(level) << (4 * lane + k) }.sum)
                .sum

        // The strobe's words. The train's opening frame is low, low, then the first rising edge, then low: the
        // device wants its preamble (tWPRE, three quarters of a clock at least) before the burst's first edge, and
        // that edge is the one the toggling word that follows opens on. The toggling word is the burst itself — a
        // transition on every beat centre — the tail carries the last transition and leaves the strobe low (the
        // postamble), and the hold-low and idle levels frame it. The initial output register settles high, the
        // strobe's idle.
        val dqsIdle = dqsWord(Seq(1, 1, 1, 1))
        // The train as one flat level list, with two slots of zeros beyond its tail: the write's own alignment cuts this
        // list a slot either side of the nominal phase ([[Ddr3.writeShift]]), so the cut needs room off the end.
        val dqsFlat =
            Seq(1, 1, 1, 1) ++ Seq(0, 0, 1, 0) ++ Seq(1, 0, 1, 0) ++ Seq(1, 0, 0, 0) ++ Seq(0, 0, 0, 0) ++
                Seq(0, 0)

        /** One lane's strobe word, cut from the train at the word the write stages at `stage` (0 = the opening frame):
          * bit `4 * lane + k` is the lane's level in slot `k`. `shift` cuts the same train that many slots further
          * along the pin grid — the whole train moves, the opening fall and the preamble with it.
          */
        def dqsLaneWord(flat: Seq[Int], lane: Int, stage: Int, shift: Int): BigInt =
            flat.slice(4 + 4 * stage - shift, 8 + 4 * stage - shift)
                .zipWithIndex
                .map((level, k) => BigInt(level) << (4 * lane + k))
                .sum

        /** The two lanes' strobe word at `stage`, the same train cut at the same phase for both. */
        def dqsWordAt(stage: Int, shift: Int): BigInt =
            (0 until Ddr3PadNames.dqsWidth).map(lane => dqsLaneWord(dqsFlat, lane, stage, shift)).sum

        // The window spans the worst case its own fixed phase cannot tune away: the read burst's arrival at the capture
        // register is the device's read latency plus the ISERDES's own, and measured on the board the pair lands about
        // two cycles later than the vendor's model puts them. Thirteen cycles (52 samples, offsets 0..44) cover both —
        // the model's burst sits about a quarter of the window in from its end, the board's two cycles further — and
        // what the window cannot catch is a factor of two, which the tap sweep would show as a dead map. (Measured on
        // the board: an 11-cycle window held the burst's head and cut its tail off, so no tap ever saw a *whole* burst
        // — the sweep needs the whole eight beats, not the four the old half-tolerant criterion settled for. The size
        // is a timing fact too: every sample is sixteen flops on the controller's clock, and the state decode they
        // hang off was the placed design's congested corner.)
        val captureFirst = 5
        val captureLast = 17
        val captureSamples = (captureLast - captureFirst + 1) * Ddr3PadNames.beats
        val captureBits = captureSamples * 16
        val checkOffsets = captureSamples - 8 + 1

        // The bring-up's glide path first: reset, CKE, the mode registers, the DLL and ZQ, and then the two loops —
        // the calibration's rounds (activate, write, precharge, activate, read, check; `sPreLead` .. `sRound`) and the
        // bus's accesses (`sBusIdle` ..). Every command state is one cycle wide, and the waits between them are the
        // device's own numbers.
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
        // The bus's own states: an access is precharge, activate and the burst; a refresh is its own little sequence.
        val sBusIdle = 25
        val sBusPre = 26
        val sBusPreWait = 27
        val sBusAct = 28
        val sBusActWait = 29
        val sBusWr = 30
        val sBusWrTail = 31
        val sBusRd = 32
        val sBusRefPre = 33
        val sBusRefPreWait = 34
        val sBusRef = 35
        val sBusRefWait = 36
        val sBusWidth = 6

        val state = RegInit(sReset.U(sBusWidth))
        val timer = RegInit(0.U(24))
        timer := constAdder(timer, 1, 24)

        // The command/address bus is a value, decided once per cycle from the state: a command is the cycle its state
        // says so, and every other cycle is the NOP the device expects between commands (its chip select is tied low
        // on this board, so every cycle is a command cycle). Every pin is assigned exactly once.
        val mrsState =
            (state === sMrs2.U(sBusWidth)) | (state === sMrs3.U(sBusWidth)) | (state === sMrs1.U(sBusWidth)) |
                (state === sMrs0.U(sBusWidth))
        val actState = (state === sAct1.U(sBusWidth)) | (state === sAct2.U(sBusWidth))
        val writeState = state === sWr.U(sBusWidth)
        val readState = state === sRd.U(sBusWidth)
        val preState = (state === sPre.U(sBusWidth)) | (state === sPreLead.U(sBusWidth))
        val zqState = state === sZq.U(sBusWidth)
        val refreshState = state === sRefresh.U(sBusWidth)

        // The bus's command states: the same five commands, decided by the bus's own states so an access and the
        // calibration's rounds never share a decode.
        val busPreState = state === sBusPre.U(sBusWidth)
        val busActState = state === sBusAct.U(sBusWidth)
        val busWriteState = state === sBusWr.U(sBusWidth)
        val busReadState = state === sBusRd.U(sBusWidth)
        val busRefPreState = state === sBusRefPre.U(sBusWidth)
        val busRefreshState = state === sBusRef.U(sBusWidth)

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
        val statePrev = RegInit(sReset.U(sBusWidth))
        statePrev := state
        val commandCycle = state =/= statePrev
        val mrsCmd = mrsState & commandCycle
        val actCmd = (actState | busActState) & commandCycle
        val wrCmd = (writeState | busWriteState) & commandCycle
        val rdCmd = (readState | busReadState) & commandCycle
        val preCmd = (preState | busPreState | busRefPreState) & commandCycle
        val refCmd = (refreshState | busRefreshState) & commandCycle
        val zqCmd = zqState & commandCycle

        // The address a burst runs at, as the row/bank/column the commands carry: the calibration's rounds take the
        // device's own baseline row (all zero), the bus's accesses the request's address decoded
        // ([[Ddr3BusShape]] holds the bit map). The three are registers, so the command decode reads registers only.
        val rowSel = RegInit(0.U(Ddr3PadNames.addrWidth))
        val colSel = RegInit(0.U(Ddr3PadNames.addrWidth))
        val baSel = RegInit(0.U(Ddr3PadNames.baWidth))

        // The burst's data and mask: the calibration's fixed pattern, or the request's sixteen bytes — beat `k` is
        // bus bits `16k + 15:16k`, which is also the transfer order the write stages in, so the bus's byte order and
        // the burst's are the same statement ([[Ddr3BusShape]]).
        val busWdata = RegInit(0.U(Ddr3BusShape.dataWidth))
        val busWstrb = RegInit(0.U(Ddr3BusShape.dataWidth / 8))
        val busWen = RegInit(false.B)
        val busUser = RegInit(0.U(Ddr3BusShape.userWidth))
        val busId = RegInit(0.U(Ddr3BusShape.idWidth))

        /** The command/address side presents *two* beats per cycle, and the device reads one command per clock, so a
          * command occupies a cycle's first beat and the second beat is the deselect that closes it: every control line
          * high, the address low. `pairOf` interleaves two levels into that pair — bit `2i` a pin's first beat, `2i +
          * 1` its second — and `3` is a control line high in both beats (the deselect's own level).
          */
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
                sMrs2.U(sBusWidth) -> pairOf(mr2, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                sMrs3.U(sBusWidth) -> pairOf(mr3, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                sMrs1.U(sBusWidth) -> pairOf(mr1, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                sMrs0.U(sBusWidth) -> pairOf(mr0, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                sPre.U(sBusWidth) -> pairOf(nopAddr, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                sPreLead.U(sBusWidth) -> pairOf(nopAddr, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                sZq.U(sBusWidth) -> pairOf(nopAddr, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                sBusPre.U(sBusWidth) -> pairOf(nopAddr, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                sBusRefPre.U(sBusWidth) -> pairOf(nopAddr, 0, Ddr3PadNames.addrWidth).U(addrWidth),
                // The burst's own address: the activate carries the row, the write and the read carry the column.
                sAct1.U(sBusWidth) -> pairNode(rowSel, Ddr3PadNames.addrWidth),
                sAct2.U(sBusWidth) -> pairNode(rowSel, Ddr3PadNames.addrWidth),
                sWr.U(sBusWidth) -> pairNode(colSel, Ddr3PadNames.addrWidth),
                sRd.U(sBusWidth) -> pairNode(colSel, Ddr3PadNames.addrWidth),
                sBusAct.U(sBusWidth) -> pairNode(rowSel, Ddr3PadNames.addrWidth),
                sBusWr.U(sBusWidth) -> pairNode(colSel, Ddr3PadNames.addrWidth),
                sBusRd.U(sBusWidth) -> pairNode(colSel, Ddr3PadNames.addrWidth)
            )
        )
        ba := muxLookup(state, 0.U(baWidth))(
            Seq(
                sMrs2.U(sBusWidth) -> pairOf(2, 0, Ddr3PadNames.baWidth).U(baWidth),
                sMrs3.U(sBusWidth) -> pairOf(3, 0, Ddr3PadNames.baWidth).U(baWidth),
                sMrs1.U(sBusWidth) -> pairOf(1, 0, Ddr3PadNames.baWidth).U(baWidth),
                sMrs0.U(sBusWidth) -> pairOf(0, 0, Ddr3PadNames.baWidth).U(baWidth),
                // The activated bank, the written and the read one: a READ or WRITE names the bank its column applies
                // to, so the access's own bank rides every command of its row.
                sAct1.U(sBusWidth) -> pairNode(baSel, Ddr3PadNames.baWidth),
                sAct2.U(sBusWidth) -> pairNode(baSel, Ddr3PadNames.baWidth),
                sWr.U(sBusWidth) -> pairNode(baSel, Ddr3PadNames.baWidth),
                sRd.U(sBusWidth) -> pairNode(baSel, Ddr3PadNames.baWidth),
                sBusAct.U(sBusWidth) -> pairNode(baSel, Ddr3PadNames.baWidth),
                sBusWr.U(sBusWidth) -> pairNode(baSel, Ddr3PadNames.baWidth),
                sBusRd.U(sBusWidth) -> pairNode(baSel, Ddr3PadNames.baWidth)
            )
        )
        val rasCmd = mrsCmd | actCmd | preCmd | refCmd
        val casCmd = mrsCmd | wrCmd | rdCmd | refCmd
        val weCmd = mrsCmd | wrCmd | preCmd | zqCmd
        // A control line is low only in the beat its command occupies; the deselect level (`1`) fills the other beat.
        rasN := muxCase[UInt](3.U(2), Seq(rasCmd -> 2.U(2)))
        casN := muxCase[UInt](3.U(2), Seq(casCmd -> 2.U(2)))
        weN := muxCase[UInt](3.U(2), Seq(weCmd -> 2.U(2)))

        // The mask is staged with the data (one level per transfer) and a read leaves it low: the bus is the device's.
        val dmOutReg = RegInit(0.U(4 * Ddr3PadNames.dmWidth))
        dm := dmOutReg
        // ODT is high for the write's own states — the whole burst and its recovery — and low everywhere else (reads
        // and idles), which is what the device's `ODT Off During READs` asks. Both loops' write states count.
        odt := (state === sWr.U(sBusWidth)) | (state === sWrTail.U(sBusWidth)) |
            (state === sBusWr.U(sBusWidth)) | (state === sBusWrTail.U(sBusWidth))

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
        dqOe := !(readState | busReadState)
        dqsOut := dqsOutReg
        dqsOe := !(readState | busReadState)

        when(state === sReset.U(sBusWidth)) {
            when(timer === (resetCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sCkeWait.U(sBusWidth)
                ddrResetNReg := true.B
            }
        }

        when(state === sCkeWait.U(sBusWidth)) {
            when(timer === (ckeCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sCke.U(sBusWidth)
            }
        }

        when(state === sCke.U(sBusWidth)) {
            ckeReg := true.B
            timer := 0.U(24)
            state := sTxp.U(sBusWidth)
        }

        when(state === sTxp.U(sBusWidth)) {
            when(timer === (txprCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sMrs2.U(sBusWidth)
            }
        }

        // A mode register set's cycle is the MRS states' own (the address and bank the decode above selects); the
        // waits between them are `tMRD`, in the order this device documents (MR2 -> MR3 -> MR1 -> MR0).
        //
        // The waits are written out at every state, not shared through a helper: a `when` a helper creates and a
        // caller places inside another `when` loses its enclosing condition (measured — the transition's own wire
        // came out as `timer == 7`, the state term gone), so every guarded region here is spelled where it guards.
        when(state === sMrs2.U(sBusWidth)) {
            when(timer === (mrdCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sMrs3.U(sBusWidth)
            }
        }
        when(state === sMrs3.U(sBusWidth)) {
            when(timer === (mrdCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sMrs1.U(sBusWidth)
            }
        }
        when(state === sMrs1.U(sBusWidth)) {
            when(timer === (mrdCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sMrs0.U(sBusWidth)
            }
        }
        when(state === sMrs0.U(sBusWidth)) {
            // tMOD closes the register writes, tDLLK the DLL reset they asked for.
            when(timer === (dllkCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sDll.U(sBusWidth)
            }
        }

        when(state === sDll.U(sBusWidth)) {
            timer := 0.U(24)
            state := sZq.U(sBusWidth)
        }

        // The ZQ calibration's cycle is the ZQ state's own; tZQinit follows before anything else is issued.
        when(state === sZq.U(sBusWidth)) {
            timer := 0.U(24)
            state := sZqWait.U(sBusWidth)
        }

        val initDone = RegInit(false.B)

        when(state === sZqWait.U(sBusWidth)) {
            when(timer === (zqinitCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sPreLead.U(sBusWidth)
                initDone := true.B
            }
        }

        // The sweep's own stepping: `roundIn` walks the 32 steps of the low lane's input delay and, above them, which
        // of the high lane's three hypotheses a round weighs (the high lane's tap is the low lane's plus the plane's
        // step, below). One turn of it is all the bring-up needs — the pattern is the device's own, so a second turn
        // would only repeat it.
        //
        // The three hypotheses are the low lane's tap, that tap eight steps on (625 ps: the step the strobe's own
        // launch needed on this board, so the same board fact is the one to weigh here), and eight steps back. `UG586`
        // centres the read path per byte group because each group's delay against the fabric's capture clock is its
        // own; the sweep is what says how far this board's two groups are apart.
        val roundIn = RegInit(0.U(7))
        val roundDone = roundIn === 95.U(7)
        val roundInNext = roundDone ? (0.U(7), constAdder(roundIn, 1, 7))
        // One bit per round, newest first: the sweep's own map, a block's worth of rounds in the ILA at once.
        val recent = RegInit(0.U(96))
        // The calibration's refresh counter, in rounds: a refresh is inserted every 24 of them ([[sPreLeadWait]]), which
        // is what keeps tREFI met while the sweep runs and no bus request is there to be interrupted.
        val refresh = RegInit(0.U(6))

        // The round's place in the sweep, read out of its index: the low five bits are the low byte lane's tap, the
        // pair above them the high lane's hypothesis.
        val tap = roundIn.asBits.bits(4, 0).asUInt
        val calibPlane = roundIn.asBits.bits(6, 5).asUInt
        // The stepped columns are tables rather than an adder's output: they are constants of the tap, so the tap's
        // own path to the pads stays wiring.
        def stepped(from: Referable[UInt], delta: Int)(using Arena, Context, Block): Node[UInt] =
            muxTree(from, (0 until 32).map(i => (((i + delta) % 32 + 32) % 32).U(Ddr3PadNames.tapWidth)))
        val highTap = muxCase(
            tap,
            Seq(
                (calibPlane === 1.U(2)) -> stepped(tap, 8),
                (calibPlane === 2.U(2)) -> stepped(tap, -8)
            )
        )
        val highTapOn = calibPlane === 1.U(2)
        val highTapBack = calibPlane === 2.U(2)

        // What the window found, and the two things the bring-up latches out of it: the alignment — the offset the
        // burst arrives at, a fixed read-latency fact, kept for the bus's reads — and whether a whole burst was ever
        // found, which is what lets the bus answer at all.
        val alignedAt = RegInit(63.U(6))
        val halfAt = RegInit(63.U(6))
        // The check's verdict, held from its own cycle to the bookkeeping's (one cycle later — [[sRd]]): the registered
        // result is what the counters, the window registers and the map read, so the priority chains over the window
        // end at flops instead of at the adders.
        val passReg = RegInit(false.B)
        val alignedReg = RegInit(63.U(6))
        val alignLocked = RegInit(0.U(6))
        val alignGot = RegInit(false.B)

        // The calibration's own record of what it saw: which (plane, step) pairs the sweep a miss has stood at,
        // plane-major (`32 * plane + step`), so one capture holds the read window of all three of the high lane's
        // hypotheses at once.
        val failTaps = RegInit(0.U(3 * (1 << Ddr3PadNames.tapWidth)))

        // The calibration's own end: the sweep has carried one whole turn, so what the lock reads off the windows is
        // the board's verdict, not a moment's. What `calibrated` means is narrower than the sweep's end though: after
        // the sweep the device runs *one more round at the locked taps* ([[ownPattern]] selects its pattern), and that
        // round's own match is what opens the bus — the alignment the response slices at is a fact of the tap the bus
        // runs at, and the sweep never read at the locked one.
        val calibrating = RegInit(true.B)
        val calibrated = RegInit(false.B)
        calibratedPort := calibrated
        // Whether the turn of the sweep that is running now has found anything: a turn of misses is a turn whose write
        // alignment is not this board's, and the sweep repeats at the next one ([[writeShift]]) instead of ending.
        val turnPass = RegInit(false.B)
        // The post-lock round: running, and its own verdict (the two bits `calibrated` is made of).
        val finalRound = RegInit(false.B)
        val finalPass = RegInit(false.B)
        // Whose burst it is: the bring-up's own pattern while either runs, the request's word after.
        val ownPattern = calibrating | finalRound
        val planeMissEarly = RegInit(0.U(32))
        val planeMissOn = RegInit(0.U(32))
        val planeMissBack = RegInit(0.U(32))
        // The window each plane's rounds came through at, latched as the sweep passes: the first step a pass stood at
        // and the last one, so the lock's arithmetic reads two registers instead of a chain over the whole map (that
        // chain, from a 96-bit register through 192 conditions, was this design's worst path by 11.5 ns against its
        // 5 ns clock). The map is still what a capture reads the window itself from; this is only how the lock reads it.
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
        // The high lane's tap at the locked plane, off the register: a table and a two-way mux.
        val lockedHigh = muxCase(
            lockedTap,
            Seq(
                (lockedPlane === 1.U(2)) -> stepped(lockedTap, 8),
                (lockedPlane === 2.U(2)) -> stepped(lockedTap, -8)
            )
        )
        // The delay lines' tap: the sweep's own stepping *while it steps*, and the locked pair from the post-lock
        // round on. The select is `calibrating`, not `ownPattern`: the post-lock round's whole point is to measure the
        // alignment *at the taps the bus runs at*, and by the time it starts the sweep's index has wrapped to zero —
        // keyed off `ownPattern` the round ran at tap 0 while the bus read at the locked tap. The difference is a whole
        // sample of the window (on this board the lock sat 15 taps, about 1.2 ns, from zero), so the alignment it
        // latched was one sample off for the bus: measured on the board, the soak's walk came back shifted a beat
        // (`0001_0002_0004_0008` read as `0000_0001_0002_0004`).
        delayTap := calibrating ? (
            (highTap.asBits ## tap.asBits).asUInt,
            (lockedHigh.asBits ## lockedTap.asBits).asUInt
        )

        // Where the strobe's train lands against the data it frames is a fact of the pads' launch, not of the RTL: the
        // device captures the whole burst on the train's edges, and the design stages the train and the data on two
        // clock grids a quarter period apart — half a data slot — so that every edge falls on a transfer's centre
        // instead of on its boundary. Where those two grids *actually* land relative to each other on the pins is what
        // the primitives do between their load edge and their first slot, and that is not the same in every world this
        // design runs in. Measured: on the board the device stores the padding as the burst's first transfer and the
        // seventh transfer as its last — the train arrives one whole data slot earlier than the plan reads it — while
        // the vendor models still call the nominal phase correct (`Ddr3BringUp` runs clean against them; the model's own
        // "latching edge required" notice is what that leniency looks like). The read path has its knob for the same
        // kind of board fact (the tap sweep); this is the write's: the train is cut at the nominal phase, one slot
        // later, or one slot earlier, and the calibration's own check — a whole eight-transfer burst in the window — is
        // what weighs them. The nominal phase is tried first, so a world where it is right (the models) never leaves the
        // first turn.
        val writeShift = RegInit(0.U(2))

        /** The strobe's word for `stage` at the alignment being weighed: 0 = nominal, 1 = one slot later,
          * 2 = one slot earlier. The words are constants of the (stage, shift) pair, so the select is a two-level mux
          * over literals and nothing wider reaches the pads' own path.
          */
        def stagedDqsWord(stage: Int)(using Arena, Context, Block): Referable[UInt] =
            val nominal = dqsWordAt(stage, 0).U(Ddr3PadNames.dqsWordWidth)
            val later = dqsWordAt(stage, 1).U(Ddr3PadNames.dqsWordWidth)
            val earlier = dqsWordAt(stage, -1).U(Ddr3PadNames.dqsWordWidth)
            (writeShift === 2.U(2)) ? (earlier, (writeShift === 1.U(2)) ? (later, nominal))

        /** Beat `k` of the burst being written: the bring-up's own pattern while it runs, the request's word after —
          * and the request's word is *the same sixteen bits* a read hands back, so a master's data round-trips by
          * construction ([[Ddr3BusShape]]).
          */
        def beatWord(k: Int)(using Arena, Context, Block): Node[UInt] =
            ownPattern ? (calBeats(k).U(16), busWdata.asBits.bits(16 * k + 15, 16 * k).asUInt)

        /** Whether byte lane `pin` is masked in transfer `k` (1 = not written). The bring-up masks nothing. */
        def dmLevel(pin: Int, k: Int)(using Arena, Context, Block): Node[Bool] =
            ownPattern ? (false.B, !busWstrb.asBits.bit(2 * k + pin))

        /** A cycle's data word: bit `4i + k` is lane `i`'s bit of the transfer in slot `k`, with the slots the burst
          * does not fill held at zero (the burst's first beat is centred on the serializer's third slot, so the words
          * the write opens and closes with carry padding).
          */
        def wordNode(slots: Seq[Int])(using Arena, Context, Block): Node[UInt] =
            (63 to 0 by -1)
                .map(pos => {
                    val lane = pos / 4
                    val k = pos % 4
                    if slots(k) < 0 then Node(0.U(1).asBits) else beatWord(slots(k)).asBits.bit(lane).asBits
                })
                .reduce((high, low) => high ## low)
                .asUInt

        /** The same for the mask: a pin's level in a slot is its byte lane's mask for the transfer staged there. */
        def dmWord(slots: Seq[Int])(using Arena, Context, Block): Node[UInt] =
            (4 * Ddr3PadNames.dmWidth - 1 to 0 by -1)
                .map(pos => {
                    val pin = pos / 4
                    val k = pos % 4
                    if slots(k) < 0 then Node(0.U(1).asBits) else dmLevel(pin, slots(k)).asBits
                })
                .reduce((high, low) => high ## low)
                .asUInt

        when(state === sAct1.U(sBusWidth)) {
            timer := 0.U(24)
            state := sAct1Wait.U(sBusWidth)
        }
        when(state === sAct1Wait.U(sBusWidth)) {
            when(timer === (trcdCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sWr.U(sBusWidth)
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
        when(state === sWr.U(sBusWidth)) {
            when(timer === 1.U(24)) {
                dqOutReg := wordNode(Seq(-1, -1, 0, 1))
                dmOutReg := dmWord(Seq(-1, -1, 0, 1))
                dqsOutReg := stagedDqsWord(0)
            }
            when(timer === 2.U(24)) {
                dqOutReg := wordNode(Seq(2, 3, 4, 5))
                dmOutReg := dmWord(Seq(2, 3, 4, 5))
                dqsOutReg := stagedDqsWord(1)
            }
            when(timer === 3.U(24)) {
                dqOutReg := wordNode(Seq(6, 7, -1, -1))
                dmOutReg := dmWord(Seq(6, 7, -1, -1))
                dqsOutReg := stagedDqsWord(2)
            }
            when(timer === 4.U(24)) {
                dqOutReg := 0.U(Ddr3PadNames.dqWordWidth)
                dmOutReg := 0.U(4 * Ddr3PadNames.dmWidth)
                dqsOutReg := stagedDqsWord(3)
            }
            when(timer === 6.U(24)) {
                timer := 0.U(24)
                state := sWrTail.U(sBusWidth)
            }
        }

        // The write recovery: the precharge waits out the rest of tWR past the burst's end, and the strobe returns to
        // its idle level for whatever comes next (between writes nothing else drives it).
        when(state === sWrTail.U(sBusWidth)) {
            when(timer === (wrTailCycles - 1).U(24)) {
                dqsOutReg := dqsIdle.U(Ddr3PadNames.dqsWordWidth)
                timer := 0.U(24)
                state := sPre.U(sBusWidth)
            }
        }

        // Precharge all banks: A10 high (the address decode's own entry).
        when(state === sPre.U(sBusWidth)) {
            timer := 0.U(24)
            state := sPreWait.U(sBusWidth)
        }

        when(state === sPreWait.U(sBusWidth)) {
            when(timer === (trpCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sAct2.U(sBusWidth)
            }
        }
        when(state === sAct2.U(sBusWidth)) {
            timer := 0.U(24)
            state := sAct2Wait.U(sBusWidth)
        }
        when(state === sAct2Wait.U(sBusWidth)) {
            when(timer === (trcdCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sRd.U(sBusWidth)
            }
        }

        val captured = RegInit(0.U(captureBits))

        // The window's enable, as a register of its own instead of the comparison in the enable cone: what the
        // comparison enables is the whole 704-bit capture register, and the merge chain's route across its flops was
        // the placed design's worst path (measured: 23 ps over the controller's own clock). A register's output is one
        // net, and the tool may replicate it per bank of flops freely. The comparisons keep their own cycle: the
        // register's value during cycle k is the comparison of cycle k − 1, so the window's bounds are one lower here
        // and the same cycles shift as before.
        val captureOpen = RegInit(false.B)
        captureOpen := ((state === sRd.U(sBusWidth)) | (state === sBusRd.U(sBusWidth))) &
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

        // The window's offsets' matches, one bit each, in a register of their own: the window is written once per
        // round, so a cycle of latency costs nothing, and it is what shortens the cone the counters and the alignment
        // read — unregistered, the 13 comparisons' merge chain was the design's worst fabric path (measured: `captured_reg
        // → rounds_reg` missed the controller clock's setup by a nanosecond). The check below therefore runs one cycle
        // after the window closes: a cycle late is a cycle still inside the window's own quiet time.
        //
        // What the burst has to be is *whole*: a master asks for sixteen bytes, so half a burst is not a pass — the
        // criterion is the bus's, not the capture's convenience.
        val matched = Reg(Vec(checkOffsets, Bool()))
        for k <- 0 until checkOffsets do
            val whole = captured.asBits.bits(16 * k + 127, 16 * k)
            matched(k) := whole.asUInt === calExpected.U(128)

        // The same for the halves — the pattern alternates every beat, so its two 64-bit halves are one value — at the
        // same 8-beat framing the whole check uses: its low half covers every four-sample window there is, so where one
        // holds is the *measurement* the whole-burst check cannot give (a board whose burst slips out of the window
        // reports "nothing" there, which says neither where the burst is nor how much of it arrived). The high half at
        // the same offset is the same window four samples on, which some other offset's low half already covers — so
        // only one comparison per offset is built, for the flops and the timing.
        val calHalf = calExpected & ((BigInt(1) << 64) - 1)
        val halfMatched = Reg(Vec(checkOffsets, Bool()))
        for k <- 0 until checkOffsets do
            val low = captured.asBits.bits(16 * k + 63, 16 * k)
            halfMatched(k) := low.asUInt === calHalf.U(64)

        // The window's own shift, where the window says it shifts ([[captureOpen]] opens it in *both* readers — the
        // calibration's rounds and the bus's accesses): the capture is the read path itself, not a round's instrument,
        // and a window that only filled during the sweep would leave the bus's reads slicing a stale one.
        when(captureOpen) {
            val shifted: Node[Bits] = captured.asBits.bits(captureBits - Ddr3PadNames.dqWordWidth - 1, 0)
            captured := (shifted ## sampleWord).asUInt
        }

        when(state === sRd.U(sBusWidth)) {
            when(timer === (captureLast + 2).U(24)) {
                // The check: the written burst has to appear at one of the window's offsets. The first offset that
                // holds is the alignment; none means the round missed, and the window itself says what arrived. What
                // it reads is the registered matches above, so the whole check is a priority chain over a few bits.
                checked := captured
                val aligned = muxCase(63.U(6), (0 until checkOffsets).map(k => matched(k) -> k.U(6)))
                val pass = aligned =/= 63.U(6)
                // Where a four-beat half sits, at every check: the sweep's and the post-lock round's alike (the second
                // is the one the bus runs behind). 63 is "nowhere", as in the alignment.
                halfAt := muxCase(63.U(6), (0 until checkOffsets).map(k => halfMatched(k) -> k.U(6)))
                // The check's verdict, one flop further: the chains above are the controller's widest logic (45 slice
                // compares merged into one priority chain), and what they feed — the plane counters' increments, the
                // window registers, the map — is the *next* cycle's block below. Directly, their merged cone into the
                // counters was the placed design's failing path by 27 ps.
                passReg := pass
                alignedReg := aligned
            }
            when(timer === (captureLast + 3).U(24)) {
                alignedAt := alignedReg
                when(calibrating) {
                    recent := (recent.asBits.bits(94, 0) ## passReg.asBits).asUInt
                    when(passReg) {
                        turnPass := true.B
                        // The window the pass stood in, per plane: its first step and its last — and the alignment the
                        // *sweep* found, kept only as a record (`alignGot` says whether one was seen at all). The one
                        // the bus slices at is the post-lock round's own ([[finalRound]]): the alignment moves with the
                        // tap (the delay shifts the burst inside the window by up to a couple of samples), so a value
                        // read at the sweep's taps is not a value for the locked one.
                        when(!alignGot) {
                            alignLocked := alignedReg
                            alignGot := true.B
                        }
                        when(calibPlane === 0.U(2)) {
                            when(!planeGotEarly) {
                                planeFirstEarly := tap
                                planeGotEarly := true.B
                            }
                            planeLastEarly := tap
                        }
                        when(highTapOn) {
                            when(!planeGotOn) {
                                planeFirstOn := tap
                                planeGotOn := true.B
                            }
                            planeLastOn := tap
                        }
                        when(highTapBack) {
                            when(!planeGotBack) {
                                planeFirstBack := tap
                                planeGotBack := true.B
                            }
                            planeLastBack := tap
                        }
                    }.otherwise {
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
                // The post-lock round's own answer: its alignment is the one the bus reads at, and whether it held is
                // what opens the bus ([[sRound]]). Latched whether or not it holds: a board whose locked tap does not
                // carry the burst is a board the bus must not answer on, and `calibrated` is how that is said.
                when(finalRound) {
                    alignLocked := alignedReg
                    finalPass := passReg
                }
                timer := 0.U(24)
                state := sRound.U(sBusWidth)
            }
        }

        when(state === sRound.U(sBusWidth)) {
            // The sweep's end, and then the one round that is not the sweep's: at the locked taps, with the device's own
            // pattern, judged by the check above. What the bus answers on is that round's verdict — the alignment the
            // response slices at and the tap it slices at are then the same configuration the bus runs in.
            //
            // A whole turn that found no pass is a turn whose write alignment is not this board's: the sweep weighs the
            // next one ([[writeShift]]) with its record cleared — the lock's arithmetic reads this turn's windows only —
            // and only a turn that found a pass (or the last alignment there is) ends the calibration. Zero is the first
            // turn, so a board the nominal phase carries locks in it.
            when(calibrating & roundDone) {
                when(!turnPass & (writeShift =/= 2.U(2))) {
                    writeShift := constAdder(writeShift, 1, 2)
                    turnPass := false.B
                    planeMissEarly := 0.U(32)
                    planeMissOn := 0.U(32)
                    planeMissBack := 0.U(32)
                    planeGotEarly := false.B
                    planeGotOn := false.B
                    planeGotBack := false.B
                    planeFirstEarly := 0.U(Ddr3PadNames.tapWidth)
                    planeFirstOn := 0.U(Ddr3PadNames.tapWidth)
                    planeFirstBack := 0.U(Ddr3PadNames.tapWidth)
                    planeLastEarly := 0.U(Ddr3PadNames.tapWidth)
                    planeLastOn := 0.U(Ddr3PadNames.tapWidth)
                    planeLastBack := 0.U(Ddr3PadNames.tapWidth)
                    alignGot := false.B
                    recent := 0.U(96)
                    failTaps := 0.U(3 * (1 << Ddr3PadNames.tapWidth))
                    state := sPreLead.U(sBusWidth)
                }.otherwise {
                    calibrating := false.B
                    finalRound := true.B
                    state := sPreLead.U(sBusWidth)
                }
            }.otherwise {
                when(finalRound) {
                    finalRound := false.B
                    calibrated := finalPass
                    state := sBusIdle.U(sBusWidth)
                }.otherwise {
                    state := sPreLead.U(sBusWidth)
                }
            }
            roundIn := roundInNext
            refresh := constAdder(refresh, 1, 6)
            timer := 0.U(24)
        }

        when(state === sRefresh.U(sBusWidth)) {
            timer := 0.U(24)
            state := sRefreshWait.U(sBusWidth)
        }

        when(state === sRefreshWait.U(sBusWidth)) {
            when(timer === (trfcCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sAct1.U(sBusWidth)
            }
        }

        when(state === sPreLead.U(sBusWidth)) {
            timer := 0.U(24)
            state := sPreLeadWait.U(sBusWidth)
        }

        // A refresh every 24 rounds: rounds are ~230 ns apart here, so this sits inside tREFI (7.8 us) with margin. The
        // round's precharge is where the refresh hangs off: the read before it left its bank activated, and a REF needs
        // every bank closed.
        when(state === sPreLeadWait.U(sBusWidth)) {
            when(timer === (trpCycles - 1).U(24)) {
                timer := 0.U(24)
                when(refresh === 23.U(6)) {
                    refresh := 0.U(6)
                    state := sRefresh.U(sBusWidth)
                }.otherwise {
                    state := sAct1.U(sBusWidth)
                }
            }
        }

        // The response: the access's data (a write's is zero), held until the master takes it. One request is
        // outstanding at a time — the ready below refuses a second while a response is pending — so the single slot
        // cannot overflow.
        val respValid = RegInit(false.B)
        val respData = RegInit(0.U(Ddr3BusShape.dataWidth))
        val respUser = RegInit(0.U(Ddr3BusShape.userWidth))
        val respId = RegInit(0.U(Ddr3BusShape.idWidth))
        val accesses = RegInit(0.U(32))

        // The refresh timer, in cycles of bus time: read only in the idle state (a request is never interrupted), and
        // restarted after every refresh, so what falls due while an access runs waits for the next one.
        val refTimer = RegInit(0.U(24))
        when(calibrated) {
            refTimer := constAdder(refTimer, 1, 24)
        }
        val refreshDue = refTimer >= refreshIntervalCycles.U(24)

        // The read's data: the burst's slice of the window, its sixteen-bit groups turned around (the window reads
        // newest-first; the bus reads beat 0 first — the same relationship the calibration's check compares at, with
        // the offset the sweep latched).
        val alignSlice = muxTree(
            alignLocked,
            (0 until checkOffsets).map(k => captured.asBits.bits(16 * k + 127, 16 * k)) ++
                Seq.fill(64 - checkOffsets)(0.U(Ddr3BusShape.dataWidth).asBits)
        )
        val rdata: Node[UInt] =
            (Ddr3BusShape.dataWidth - 1 to 0 by -1)
                .map(i => alignSlice.bit(16 * (7 - i / 16) + i % 16).asBits)
                .reduce((high, low) => high ## low)
                .asUInt

        bus.req.ready := (state === sBusIdle.U(sBusWidth)) & calibrated & !refreshDue & !respValid
        // The accept itself: a request is *taken* when it is both offered and answered (a ready with nothing on the port
        // is not an access), and that is the one event the latch below and the state's own transition turn on.
        val busAccepted = bus.req.valid & bus.req.ready
        when(busAccepted) {
            busWdata := bus.req.bits.wdata
            busWstrb := bus.req.bits.wstrb
            busWen := bus.req.bits.wen
            busUser := bus.req.bits.user
            busId := bus.req.bits.id
            // The address decodes to the row, bank and column the commands carry: the row is re-sent as it is (its
            // fifteen bits are the pins), the bank is three, and the column keeps the burst's own low three bits at
            // zero (A2..A0) with its A10 (auto-precharge) zero as well ([[Ddr3BusShape]]).
            rowSel := bus.req.bits.addr.asBits.bits(28, 14).asUInt
            baSel := bus.req.bits.addr.asBits.bits(13, 11).asUInt
            colSel := (bus.req.bits.addr.asBits.bits(10, 4) ## 0.U(3).asBits).asUInt
        }

        // The accept, as the state machine's own condition: the ready registered once. The ready's own cone holds the
        // refresh timer's compare (`refreshDue`) and the calibration's verdict, and the state's transition *is* the
        // accept — a request is run exactly where it is answered. Read registered, a cycle after the ready: the latch
        // of the request itself is the ready's (`busWdata` and its neighbours, above), so the transition follows the
        // handshake by a cycle and the state decode's clock enable grows by one register instead of a 24-bit compare
        // (measured: the unregistered form was this design's worst setup path at −0.057 ns, on the state flops' own
        // clock enable).
        val busTaken = RegInit(false.B)
        busTaken := busAccepted

        when(state === sBusIdle.U(sBusWidth)) {
            when(calibrated & refreshDue) {
                refTimer := 0.U(24)
                timer := 0.U(24)
                state := sBusRefPre.U(sBusWidth)
            }.otherwise {
                when(busTaken) {
                    accesses := constAdder(accesses, 1, 32)
                    timer := 0.U(24)
                    state := sBusPre.U(sBusWidth)
                }
            }
        }

        when(state === sBusPre.U(sBusWidth)) {
            timer := 0.U(24)
            state := sBusPreWait.U(sBusWidth)
        }

        when(state === sBusPreWait.U(sBusWidth)) {
            when(timer === (trpCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sBusAct.U(sBusWidth)
            }
        }

        when(state === sBusAct.U(sBusWidth)) {
            timer := 0.U(24)
            state := sBusActWait.U(sBusWidth)
        }

        when(state === sBusActWait.U(sBusWidth)) {
            when(timer === (trcdCycles - 1).U(24)) {
                timer := 0.U(24)
                state := busWen ? (sBusWr.U(sBusWidth), sBusRd.U(sBusWidth))
            }
        }

        // The bus's write is the round's own staging, word for word (it is the same burst): three words of data with
        // their mask, the strobe's frames beside them, and the recovery.
        when(state === sBusWr.U(sBusWidth)) {
            when(timer === 1.U(24)) {
                dqOutReg := wordNode(Seq(-1, -1, 0, 1))
                dmOutReg := dmWord(Seq(-1, -1, 0, 1))
                dqsOutReg := stagedDqsWord(0)
            }
            when(timer === 2.U(24)) {
                dqOutReg := wordNode(Seq(2, 3, 4, 5))
                dmOutReg := dmWord(Seq(2, 3, 4, 5))
                dqsOutReg := stagedDqsWord(1)
            }
            when(timer === 3.U(24)) {
                dqOutReg := wordNode(Seq(6, 7, -1, -1))
                dmOutReg := dmWord(Seq(6, 7, -1, -1))
                dqsOutReg := stagedDqsWord(2)
            }
            when(timer === 4.U(24)) {
                dqOutReg := 0.U(Ddr3PadNames.dqWordWidth)
                dmOutReg := 0.U(4 * Ddr3PadNames.dmWidth)
                dqsOutReg := stagedDqsWord(3)
            }
            when(timer === 6.U(24)) {
                timer := 0.U(24)
                state := sBusWrTail.U(sBusWidth)
            }
        }

        when(state === sBusWrTail.U(sBusWidth)) {
            when(timer === (wrTailCycles - 1).U(24)) {
                dqsOutReg := dqsIdle.U(Ddr3PadNames.dqsWordWidth)
                respValid := true.B
                respData := 0.U(Ddr3BusShape.dataWidth)
                respUser := busUser
                respId := busId
                timer := 0.U(24)
                state := sBusIdle.U(sBusWidth)
            }
        }

        // The bus's read: the same capture window as the round's, and the response is the slice the sweep latched.
        when(state === sBusRd.U(sBusWidth)) {
            when(timer === (captureLast + 2).U(24)) {
                respValid := true.B
                respData := rdata
                respUser := busUser
                respId := busId
                timer := 0.U(24)
                state := sBusIdle.U(sBusWidth)
            }
        }

        when(respValid & bus.resp.ready) {
            respValid := false.B
        }
        bus.resp.valid := respValid
        bus.resp.bits.data := respData
        bus.resp.bits.user := respUser
        bus.resp.bits.id := respId

        // The refresh: all banks closed, one REF, then tRFC before the next activate.
        when(state === sBusRefPre.U(sBusWidth)) {
            timer := 0.U(24)
            state := sBusRefPreWait.U(sBusWidth)
        }
        when(state === sBusRefPreWait.U(sBusWidth)) {
            when(timer === (trpCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sBusRef.U(sBusWidth)
            }
        }
        when(state === sBusRef.U(sBusWidth)) {
            timer := 0.U(24)
            state := sBusRefWait.U(sBusWidth)
        }
        when(state === sBusRefWait.U(sBusWidth)) {
            when(timer === (trfcCycles - 1).U(24)) {
                timer := 0.U(24)
                state := sBusIdle.U(sBusWidth)
            }
        }

        // Marked where the signals live, in one step (`Probes.mark` = declare + bind): this module is instantiated once,
        // so one name is one net. A shared body would declare and be bound where the instances are named instead.
        Probes.mark("state")(state)
        Probes.mark("initDone")(initDone)
        Probes.mark("alignedAt")(alignedAt)
        // Where the last check found a four-beat half (63 = nowhere): the sweep's window reading, and what a board
        // capture is read from when no whole burst was seen.
        Probes.mark("halfAt")(halfAt)
        // The post-lock round, as a state and as a verdict: it is what opens the bus ([[calibrated]]), so a board
        // whose bus stays shut says through these two whether the round ran at all.
        Probes.mark("tap")(tap)
        // Which of the high byte lane's tap hypotheses this round weighed: 0 is both lanes at the same tap, 1 the high
        // lane eight steps on, 2 eight steps back. Read beside `failTaps`, it is which hypothesis a capture is of.
        Probes.mark("calibPlane")(calibPlane)
        // The calibration's own conclusion, held after it: whether the sweep is still running, which plane it chose,
        // the tap it locked the low lane at (the high lane's follows from the plane), and the alignment it found —
        // with `alignGot` saying whether a whole burst was ever seen at all, which is what the bus's readiness means.
        Probes.mark("calibrating")(calibrating)
        Probes.mark("calibrated")(calibrated)
        Probes.mark("finalRound")(finalRound)
        Probes.mark("finalPass")(finalPass)
        Probes.mark("alignGot")(alignGot)
        Probes.mark("alignLocked")(alignLocked)
        Probes.mark("lockedPlane")(lockedPlane)
        Probes.mark("lockedTap")(lockedTap)
        Probes.mark("writeShift")(writeShift)
        Probes.mark("recent")(recent)
        // The window a word at a time, not as one net: a probe this wide does not survive the netlist's debug insertion
        // (measured on the board: the single 832-bit probe reads as zeros while the design's own compares prove the
        // register non-zero at the check), and the word is the unit both the check and a reading of the stream work in
        // — word `k` is the sample the window took `k + 1` shifts ago, newest first. `checked` is not watched beside it:
        // nothing shifts the window between a check and the next round's read, so the check's own instant is one of
        // these words' samples, and thirteen words of load are what the timer's carry chain can still afford.
        for k <- 0 until captureSamples / 4 do
            Probes.mark(f"captured$k%02d")(captured.asBits.bits(64 * k + 63, 64 * k).asUInt)
        Probes.mark("failTaps")(failTaps)
        Probes.mark("roundIn")(roundIn)
        // The bus's own account: how many accesses it has answered, whether one is in flight, what the last response
        // carried, and the mask the write staged (which is what says the strobe path is doing something).
        Probes.mark("accesses")(accesses)
        Probes.mark("respValid")(respValid)
        Probes.mark("respData")(respData)
        Probes.mark("dmOut")(dmOutReg)
        // Measured in the bring-up, not for the access's judgement: the state machine's own cycle counter beside the
        // capture's input word, so an ILA capture can say *which* cycle the read burst reached the fabric in — the
        // board's capture chain delivers it a few cycles later than the vendor's model does.
        Probes.mark("timer")(timer)
        Probes.mark("dqIn")(dqIn)
