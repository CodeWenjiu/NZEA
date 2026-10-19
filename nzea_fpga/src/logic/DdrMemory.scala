package nzea_fpga.logic

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_fpga.device.ddr3.{Ddr3BusAdapter, Ddr3BusAdapterParameter, Ddr3PadNames, Ddr3Side, Ddr3SideParameter}
import nzea_fpga.{BoardLogic, Clocks, LogicNeeds, Logics}
import nzea_rtl.bus.{LiteBusCdc, LiteBusCdcParameter, LiteBusRW}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The memory's shape from the level's side: the fabric window's geometry, the base the memory answers at, and how deep
  * the crossing's levels are synchronized.
  */
case class DdrMemoryParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    base: Long,
    syncStages: Int
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(base >= 0, s"base must be >= 0, got $base")

given upickle.default.ReadWriter[DdrMemoryParameter] = upickle.default.macroRW

class DdrMemoryLayers(parameter: DdrMemoryParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The memory's ports: the DDR side's statement ([[nzea_fpga.device.ddr3.Ddr3Side.needs]]) plus what the level wires
  * here — its own clock and reset for the near side, the two masters' buses, and the level's own knowledge of whether
  * the core is in its reset — the same net the tile's own reset port takes, so a hold stated as a reason
  * ([[nzea_fpga.logic.BootDdr]]) is one of the reasons it carries.
  */
class DdrMemoryIO(parameter: DdrMemoryParameter) extends HWRecord(parameter):
    Logics.declare(this, DdrMemory.needs)

    Flipped("nearClock", Clock())
    Flipped("nearResetN", Reset())

    Flipped("core", new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))
    Flipped("load", new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

    Flipped("coreHeld", Bool())

class DdrMemoryProbe(parameter: DdrMemoryParameter) extends DVRecord[DdrMemoryParameter, DdrMemoryLayers](parameter)

/** The core's memory, out of the board's DDR3: the tile's window and the loader's port, one arbiter, one crossing, one
  * whole-burst adaptation, and the device itself.
  *
  * The path is three pieces, each stated where its own fact lives:
  *
  *   - the arbitration is this module's: the loader wins when it asks — the level holds the core's domain for the whole
  *     load, so a load's traffic and the core's never overlap — and a *reset* core's answers are drained, because a
  *     master in reset would never take them and the memory's single request slot would stall on one forever. The drain
  *     is *latched* rather than read live: the core's reset can lift before the answer it orphaned comes back (the
  *     crossing's own response register belongs to the loader's domain and is not reset with the core), and an answer
  *     nobody waits for would otherwise wedge the slot for good;
  *   - the crossing is [[nzea_rtl.bus.LiteBusCdc]]'s: the level's domain against the controller's own grid, one
  *     transaction outstanding, payloads held rather than synchronized. The two sides' resets are the level's reasons
  *     of one network ([[nzea_rtl.stdlib.Resets]]) — a load is what holds the core, and both domains wait for the
  *     device's own readiness besides;
  *   - the adaptation is the device's ([[nzea_fpga.device.ddr3.Ddr3BusAdapter]]): one narrow access becomes one whole
  *     burst, because that is the least the memory can be asked for.
  *
  * The device's readiness leaves here as a reason of its own ([[nzea_fpga.device.ddr3.Ddr3Side.ReadyReason]]): the
  * *reader* of the memory is what has to wait for the calibration, and the core is that reader.
  */
@generator
object DdrMemory extends Generator[DdrMemoryParameter, DdrMemoryLayers, DdrMemoryIO, DdrMemoryProbe], BoardLogic:
    override def moduleName(parameter: DdrMemoryParameter): String = "DdrMemory"

    override def needs: LogicNeeds = Ddr3Side.needs

    def architecture(parameter: DdrMemoryParameter) =
        val io = summon[Interface[DdrMemoryIO]]

        val ddr3 = Ddr3Side.instantiate(
            Ddr3SideParameter(
                dq = io.field[Analog](Ddr3PadNames.dq),
                dqsP = io.field[Analog](Ddr3PadNames.dqsP),
                dqsN = io.field[Analog](Ddr3PadNames.dqsN),
                ckP = io.field[Bool](Ddr3PadNames.ckP),
                ckN = io.field[Bool](Ddr3PadNames.ckN),
                addr = io.field[UInt](Ddr3PadNames.addr),
                ba = io.field[UInt](Ddr3PadNames.ba),
                dm = io.field[UInt](Ddr3PadNames.dm),
                rasN = io.field[UInt](Ddr3PadNames.rasN),
                casN = io.field[UInt](Ddr3PadNames.casN),
                weN = io.field[UInt](Ddr3PadNames.weN),
                cke = io.field[Bool](Ddr3PadNames.cke),
                odt = io.field[Bool](Ddr3PadNames.odt),
                resetN = io.field[Bool](Ddr3PadNames.resetN),
                rawResetN = io.field[Bool](Ddr3Side.ButtonPin),
                reset = io.field[Reset](Logics.ResetPort),
                tapsReady = io.field[Bool](Ddr3Side.TapsReadyReason),
                clocks = Clocks.ports(io, Ddr3Side.needs.grids)
            )
        )
        io.field[Bool](Ddr3Side.ReadyReason) := ddr3.ready

        val nearClock = io.field[Clock]("nearClock")
        val nearResetN = io.field[Reset]("nearResetN")
        val core = io.field[LiteBusRW]("core")
        val load = io.field[LiteBusRW]("load")
        val coreHeld = io.field[Bool]("coreHeld")

        val cdc = LiteBusCdc.instantiate(
            LiteBusCdcParameter(
                parameter.addrWidth,
                parameter.dataWidth,
                parameter.userWidth,
                parameter.idWidth,
                parameter.syncStages
            )
        )
        // What the crossing's own registers need, said where the crossing is built ([[BusCdcXdc]]).
        BusCdcXdc.register(parameter.syncStages)
        val adapter = Ddr3BusAdapter.instantiate(
            Ddr3BusAdapterParameter(
                parameter.addrWidth,
                parameter.dataWidth,
                parameter.userWidth,
                parameter.idWidth,
                parameter.base
            )
        )

        cdc.io.nearClock := nearClock
        cdc.io.nearResetN := nearResetN
        cdc.io.farClock := ddr3.clock
        cdc.io.farResetN := ddr3.resetN

        adapter.io.clock := ddr3.clock
        adapter.io.resetN := ddr3.resetN
        adapter.io.narrow :<>= cdc.io.far
        ddr3.bus :<>= adapter.io.ddr

        // The near side, in the level's own domain: whose transaction is in flight — false for the core, true for the
        // loader, taken when a request is granted and held until its answer is. `coreHeld` is the core's own domain
        // reset as the tile sees it, so any reason that takes the core away (a load's hold among them) is this one
        // level: requests of a core that is not there are not taken, and answers it will not be there for are.
        ClockScope.posedge(nearClock):
            ResetScope.asyncActiveLow(nearResetN):
                val serving = RegInit(false.B)
                val loadWins = load.req.valid

                // An answer the core was reset out from under. The request was accepted on the core's behalf and the
                // core is gone — possibly *already back* (its reset lifted while the answer was still travelling), so
                // the condition cannot be read live at the moment the answer lands. Latched here instead: set while
                // the core is away, cleared when the slot is free again, which is after the answer was taken.
                val abandoned = RegInit(false.B)

                cdc.io.near.req.valid := (core.req.valid & !coreHeld) | loadWins
                cdc.io.near.req.bits.addr := loadWins ? (load.req.bits.addr, core.req.bits.addr)
                cdc.io.near.req.bits.wdata := loadWins ? (load.req.bits.wdata, core.req.bits.wdata)
                cdc.io.near.req.bits.wen := loadWins ? (load.req.bits.wen, core.req.bits.wen)
                cdc.io.near.req.bits.wstrb := loadWins ? (load.req.bits.wstrb, core.req.bits.wstrb)
                cdc.io.near.req.bits.user := loadWins ? (load.req.bits.user, core.req.bits.user)
                cdc.io.near.req.bits.id := loadWins ? (load.req.bits.id, core.req.bits.id)

                core.req.ready := !loadWins & !coreHeld & cdc.io.near.req.ready
                load.req.ready := loadWins & cdc.io.near.req.ready

                when(cdc.io.near.req.valid & cdc.io.near.req.ready) {
                    serving := loadWins
                }
                when(coreHeld) {
                    abandoned := true.B
                }
                when(cdc.io.near.resp.valid & cdc.io.near.resp.ready) {
                    serving := false.B
                    abandoned := false.B
                }

                val coreTakes: Referable[Bool] = core.resp.ready | coreHeld | abandoned
                val loadTakes: Referable[Bool] = load.resp.ready
                cdc.io.near.resp.ready := serving ? (loadTakes, coreTakes)
                load.resp.valid := cdc.io.near.resp.valid & serving
                core.resp.valid := cdc.io.near.resp.valid & !serving
                for (resp, bits) <- Seq((load.resp, cdc.io.near.resp.bits), (core.resp, cdc.io.near.resp.bits))
                do
                    resp.bits.data := bits.data
                    resp.bits.user := bits.user
                    resp.bits.id := bits.id
