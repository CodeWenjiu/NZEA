package nzea_fpga.device.ddr3

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.bus.LiteBusRW
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** What the adapter is told: the narrow bus's shape, and the window's base — the address the level above gives the
  * memory, which the device's own decode does not know (a burst's address is the memory's, not the window's).
  */
case class Ddr3BusAdapterParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    base: Long
) extends Parameter:
    require(addrWidth >= Ddr3BusShape.addrWidth, s"addrWidth must be >= ${Ddr3BusShape.addrWidth}, got $addrWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(
        Ddr3BusShape.dataWidth % dataWidth == 0,
        s"dataWidth must divide the burst's ${Ddr3BusShape.dataWidth} bits, got $dataWidth"
    )
    require(base >= 0, s"base must be >= 0, got $base")

given upickle.default.ReadWriter[Ddr3BusAdapterParameter] = upickle.default.macroRW

class Ddr3BusAdapterLayers(parameter: Ddr3BusAdapterParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class Ddr3BusAdapterIO(parameter: Ddr3BusAdapterParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The narrow side: the fabric's own vocabulary, so the adapter is its slave. */
    val narrow =
        Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

    /** The device's side: one whole burst per access, in the shapes [[Ddr3BusShape]] states. */
    val ddr = Aligned(
        new LiteBusRW(
            Ddr3BusShape.addrWidth,
            Ddr3BusShape.dataWidth,
            Ddr3BusShape.userWidth,
            Ddr3BusShape.idWidth
        )
    )

class Ddr3BusAdapterProbe(parameter: Ddr3BusAdapterParameter)
    extends DVBundle[Ddr3BusAdapterParameter, Ddr3BusAdapterLayers](parameter)

/** The fabric's bus, in the memory's vocabulary: one narrow access becomes one whole burst — the narrow word placed at
  * its own byte offset inside the beat, its strobe with it, and the answer's bytes taken from the same offset.
  *
  * Why one access is one burst, and not a burst gathered over several accesses: the device is *addressed* per burst —
  * the front door decodes `addr[28:4]` into row, bank and column and leaves `addr[3:0]` to the bus ([[Ddr3BusShape]]),
  * so an access's low bits are already the bus's own, and the burst is the least the memory can be asked for. Gathering
  * four 32-bit writes into one burst would buy pin time the design does not need (the loader is line-bound at 87 µs a
  * word, the core is latency-bound), and would cost the write's acknowledgment its meaning.
  *
  * What the narrow side must hold to: a narrow access lies inside *one* burst. A word at a four-byte boundary never
  * crosses a sixteen-byte one, and both masters (the core's fetch and load/store units, the loader's word writer) are
  * aligned — an access that straddled would need two bursts, which is a different adapter.
  */
@generator
object Ddr3BusAdapter
    extends Generator[Ddr3BusAdapterParameter, Ddr3BusAdapterLayers, Ddr3BusAdapterIO, Ddr3BusAdapterProbe]:
    override def moduleName(parameter: Ddr3BusAdapterParameter): String = "Ddr3BusAdapter"

    def architecture(parameter: Ddr3BusAdapterParameter) =
        val io = summon[Interface[Ddr3BusAdapterIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.asyncActiveLow(io.resetN)

        val narrow = io.narrow
        val ddr = io.ddr

        val sIdle = 0
        val sReq = 1
        val sResp = 2
        val sHold = 3
        val width = 2

        val state = RegInit(sIdle.U(width))
        // The request the access is being served from: its low address bits place the word in the burst and slice the
        // answer out of it, and its user/id are the fabric's own — the device echoes one user bit of its own, so the
        // answer's fields come from here, not from the memory.
        val held = Reg(narrow.req.bits.getType)
        val readWord = RegInit(0.U(parameter.dataWidth))

        narrow.req.ready := state === sIdle.U(width)
        when(narrow.req.valid & narrow.req.ready) {
            held :<= narrow.req.bits
            state := sReq.U(width)
        }

        val byteOffset: Node[UInt] = held.addr.asBits.bits(3, 0).asUInt
        val bitOffset: Node[UInt] = (byteOffset.asBits ## 0.U(3).asBits).asUInt
        // The window's base comes off here: what the device decodes is the memory's own address (a burst is sixteen
        // bytes, so only the bits above the beat survive the subtraction anyway).
        val burstAddr: Node[UInt] = prefixSub(held.addr, BigInt(parameter.base).U(parameter.addrWidth), parameter.addrWidth)._1
        val wideData: Node[Bits] = (held.wdata.asBits << bitOffset).bits(Ddr3BusShape.dataWidth - 1, 0)
        val wideStrb: Node[Bits] = (held.wstrb.asBits << byteOffset).bits(Ddr3BusShape.dataWidth / 8 - 1, 0)

        ddr.req.valid := state === sReq.U(width)
        ddr.req.bits.addr := burstAddr
        ddr.req.bits.wdata := wideData.asUInt
        ddr.req.bits.wstrb := wideStrb.asUInt
        ddr.req.bits.wen := held.wen
        ddr.req.bits.user := 0.U(Ddr3BusShape.userWidth)
        ddr.req.bits.id := 0.U(Ddr3BusShape.idWidth)
        ddr.resp.ready := true.B

        when(ddr.req.valid & ddr.req.ready) {
            state := sResp.U(width)
        }

        // The answer, sliced at the access's own byte offset and registered *before* it is offered: the device's own
        // answer is taken the cycle it arrives, and the narrow side's valid must not rise while the word it presents
        // is still the previous one's.
        when(state === sResp.U(width) & ddr.resp.valid) {
            readWord := (ddr.resp.bits.data.asBits >> bitOffset).bits(parameter.dataWidth - 1, 0).asUInt
            state := sHold.U(width)
        }

        narrow.resp.valid := state === sHold.U(width)
        narrow.resp.bits.data := readWord
        narrow.resp.bits.user := held.user
        narrow.resp.bits.id := held.id
        when(narrow.resp.valid & narrow.resp.ready) {
            state := sIdle.U(width)
        }
