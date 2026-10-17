package nzea_fpga.logic

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_device.devices.{RamFabricSlave, RamFabricSlaveParameter}
import nzea_rtl.RtlFlow
import nzea_rtl.bus.LiteBusRW
import nzea_rtl.stdlib.SyncRamPorts
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The memory's shape: the fabric's geometry, the window it answers, and the shape its macro is built with. */
case class CoreMemoryParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    base: Long,
    size: Long,
    ports: SyncRamPorts,
    flow: RtlFlow
) extends Parameter:
    require(size > 0, s"size must be > 0, got $size")

given upickle.default.ReadWriter[CoreMemoryParameter] = upickle.default.macroRW

class CoreMemoryLayers(parameter: CoreMemoryParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The memory's ports: the domain it runs in, the level's own knowledge of whether the core is held, and the two
  * masters it serves — the core, which is the tile's window, and the loader.
  */
class CoreMemoryIO(parameter: CoreMemoryParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())

    /** The loader's own domain: a load does not hold its memory, or the loader could not write it. */
    val resetN = Flipped(Reset())

    /** The core's domain is in reset: its side of the memory is drained and never selected ([[CoreMemory]]). */
    val coreHeld = Flipped(Bool())

    val core = Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))
    val load = Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

class CoreMemoryProbe(parameter: CoreMemoryParameter) extends DVBundle[CoreMemoryParameter, CoreMemoryLayers](parameter)

/** The core's memory, with the loader's own port on it: the tile's window and the boot loader both write and read it,
  * so something has to say who a request belongs to — that is this module.
  *
  * The loader always wins when it asks, and it is right to: the level holds the core's domain for the whole load
  * ([[BootSram.HoldReason]]), so a load's traffic and the core's never overlap by construction. What can overlap is a
  * request the core had *already* been granted when the hold landed: its answer would come back to a master that is in
  * reset, and the memory's own port would stall on it forever. So a held core's answers are drained here — the level
  * knows the core is held (`coreHeld`, the same reason level the reset network waits for), and a core that lost an
  * answer re-issues it after its release.
  */
@generator
object CoreMemory extends Generator[CoreMemoryParameter, CoreMemoryLayers, CoreMemoryIO, CoreMemoryProbe]:
    override def moduleName(parameter: CoreMemoryParameter): String = "CoreMemory"

    def architecture(parameter: CoreMemoryParameter) =
        val io = summon[Interface[CoreMemoryIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.asyncActiveLow(io.resetN)

        val core = io.core
        val load = io.load

        val mem = RamFabricSlave.instantiate(
            RamFabricSlaveParameter(
                parameter.addrWidth,
                parameter.dataWidth,
                parameter.userWidth,
                parameter.idWidth,
                parameter.base,
                parameter.size,
                parameter.ports,
                parameter.flow
            )
        )
        mem.io.clock := io.clock
        mem.io.resetN := io.resetN
        // The macro's clock is this domain's: an fpga's block RAM has no cycle time of its own to protect, so the
        // device's two-phase handshake runs on the one clock (at the cost of its few cycles per access).
        mem.io.boundary.clock := io.clock

        // Whose request the memory is serving: false for the core, true for the loader — taken when a request is
        // granted, held until its answer is.
        val serving = RegInit(false.B)
        val loadWins = load.req.valid

        mem.io.bus.req.valid := (core.req.valid & !io.coreHeld) | load.req.valid
        mem.io.bus.req.bits.addr := loadWins ? (load.req.bits.addr, core.req.bits.addr)
        mem.io.bus.req.bits.wdata := loadWins ? (load.req.bits.wdata, core.req.bits.wdata)
        mem.io.bus.req.bits.wen := loadWins ? (load.req.bits.wen, core.req.bits.wen)
        mem.io.bus.req.bits.wstrb := loadWins ? (load.req.bits.wstrb, core.req.bits.wstrb)
        mem.io.bus.req.bits.user := loadWins ? (load.req.bits.user, core.req.bits.user)
        mem.io.bus.req.bits.id := loadWins ? (load.req.bits.id, core.req.bits.id)

        core.req.ready := !loadWins & !io.coreHeld & mem.io.bus.req.ready
        load.req.ready := loadWins & mem.io.bus.req.ready

        when(mem.io.bus.req.valid & mem.io.bus.req.ready) {
            serving := loadWins
        }
        when(mem.io.bus.resp.valid & mem.io.bus.resp.ready) {
            serving := false.B
        }

        mem.io.bus.resp.ready := serving ? (load.resp.ready, core.resp.ready | io.coreHeld)
        load.resp.valid := mem.io.bus.resp.valid & serving
        core.resp.valid := mem.io.bus.resp.valid & !serving
        for (resp, bits) <- Seq((load.resp, mem.io.bus.resp.bits), (core.resp, mem.io.bus.resp.bits)) do
            resp.bits.data := bits.data
            resp.bits.user := bits.user
            resp.bits.id := bits.id
