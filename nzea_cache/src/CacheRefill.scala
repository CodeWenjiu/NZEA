package nzea_cache

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.bus.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** What the refill engine needs to know: the bus's widths, the line's shape on it, and how many beats may be in flight.
  * `outstanding` is the fabric's per-slave depth — the engine never puts more on the bus than the fabric can hold — and
  * the beats of one line are issued back to back up to it, so a refill costs the latency plus the beats, not the
  * latency per beat.
  */
case class CacheRefillParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    lineBeats: Int,
    wordOffBits: Int,
    outstanding: Int
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 8 && dataWidth % 8 == 0, s"dataWidth must be a whole number of bytes, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(lineBeats >= 1, s"lineBeats must be >= 1, got $lineBeats")
    require(outstanding >= 1, s"outstanding must be >= 1, got $outstanding")

given upickle.default.ReadWriter[CacheRefillParameter] = upickle.default.macroRW

class CacheRefillLayers(parameter: CacheRefillParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class CacheRefillIO(parameter: CacheRefillParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The miss that starts a refill: the line's base address (the word's own, when uncached), which word to answer
      * with, and whether the line is to be installed at all.
      */
    val start = Flipped(Bool())
    val startAddr = Flipped(UInt(parameter.addrWidth))
    val startWordOff = Flipped(UInt(parameter.wordOffBits))
    val startUncached = Flipped(Bool())

    /** The memory side. */
    val bottom =
        Aligned(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

    /** The engine is running: the cache must not accept while it is. */
    val busy = Aligned(Bool())

    /** The assembled line, one cycle before the answer: the cache installs it and touches its replacement state. */
    val install = Aligned(Bool())
    val line = Aligned(UInt(parameter.lineBeats * parameter.dataWidth))

    /** The answer is ready, held until the cache takes it. */
    val done = Aligned(Bool())
    val ack = Flipped(Bool())
    val bypassData = Aligned(UInt(parameter.dataWidth))

class CacheRefillProbe(parameter: CacheRefillParameter)
    extends DVBundle[CacheRefillParameter, CacheRefillLayers](parameter)

/** The line a miss needs, off the memory bus: beats issued back to back and collected in order, assembled into one
  * line, with the word the miss asked for picked back out for its answer.
  *
  * The engine knows nothing of tags, sets or ways — the cache that started it installs the line and answers from
  * `bypassData` — so the I- and D-caches share it. An uncached miss is the same stream with one beat and no install.
  */
@generator
object CacheRefill extends Generator[CacheRefillParameter, CacheRefillLayers, CacheRefillIO, CacheRefillProbe]:

    /** The module is named by its bus and line shape — the engine is the body — so two caches do not share one. */
    override def moduleName(parameter: CacheRefillParameter): String =
        s"CacheRefill_dataWidth${parameter.dataWidth}_lineBeats${parameter.lineBeats}" +
            s"_outstanding${parameter.outstanding}"

    def architecture(parameter: CacheRefillParameter) =
        val io = summon[Interface[CacheRefillIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val cntW = log2Ceil(parameter.lineBeats + 1)
        val wordBytes = parameter.dataWidth / 8

        // The assembled line: one register per beat, filled by the responses as they come back in order.
        val beats = Reg(Vec(parameter.lineBeats, UInt(parameter.dataWidth)))

        val idle = 0.U(3)
        val run = 1.U(3)
        val finish = 2.U(3)
        // One cycle between the install and the answer: the storage's read port needs it to show the installed row's
        // set again (a single-port macro reads what it just wrote), and the cache's held head reads through that port.
        val settle = 3.U(3)
        val held = 4.U(3)
        val state = RegInit(idle)
        val addr = RegInit(0.U(parameter.addrWidth))
        val wordOff = RegInit(0.U(parameter.wordOffBits))
        val uncached = RegInit(false.B)
        val sent = RegInit(0.U(cntW))
        val recv = RegInit(0.U(cntW))

        val total = uncached ? (1.U(cntW), parameter.lineBeats.U(cntW))
        val room = subWrap(sent, recv, cntW) < parameter.outstanding.U(cntW)
        val issue = (state === run) & (sent < total) & room
        val beatOffset =
            muxTree(sent, (0 until parameter.lineBeats).map(i => (i * wordBytes).U(parameter.addrWidth)))

        io.bottom.req.valid := issue
        io.bottom.req.bits.addr := addWrap(addr, beatOffset, parameter.addrWidth)
        io.bottom.req.bits.wdata := 0.U(parameter.dataWidth)
        io.bottom.req.bits.wen := false.B
        io.bottom.req.bits.wstrb := 0.U(parameter.dataWidth / 8)
        io.bottom.req.bits.user := 0.U(parameter.userWidth)
        io.bottom.req.bits.id := 0.U(parameter.idWidth)
        io.bottom.resp.ready := state === run

        io.busy := state =/= idle
        io.install := state === finish
        // The line, low beat first: beat 0 is the line's own base, so it sits at the bottom of the word.
        io.line := (parameter.lineBeats - 1 to 0 by -1)
            .map(i => Node(beats.ref(i).asBits))
            .reduce(_ ## _)
            .asUInt
        io.done := state === held
        io.bypassData := beats.ref(wordOff)

        when(state === idle) {
            when(io.start) {
                addr := io.startAddr
                wordOff := io.startWordOff
                uncached := io.startUncached
                sent := 0.U(cntW)
                recv := 0.U(cntW)
                state := run
            }
        }
        when(state === run) {
            when(io.bottom.req.valid & io.bottom.req.ready) { sent := addWrap(sent, 1.U(cntW), cntW) }
            when(io.bottom.resp.valid) {
                for i <- 0 until parameter.lineBeats do
                    when(recv === i.U(cntW)) { beats(i) := io.bottom.resp.bits.data }
                recv := addWrap(recv, 1.U(cntW), cntW)
            }
            when((sent === total) & (recv === total)) { state := finish }
        }
        when(state === finish) { state := settle }
        when(state === settle) { state := held }
        when((state === held) & io.ack) { state := idle }
