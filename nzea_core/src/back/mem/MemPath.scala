package nzea_core.back.mem

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.core.{CoreConfig, Isa}
import nzea_core.back.{IssueUop, PathResult}
import nzea_core.isa.CsrType
import nzea_rtl.LiteBusRW
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One `base:size` window, hex, of the platform's device map (e.g. `0x10000000:0x1000`): an address the platform
  * answers outside memory, which commit traces mark as MMIO.
  */
object MmioWindow:

  def parse(range: String): (BigInt, BigInt) =
    def hex(text: String): BigInt = BigInt(text.trim.stripPrefix("0x").replace("_", ""), 16)
    range.split(":") match
      case Array(base, size) => (hex(base), hex(size))
      case _                 => throw new IllegalArgumentException(s"expected a base:size window, got '$range'")

case class MemPathParameter(isa: Isa, defaultPc: Long, mmioRanges: Seq[String]) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)
  val windows: Seq[(BigInt, BigInt)] = mmioRanges.map(MmioWindow.parse)

given upickle.default.ReadWriter[MemPathParameter] = upickle.default.macroRW

class MemPathLayers(parameter: MemPathParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class MemPathIO(parameter: MemPathParameter) extends HWBundle(parameter):
  private val width = parameter.config.width
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val issue = Flipped(Valid(new IssueUop(parameter.config)))
  val done = Aligned(Bool())
  val result = Aligned(new PathResult(width))
  val dbus = Aligned(MemPath.dbusType(parameter.config))

class MemPathProbe(parameter: MemPathParameter) extends DVBundle[MemPathParameter, MemPathLayers](parameter)

/** Memory execution path: one bus request per uop, its response matched by the request tag.
  *
  * The path keeps no queue — the held uop is all the state it needs. The request is driven while the uop is there and
  * dropped when it goes away, and every response is consumed, with only a tag match turning one into a completion, so a
  * response for an abandoned request drains instead of completing the next uop. Byte or half accesses are aligned by
  * the bus itself: the request carries the word address, and the path selects inside the word with the offset it holds.
  */
@generator
object MemPath extends Generator[MemPathParameter, MemPathLayers, MemPathIO, MemPathProbe]:
  override def moduleName(parameter: MemPathParameter): String = "MemPath"

  val busIdWidth: Int = 1

  def dbusType(config: CoreConfig) =
    new LiteBusRW(config.width, config.width, DbusUser.userWidth, busIdWidth)

  def architecture(parameter: MemPathParameter) =
    val io = summon[Interface[MemPathIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    val uopValid = io.issue.valid
    val u = io.issue.bits

    val memOps = MemOp.set(parameter.config.isa)
    val lsuOps = LsuOp.set(parameter.config.isa)
    val op: Node[UInt] = memOps.payload(u.op)
    val isStore = LsuOp.isStore(op, lsuOps)

    val addr = prefixAdder(u.src_a, u.src_b, false.B, width)
    val addrBits: Node[Bits] = Node(addr.asBits)
    // One bus word is `lanes` bytes (four at xlen 32, eight at xlen 64), and the low address bits are the offset inside
    // it: the request carries the word address, the path selects inside the word with the offset it holds.
    val lanes = width / 8
    val offsetWidth = log2Ceil(lanes)
    val offset: Node[UInt] = addrBits.bits(offsetWidth - 1, 0).asUInt

    // Store data and strobe, both selected by the offset bits: a constant shift per data position, a strobe per access
    // size. A wider access than a lane is aligned by the ISA, so its strobe sits at the offset's own alignment.
    val byteStrb: Referable[UInt] = muxTree(offset, (0 until lanes).map(i => (BigInt(1) << i).U(lanes)))
    val halfStrb: Referable[UInt] = muxTree(offset, (0 until lanes).map(i => (BigInt(3) << (i & ~1)).U(lanes)))
    val wordStrb: Referable[UInt] = muxTree(offset, (0 until lanes).map(i => (BigInt(0xf) << (i & ~3)).U(lanes)))
    val doubleStrb: Referable[UInt] = ((BigInt(1) << lanes) - 1).U(lanes)
    val zeroStrb: Referable[UInt] = BigInt(0).U(lanes)

    /** One op's byte strobe: a store lights the lanes it writes, everything else reads. */
    def strobe(v: LsuOp): Referable[UInt] = v match
      case LsuOp.SB                                                                      => byteStrb
      case LsuOp.SH                                                                      => halfStrb
      case LsuOp.SW                                                                      => wordStrb
      case LsuOp.SD                                                                      => doubleStrb
      case LsuOp.LB | LsuOp.LH | LsuOp.LW | LsuOp.LBU | LsuOp.LHU | LsuOp.LWU | LsuOp.LD => zeroStrb

    val wstrb = mux1H(op, lsuOps.variants.map(strobe), lanes)
    val storeData: Node[Bits] = Node(u.src_c.asBits)
    val wdata = muxTree(offset, (0 until lanes).map(i => (storeData << (i * 8)).bits(width - 1, 0)))

    // One request per uop, held until the bus accepts it and dropped when the uop goes away.
    val uopEdge = uopValid & !regNext(uopValid, false.B)
    val reqValid = RegInit(false.B)
    when(uopEdge) { reqValid := true.B }
    when(!uopValid | (reqValid & io.dbus.req.ready)) { reqValid := false.B }

    val tag = RegInit(0.U(DbusUser.tagWidth))
    val curTag = RegInit(0.U(DbusUser.tagWidth))
    val tagNext = (tag.asBits.asUInt + 1.U(DbusUser.tagWidth)).asBits.bits(DbusUser.tagWidth - 1, 0).asUInt
    when(uopEdge) {
      curTag := tag
      tag := tagNext
    }

    io.dbus.req.valid := reqValid
    io.dbus.req.bits.addr := (addrBits.bits(width - 1, offsetWidth) ## 0.B(offsetWidth)).asUInt
    io.dbus.req.bits.wdata := wdata.asUInt
    io.dbus.req.bits.wen := isStore
    io.dbus.req.bits.wstrb := wstrb
    io.dbus.req.bits.user := DbusUser.pack(curTag)
    io.dbus.req.bits.id := 0.U(busIdWidth)

    io.dbus.resp.ready := true.B
    val hit = io.dbus.resp.valid & uopValid & (DbusUser.tagOf(io.dbus.resp.bits.user) === curTag)

    // Load result: sign- or zero-extended from the addressed byte, half, word or the whole bus word.
    val rdata: Node[Bits] = Node(io.dbus.resp.bits.data.asBits)
    val byteSel = muxTree(offset, (0 until lanes).map(i => rdata.bits(i * 8 + 7, i * 8)))
    val halfSel = muxTree(offset, (0 until lanes).map(i => rdata.bits((i & ~1) * 8 + 15, (i & ~1) * 8)))
    // A word access sits in one half of a 64-bit bus word; at xlen 32 the bus word is the word.
    val wordSel: Node[Bits] =
      if lanes > 4 then (Node(offset.asBits).bit(offsetWidth - 1) ? (rdata.bits(63, 32), rdata.bits(31, 0)))
      else rdata
    val wordSext: Node[Bits] = if lanes > 4 then (fill(width - 32, wordSel.bit(31)) ## wordSel) else wordSel
    val wordZext: Node[Bits] = if lanes > 4 then (BigInt(0).B(width - 32) ## wordSel) else wordSel
    val zero: Referable[Bits] = BigInt(0).B(width)

    /** One op's load result: sign- or zero-extended from the addressed byte, half, word or the whole bus word. The
      * stores have nothing to answer with.
      */
    def load(v: LsuOp): Referable[Bits] = v match
      case LsuOp.LB                                  => fill(width - 8, byteSel.bit(7)) ## byteSel
      case LsuOp.LH                                  => fill(width - 16, halfSel.bit(15)) ## halfSel
      case LsuOp.LW                                  => wordSext
      case LsuOp.LBU                                 => BigInt(0).B(width - 8) ## byteSel
      case LsuOp.LHU                                 => BigInt(0).B(width - 16) ## halfSel
      case LsuOp.LWU                                 => wordZext
      case LsuOp.LD                                  => rdata
      case LsuOp.SB | LsuOp.SH | LsuOp.SW | LsuOp.SD => zero

    val loadData = mux1H(op, lsuOps.variants.map(load), width)

    val isMmio: Referable[Bool] = parameter.windows.foldLeft(false.B: Referable[Bool]): (acc, window) =>
      val (base, size) = window
      acc | ((addr >= base.U(width)) & (addr < (base + size).U(width)))

    io.done := hit
    io.result.data := (isStore ? (0.U(width), loadData.asUInt))
    io.result.next_pc := prefixAdder(u.pc, 4.U(width), false.B, width)
    io.result.csr_type := CsrType.None.lit.U(CsrType.width)
    io.result.csr_data := 0.U(width)
    io.result.is_mmio := isMmio
