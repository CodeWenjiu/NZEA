package nzea_fpga.logic

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_device.devices.{UartRx, UartRxParameter}
import nzea_fpga.device.ddr3.Ddr3Side
import nzea_fpga.device.uart.UartXdc
import nzea_fpga.ip.ClockOut
import nzea_fpga.{BoardLogic, Clocks, LogicNeeds, LogicReset, Logics, Pin}
import nzea_rtl.bus.LiteBusRW
import nzea_rtl.probe.Probes
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** What the loader needs to be told: the rate the line runs at, and the shape of the port it writes through.
  *
  * The line rate is the host's own ([[probe]]'s `--baud`, 115200 by default) — the board's bridge is transparent to it
  * — so it cannot be derived from anything here. The port's shape is the fabric's, which the level above knows
  * ([[nzea_fpga.Top]] builds it from the tile's own map).
  */
case class BootDdrParameter(
    lineHz: Int,
    busAddrWidth: Int,
    busDataWidth: Int,
    busUserWidth: Int,
    busIdWidth: Int
) extends Parameter:
    require(lineHz > 0, s"lineHz must be positive, got $lineHz")

given upickle.default.ReadWriter[BootDdrParameter] = upickle.default.macroRW

class BootDdrLayers(parameter: BootDdrParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The loader's ports: the line, the grids and the hold it states ([[nzea_fpga.Logics]]), plus the bus it writes the
  * program through — the level above connects that one to the memory it owns.
  */
class BootDdrIO(parameter: BootDdrParameter) extends HWRecord(parameter):
    Logics.declare(this, BootDdr.needs)

    Aligned(
        "load",
        new LiteBusRW(parameter.busAddrWidth, parameter.busDataWidth, parameter.busUserWidth, parameter.busIdWidth)
    )

class BootDdrProbe(parameter: BootDdrParameter) extends DVRecord[BootDdrParameter, BootDdrLayers](parameter)

/** The board's program loader: a serial line's bytes, a magic number, and a program written into the board's DDR3.
  *
  * The protocol is the host tool's ([[probe]]'s `protocol.rs`): `magic u32 (0xB007B007) + addr u32 + size u32 + size *
  * u32`, every word little-endian, and `size = 0` is the reset-only command — the header is still sent and the core
  * still restarts from the reset vector, but no word is written.
  *
  * The core is held from the moment the magic is recognized until the frame's last word has been *acknowledged* on the
  * bus: a load must not race a fetch, and every write must have landed before the core's first instruction. The hold
  * leaves this module as a reason of the level's reset network ([[nzea_fpga.logic.BootDdr.HoldNReason]]), so what the
  * core actually sees is that network's own synchronizer releasing it. From power-up the core is held until the first
  * frame completes — there is no program in the memory before that, and running one would run zeros.
  *
  * The hold covers the core alone: this module, the receiver and the memory's own port run on the boot domain, which
  * does not wait for it — a domain that held the loader would never load anything.
  */
@generator
object BootDdr extends Generator[BootDdrParameter, BootDdrLayers, BootDdrIO, BootDdrProbe], BoardLogic:
    override def moduleName(parameter: BootDdrParameter): String = "BootDdr"

    /** The grid the core, the loader and the memory share: a same-domain sequence, so a load's writes and the core's
      * first fetch are ordered by the one clock, and the hold's release is what the core's own synchronizer cleans.
      *
      * 25 MHz is the first build's margin: the tile has never run on this board, so the first thing to be sure of is
      * that it runs at all. The line's divider follows this grid ([[nzea_device.devices.UartRxParameter]]).
      */
    val ClockGrid = ClockOut(25.0, 0.0)

    /** The reason this logic produces: the core may run — the loader is idle and a program has been loaded. It is a
      * reason of the level's network, so it is the level's own polarity (`1` means *not holding*): the hold is this
      * level read the other way, and the network's `rawN` spells it `... & bootHoldN`.
      */
    val HoldNReason = "bootHoldN"

    /** What the level above declares and wires: the line's pin, the shared grid, the hold, and the boot domain — the
      * board's own reasons plus the generator's lock, the same pair every generated domain waits for.
      */
    override def needs: LogicNeeds = LogicNeeds(
        pins = UartXdc.pins,
        grids = Seq(ClockGrid),
        produces = Seq(HoldNReason),
        reset = Some(LogicReset("boot", ClockGrid, Seq("buttonPin", "pllLocked", Ddr3Side.ReadyReason)))
    )

    private val sIdle = 0
    private val sAddr = 1
    private val sSize = 2
    private val sData = 3
    private val sWrReq = 4
    private val sWrResp = 5
    private val sDone = 6
    private val sBits = 3

    private val magic = BigInt("b007b007", 16)

    def architecture(parameter: BootDdrParameter) =
        val io = summon[Interface[BootDdrIO]]
        val clock = io.field[Clock](Clocks.portName(ClockGrid))
        val resetN = io.field[Reset](Logics.ResetPort)
        given ClockScope = ClockScope.posedge(clock)
        given ResetScope = ResetScope.asyncActiveLow(resetN)

        val bus = io.field[LiteBusRW]("load")
        val wordBytes = parameter.busDataWidth / 8

        val rx = UartRx.instantiate(UartRxParameter((ClockGrid.mhz * 1000000).toInt, parameter.lineHz))
        rx.io.clock := clock
        rx.io.resetN := resetN
        rx.io.rxd := io.field[Bool](UartXdc.RxPin)

        val state = RegInit(sIdle.U(sBits))
        val shift = RegInit(0.U(32))
        val byte = RegInit(0.U(3))
        val magicLow = RegInit(0.U(32))
        val addr = RegInit(0.U(parameter.busAddrWidth))
        val words = RegInit(0.U(32))
        val wordsLeft = RegInit(0.U(32))
        val written = RegInit(0.U(32))
        val loaded = RegInit(false.B)

        // The word the last four bytes spell, little-endian: the arriving byte is the word's highest part, the three
        // before it the rest (`probe`'s protocol).
        val word = (rx.io.data.asBits ## Node(shift.asBits).bits(31, 8)).asUInt
        val atWord = byte === 3.U(3)

        when(rx.io.valid) {
            shift := word
            byte := constAdder(byte, 1, 3)
            magicLow := (rx.io.data.asBits ## Node(magicLow.asBits).bits(31, 8)).asUInt
        }

        when(state === sIdle.U(sBits)) {
            // The scanner: nothing else looks at the stream, so a load can start at any byte boundary — the host's
            // frames are sent back to back and there is no idle time between them to key on.
            when(magicLow === magic.U(32)) {
                state := sAddr.U(sBits)
                byte := 0.U(3)
            }
        }

        when(state === sAddr.U(sBits)) {
            when(rx.io.valid & atWord) {
                addr := word
                byte := 0.U(3)
                state := sSize.U(sBits)
            }
        }

        when(state === sSize.U(sBits)) {
            when(rx.io.valid & atWord) {
                words := word
                wordsLeft := word
                byte := 0.U(3)
                state := (word === 0.U(32)) ? (sDone.U(sBits), sData.U(sBits))
            }
        }

        when(state === sData.U(sBits)) {
            when(rx.io.valid & atWord) {
                byte := 0.U(3)
                state := sWrReq.U(sBits)
            }
        }

        // One request outstanding at a time, and the answer is what makes a written word land: the next word's bytes are
        // accepted only after the memory has acknowledged the one before it. The write therefore has to finish inside
        // one byte's time on the line (86 us at 115200, against a handful of memory clocks) — the design's margin is
        // what keeps the next word's bytes from shifting in mid-write, since the receiver keeps delivering them.
        bus.req.valid := state === sWrReq.U(sBits)
        bus.req.bits.addr := addr
        bus.req.bits.wdata := shift
        bus.req.bits.wstrb := ((1 << wordBytes) - 1).U(wordBytes)
        bus.req.bits.wen := true.B
        bus.req.bits.user := 0.U(parameter.busUserWidth)
        bus.req.bits.id := 0.U(parameter.busIdWidth)
        bus.resp.ready := true.B

        when(state === sWrReq.U(sBits)) {
            when(bus.req.ready) {
                state := sWrResp.U(sBits)
            }
        }

        when(state === sWrResp.U(sBits)) {
            when(bus.resp.valid) {
                written := constAdder(written, 1, 32)
                addr := constAdder(addr, wordBytes, parameter.busAddrWidth)
                wordsLeft := prefixSub(wordsLeft, 1.U(32), 32)._1
                state := (wordsLeft === 1.U(32)) ? (sDone.U(sBits), sData.U(sBits))
            }
        }

        when(state === sDone.U(sBits)) {
            // Cleared here so a program whose own last four bytes spell the magic does not start a second load of
            // itself the moment it runs. A reset-only command before any load leaves the core held: there is no
            // program in the memory yet, and releasing it would run zeros.
            magicLow := 0.U(32)
            loaded := loaded | (words =/= 0.U(32))
            state := sIdle.U(sBits)
        }

        // The hold, as the network's own polarity (`1` = not holding): the core may run only when a program has been
        // loaded *and* no frame is in flight — from power-up the first of the two is what keeps it held. `sDone` is
        // the release: the frame's last word has been acknowledged by then.
        io.field[Bool](HoldNReason) := loaded & ((state === sIdle.U(sBits)) | (state === sDone.U(sBits)))

        Probes.mark("bootState")(state)
        Probes.mark("bootMagic")(magicLow)
        Probes.mark("bootAddr")(addr)
        Probes.mark("bootWords")(words)
        Probes.mark("bootWritten")(written)
        Probes.watch(rx, "data")
        Probes.watch(rx, "valid")
