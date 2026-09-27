package nzea_core.back.mem

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_config.core.CoreConfig
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

case class MemPathParameter(isa: String, defaultPc: Long, mmioRanges: Seq[String]) extends Parameter:
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

    val op: Node[UInt] = MemOp.payload(u.op)
    val isStore = LsuOp.isStore(op)

    val addr = prefixAdder(u.src_a, u.src_b, false.B, width)
    val addrBits: Node[Bits] = Node(addr.asBits)
    val offset: Node[UInt] = addrBits.bits(1, 0).asUInt

    // Store data and strobe, both selected by the two offset bits: a constant shift per data position, a strobe per
    // access size, one-hot over the op.
    val byteStrb: Referable[UInt] = muxTree(offset, Seq(1, 2, 4, 8).map(v => BigInt(v).U(width / 8)))
    val halfStrb: Referable[UInt] = muxTree(offset, Seq(3, 3, 12, 12).map(v => BigInt(v).U(width / 8)))
    val zeroStrb: Referable[UInt] = BigInt(0).U(width / 8)
    val wordStrb: Referable[UInt] = ((BigInt(1) << (width / 8)) - 1).U(width / 8)
    val wstrb = mux1H(
      op,
      Seq(zeroStrb, zeroStrb, zeroStrb, zeroStrb, zeroStrb, byteStrb, halfStrb, wordStrb),
      width / 8
    )
    val storeData: Node[Bits] = Node(u.src_c.asBits)
    val wdata = muxTree(offset, Seq(0, 8, 16, 24).map(shift => (storeData << shift).bits(width - 1, 0)))

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
    io.dbus.req.bits.addr := (addrBits.bits(width - 1, 2) ## 0.B(2)).asUInt
    io.dbus.req.bits.wdata := wdata.asUInt
    io.dbus.req.bits.wen := isStore
    io.dbus.req.bits.wstrb := wstrb
    io.dbus.req.bits.user := DbusUser.pack(curTag)
    io.dbus.req.bits.id := 0.U(busIdWidth)

    io.dbus.resp.ready := true.B
    val hit = io.dbus.resp.valid & uopValid & (DbusUser.tagOf(io.dbus.resp.bits.user) === curTag)

    // Load result: sign- or zero-extended from the addressed byte or half of the word.
    val rdata: Node[Bits] = Node(io.dbus.resp.bits.data.asBits)
    val byteSel = muxTree(
      offset,
      Seq(rdata.bits(7, 0), rdata.bits(15, 8), rdata.bits(23, 16), rdata.bits(31, 24))
    )
    val halfSel = muxTree(
      offset,
      Seq(rdata.bits(15, 0), rdata.bits(15, 0), rdata.bits(31, 16), rdata.bits(31, 16))
    )
    val zero: Referable[Bits] = BigInt(0).B(width)
    val loadData = mux1H(
      op,
      Seq(
        fill(width - 8, byteSel.bit(7)) ## byteSel,
        fill(width - 16, halfSel.bit(15)) ## halfSel,
        rdata,
        BigInt(0).B(width - 8) ## byteSel,
        BigInt(0).B(width - 16) ## halfSel,
        zero,
        zero,
        zero
      ),
      width
    )

    val isMmio: Referable[Bool] = parameter.windows.foldLeft(false.B: Referable[Bool]): (acc, window) =>
      val (base, size) = window
      acc | ((addr >= base.U(width)) & (addr < (base + size).U(width)))

    io.done := hit
    io.result.data := (isStore ? (0.U(width), loadData.asUInt))
    io.result.next_pc := prefixAdder(u.pc, 4.U(width), false.B, width)
    io.result.csr_type := CsrType.None.lit.U(CsrType.width)
    io.result.csr_data := 0.U(width)
    io.result.is_mmio := isMmio
