package nzea_fpga.logic

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_fpga.device.ddr3.Ddr3PadNames
import nzea_rtl.probe.Probes
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class Ddr3SoakParameter() extends Parameter

given upickle.default.ReadWriter[Ddr3SoakParameter] = upickle.default.macroRW

class Ddr3SoakLayers(parameter: Ddr3SoakParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The soak's ports: the domain it runs in — the bus's own, clock and reset handed over by
  * [[nzea_fpga.device.ddr3.Ddr3Side]] — and the bus's campaign side, named as the bus names it
  * ([[nzea_fpga.device.ddr3.Ddr3]]).
  */
class Ddr3SoakIO(parameter: Ddr3SoakParameter) extends HWRecord(parameter):
    Flipped("clock", Clock())
    Flipped("resetN", Reset())

    // What a round carries: this module's registers, held stable for the whole round (the device stages them long after
    // the round's own edge stepped them, so nothing here has to be latched for it).
    Aligned("campBeats", UInt(8 * 16))
    Aligned("campBa", UInt(Ddr3PadNames.baWidth))
    Aligned("campRow", UInt(Ddr3PadNames.addrWidth))
    Aligned("campCol", UInt(Ddr3PadNames.addrWidth))

    // What a round answers, on the edge the device says the answer is valid.
    Flipped("campPass", Bool())
    Flipped("campRoundEdge", Bool())
    Flipped("campRoundDone", Bool())
    Flipped("campTap", UInt(Ddr3PadNames.tapWidth))
    Flipped("campCalibrating", Bool())
    Flipped("campLockedTap", UInt(Ddr3PadNames.tapWidth))

class Ddr3SoakProbe(parameter: Ddr3SoakParameter) extends DVRecord[Ddr3SoakParameter, Ddr3SoakLayers](parameter)

/** The board's DDR3 soak: the campaign the device is put through, round after round — the *test*, where
  * [[nzea_fpga.device.ddr3.Ddr3]] is the transport.
  *
  * A round is the device's (activate, write, precharge, activate, read, judge, ~230 ns). What this module owns is what
  * a round *carries* and what the rounds *account to*:
  *
  *   - the two things a round's burst says, stepped together as a *pair*: one of eight patterns ([[patternRows]], the
  *     eight-beat rows one round writes) and one of eight addresses ([[comboRows]] and its bank and column) — 64 pairs,
  *     each run once before the next block;
  *   - a *block*: one whole turn of the device's tap sweep ([[nzea_fpga.device.ddr3.Ddr3]] states the sweep, and
  *     `campRoundDone` is the round that closes a turn). The pairs step at a block's edge, so every pair is weighed
  *     against every tap the board may need before the campaign moves on — a pair that only ever ran at one tap could
  *     fail for the board's timing and pass for the pattern, or the other way round;
  *   - the account a board reading is made of: `rounds` and `misses` (the rate), and the two 64-bit masks — per pair,
  *     whether its block ever missed and whether its last block came through clean — which is what says *which* pairs
  *     fail rather than only how often (the reading guide is `nzea_fpga/src/device/ddr3/README_zh.md`, §6).
  *
  * The one bit a round reports arrives on `campPass`, valid on `campRoundEdge` — the same cycle the device's state
  * machine leaves the round — and everything here is a register updated on that edge. The campaign and the device see
  * one timeline because the bus's domain is handed over with the campaign's side ([[nzea_fpga.device.ddr3.Ddr3Side]]
  * wires the clock and the reset), so this module runs on the controller's own grid and leaves reset with it.
  *
  * The beats leave one cycle behind the row's own registers ([[beatRegs]]): the words the serializers take are a
  * register's output, and the worst cone of the table — the streaming row's eight steps of feedback — is spent inside
  * the cycle rather than in front of the pads.
  */
@generator
object Ddr3Soak extends Generator[Ddr3SoakParameter, Ddr3SoakLayers, Ddr3SoakIO, Ddr3SoakProbe]:
    override def moduleName(parameter: Ddr3SoakParameter): String = "Ddr3Soak"

    /** The rows a round's pattern may name, eight 16-bit beats each; the last row is the LFSR's ([[lfsrRow]]), whose
      * beats are computed rather than tabulated, so only the seven constant rows are here.
      *
      * What the rows have to do: every row moves through all sixteen lanes with transitions inside every beat — the
      * walking rows move one wire at a time, the two mask rows toggle every wire on every beat, the register's row is a
      * run of pseudorandom words — so a lane that is stuck, crossed with its neighbour or slow to change shows up as
      * itself instead of being hidden by its neighbours. The rows differ from each other in *which* walk they run: that
      * is what makes a failing pair's pattern worth reading.
      */
    private val patternRows: Seq[Seq[BigInt]] =
        val rows = Seq(
            (0 until 8).map(k => BigInt(0xa000 + k)), // a nibble's walk in the top byte, the rest of the word standing
            (0 until 8).map(k => BigInt(1) << k), // one bit walking the low byte
            (0 until 8).map(k => BigInt(1) << (k + 8)), // the same walk in the high byte
            (0 until 8).map(k => ~(BigInt(1) << k) & 0xffff), // one bit low, the rest high — the inverse walk
            (0 until 8).map(k => ~(BigInt(1) << (k + 8)) & 0xffff),
            Seq.fill(8)(BigInt(0xaaaa)), // every lane every beat: the densest edges a burst can ask the bus for
            Seq.fill(8)(BigInt(0x5555))
        )
        require(
            rows.forall(row => row.forall(word => word != 0 && word != 0xffff)),
            "a pattern row with a 0x0000 or 0xFFFF beat: a burst whose word never changes does not test a wire"
        )
        require(
            rows.distinct.sizeIs == rows.size,
            "two pattern rows are the same walk — the pairs would spend a block on each of them for one verdict"
        )
        rows

    /** The row whose beats are the LFSR's ([[lfsr]]): the one row that keeps moving between rounds. */
    private val lfsrRow = 7

    /** The eight addresses the pairs run at, as the row, bank and column each names — a walk that reaches every field
      * the device decodes: the row's low bit (0x0001), its A10 (0x0400), all ones (0x7FFF) and an alternating one
      * (0x2AAA); the banks 1, 7 and 4 beside 0; and the columns 0x8 (A3) and 0x3F8 (A3..A9) beside 0.
      *
      * The three are one table because a pair *is* one address: they are selected by the pair index's high bits
      * (`combo[5:3]`), so an entry here is a whole (row, bank, column), never one field of one.
      */
    private val comboRows = Seq(0x0000, 0x0000, 0x0000, 0x0001, 0x0400, 0x7fff, 0x0000, 0x2aaa)
    private val comboBas = Seq(0, 1, 7, 0, 0, 0, 0, 4)
    private val comboCols = Seq(0, 0, 0, 0, 0, 0, 8, 0x3f8)

    def architecture(parameter: Ddr3SoakParameter) =
        val io = summon[Interface[Ddr3SoakIO]]
        given ClockScope = ClockScope.posedge(io.field[Clock]("clock"))
        given ResetScope = ResetScope.asyncActiveLow(io.field[Reset]("resetN"))

        val campBeats = io.field[UInt]("campBeats")
        val campBa = io.field[UInt]("campBa")
        val campRow = io.field[UInt]("campRow")
        val campCol = io.field[UInt]("campCol")
        val campPass = io.field[Bool]("campPass")
        val campRoundEdge = io.field[Bool]("campRoundEdge")
        val campRoundDone = io.field[Bool]("campRoundDone")
        val campTap = io.field[UInt]("campTap")
        val campCalibrating = io.field[Bool]("campCalibrating")
        val campLockedTap = io.field[UInt]("campLockedTap")

        // ---- the state ------------------------------------------------------------------------------------------

        /** The pair index: the low three bits are the pattern ([[patternRows]]), the next three the address
          * ([[comboRows]], [[comboBas]], [[comboCols]]) — so the 64 pairs are one 6-bit walk, and a block's step is one
          * increment.
          */
        val combo = RegInit(0.U(6))
        val patIdx = RegInit(0.U(3))
        val rowReg = RegInit(0.U(Ddr3PadNames.addrWidth))
        val colReg = RegInit(0.U(Ddr3PadNames.addrWidth))
        val baReg = RegInit(0.U(Ddr3PadNames.baWidth))

        /** The streaming row's state: a 16-bit LFSR whose feedback makes a maximal-length sequence — every word of it
          * but zero appears once before it repeats, so a burst sampled twice never sees the same run by accident.
          * Seeded to something with both levels present and a mix of runs, and stepped eight times per round (a burst's
          * own width), so consecutive rounds of the row are consecutive windows of the sequence.
          */
        val lfsr = RegInit(BigInt(0xace1).U(16))

        val rounds = RegInit(0.U(32))
        val misses = RegInit(0.U(32))
        val blocks = RegInit(0.U(32))

        /** Where the last miss stood: its pair ([[patIdx]] and the pair index's address bits) and the tap the round ran
          * at — the sweep's own step while it runs, the locked tap once the device stops it. Kept for the capture's
          * sake: it is what turns "a round missed" into a lead.
          */
        val missPat = RegInit(0.U(3))
        val missAddr = RegInit(0.U(3))
        val missTap = RegInit(0.U(Ddr3PadNames.tapWidth))

        /** Whether the block being run has missed, and the two masks it writes when it ends: `passMask` marks the pairs
          * whose block came through clean, `missMask` the pairs a miss has ever stood at. A pair's own bit is set at
          * its block's edge (`combo` is still the block's pair there), so the masks read as "which of the 64 pairs this
          * campaign has cleared, and which it has seen fail" — a miss in any of a block's rounds marks the pair, which
          * is what a board reading wants: one bad tap fails the pair for the whole board, not for the pattern.
          */
        val blockMiss = RegInit(false.B)
        val passMask = RegInit(0.U(64))
        val missMask = RegInit(0.U(64))

        // ---- what a round carries -------------------------------------------------------------------------------

        /** The LFSR's feedback: the register's own bits 15, 13, 12 and 10 xored — the tap set of a maximal-length
          * 16-bit sequence (`XAPP052`'s 16-bit entry: taps 16/14/13/11, which for a register that shifts *up* and takes
          * its feedback at bit 0 are the bits one below each tap).
          *
          * A helper that creates ops takes the enclosing context (`Arena`, `Context`, `Block`) as a `using` clause: the
          * block is what says *where* an op is appended, and a helper without it would append to the architecture's own
          * block even when it is called inside a `when` — the value would then be defined after the region that uses it
          * (measured: `firld` refuses the module with `operand #1 does not dominate this use`).
          */
        def lfsrFeedback(value: Referable[UInt])(using Arena, Context, Block): Node[Bool] =
            value.asBits.bit(15) ^ value.asBits.bit(13) ^ value.asBits.bit(12) ^ value.asBits.bit(10)

        /** One step of the register: the word shifted one bit up, the feedback entering at the bottom. */
        def lfsrStep(value: Referable[UInt])(using Arena, Context, Block): Node[UInt] =
            val shifted = (value.asBits << 1).bits(15, 0)
            (shifted | (0.U(15).asBits ## lfsrFeedback(value).asBits)).asUInt

        // The beats the round writes: one 16-bit word per beat, the pattern row's own walk.
        //
        // They are latched, not wired: the row's beats are a table lookup for seven of the eight rows and eight steps of
        // feedback for the streaming one, so the registered words keep that cone inside the campaign's own clock and
        // give the pads a register to take — the campaign is not the last stage before a serializer. The latch is
        // unconditional, so a word's value is "the row `patIdx` names, one cycle late": the round's edge steps the row
        // and the words follow, cycles before the round's write stages them.
        val beatRegs = (0 until 8).map(_ => Reg(UInt(16)))

        /** Beat `k` of the row `patIdx` names: a constant of the table, or — the streaming row — the LFSR's own state
          * after `k` steps, which is what makes that row's eight beats eight successive states of it.
          */
        def tableBeat(k: Int)(using Arena, Context, Block): Node[UInt] =
            val lfsrBeat = (0 until k).foldLeft(lfsr: Referable[UInt])((value, _) => lfsrStep(value))
            muxTree(patIdx, patternRows.map(row => row(k).U(16)) :+ lfsrBeat)

        for k <- 0 until 8 do beatRegs(k) := tableBeat(k)

        // The bus takes the round's beats off this port as one flat word — beat `k` at bits `16k + 15:16k`, the device's
        // own reading of it ([[nzea_fpga.device.ddr3.Ddr3]]).
        campBeats := (7 to 0 by -1).map(k => beatRegs(k).asBits).reduce((high, low) => high ## low).asUInt

        // The round's address: the pair's own row, bank and column, held for the whole round.
        campBa := baReg
        campRow := rowReg
        campCol := colReg

        // ---- the round's edge: what the round was, and what the next one carries ---------------------------------

        // One block is one whole turn of the sweep, and the pair steps there: everything the campaign records about a
        // pair — its mask bits — belongs to the block that just ended, while the pair index still names it.
        val blockDone = campRoundEdge & campRoundDone

        when(campRoundEdge) {
            rounds := constAdder(rounds, 1, 32)
            when(!campPass) {
                misses := constAdder(misses, 1, 32)
                missPat := patIdx
                missAddr := combo.asBits.bits(5, 3).asUInt
                // The tap the round ran at: while the sweep is running that is the step it stands on, and after it the
                // one the device locked — which is what a capture reads the miss against.
                missTap := campCalibrating ? (campTap, campLockedTap)
            }
            // The block's own verdict, cleared where it ends: the round that closes a block cannot mark it (its miss
            // belongs to the pair that is stepping away, and the next block's own rounds will say what it deserves).
            blockMiss := (blockMiss | !campPass) & !blockDone
            // The streaming row moves every round — eight steps, one burst's width — and the block's end is not
            // special to it: consecutive rounds of the row are consecutive windows of the sequence, whatever the pair
            // index is doing at the time.
            lfsr := (0 until 8).foldLeft(lfsr: Referable[UInt])((value, _) => lfsrStep(value))
        }

        when(blockDone) {
            // A block's verdict, per pair: the pair's own bit of the masks, one-hot from the index that is still the
            // block's (`combo` steps below, after the masks have read it).
            val oneHot = (1.U(64).asBits << combo).bits(63, 0)
            when(!blockMiss) {
                passMask := (passMask.asBits | oneHot).asUInt
            }
            when(blockMiss) {
                missMask := (missMask.asBits | oneHot).asUInt
            }
            blocks := constAdder(blocks, 1, 32)
            // The next pair: one increment of the walk, and the fields it carries read out of the new index — the
            // pattern from its low bits, the address from its high ones.
            val comboNext = constAdder(combo, 1, 6)
            combo := comboNext
            patIdx := comboNext.asBits.bits(2, 0).asUInt
            val addrIdx = comboNext.asBits.bits(5, 3).asUInt
            rowReg := muxTree(addrIdx, comboRows.map(_.U(Ddr3PadNames.addrWidth)))
            baReg := muxTree(addrIdx, comboBas.map(_.U(Ddr3PadNames.baWidth)))
            colReg := muxTree(addrIdx, comboCols.map(_.U(Ddr3PadNames.addrWidth)))
        }

        // ---- what the board watches -----------------------------------------------------------------------------

        // The account a board reading is made of, marked here where the registers live: the device's own probes
        // ([[nzea_fpga.device.ddr3.Ddr3]]) say what the round was, these say what the campaign did with it. One step,
        // not two (`Probes.mark` = declare + bind): this module is instantiated once — with a second campaign the
        // names would have to grow a prefix, because a marked signal is found by its name through the hierarchy and
        // one name is one net.
        Probes.mark("rounds")(rounds)
        Probes.mark("misses")(misses)
        Probes.mark("blocks")(blocks)
        Probes.mark("combo")(combo)
        Probes.mark("missPat")(missPat)
        Probes.mark("missAddr")(missAddr)
        Probes.mark("missTap")(missTap)
        Probes.mark("passMask")(passMask)
        Probes.mark("missMask")(missMask)
