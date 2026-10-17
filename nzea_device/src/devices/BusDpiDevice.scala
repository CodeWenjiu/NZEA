package nzea_device.devices

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.bus.{LiteBusRO, LiteBusRW}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The read-only bus device: a device whose "hardware" is the simulator, answering remu's `bus_read` for a read-only
  * master (the fetch port). There is no hardware version: outside the bridge flow this device has no meaning. It carries
  * an elastic one-slot response register.
  */
case class IbusDpiDeviceParameter(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")

given upickle.default.ReadWriter[IbusDpiDeviceParameter] = upickle.default.macroRW

class IbusDpiDeviceLayers(parameter: IbusDpiDeviceParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class IbusDpiDeviceIO(parameter: IbusDpiDeviceParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val bus = Flipped(new LiteBusRO(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

class IbusDpiDeviceProbe(parameter: IbusDpiDeviceParameter)
    extends DVBundle[IbusDpiDeviceParameter, IbusDpiDeviceLayers](parameter)

@generator
object IbusDpiDevice
    extends Generator[IbusDpiDeviceParameter, IbusDpiDeviceLayers, IbusDpiDeviceIO, IbusDpiDeviceProbe]:
    override def moduleName(parameter: IbusDpiDeviceParameter): String = "IbusDpiDevice"

    def architecture(parameter: IbusDpiDeviceParameter) =
        val io = summon[Interface[IbusDpiDeviceIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.dataWidth

        val respValid = RegInit(false.B)
        val respData = Reg(UInt(width))
        val respUser = Reg(UInt(parameter.userWidth))
        val respId = Reg(UInt(parameter.idWidth))

        // `resetN` is active low, so ready is gated on it *high*: a device refuses traffic while it is being reset. The
        // slot is elastic — taken-and-refilled in the same cycle is a handover, keeping the round trip at one request per
        // cycle.
        io.bus.req.ready := (!respValid | io.bus.resp.ready) & io.resetN.asBool
        val fire = io.bus.req.valid & io.bus.req.ready
        val rdata = dpiUnclockedCall("bus_read", UInt(width), Some(Seq("addr")), Some("rdata"))(
            fire,
            io.bus.req.bits.addr
        )

        when(fire) {
            respValid := true.B
            respData := rdata
            respUser := io.bus.req.bits.user
            respId := io.bus.req.bits.id
        }.otherwise {
            when(respValid & io.bus.resp.ready) {
                respValid := false.B
            }
        }

        io.bus.resp.valid := respValid
        io.bus.resp.bits.data := respData
        io.bus.resp.bits.user := respUser
        io.bus.resp.bits.id := respId

/** The read/write bus device: the same, answering remu's `bus_read` / `bus_write`. A load answers from `bus_read`, a
  * store from `bus_write` — a store's response acknowledges the request rather than carrying data — and `wstrb` is
  * widened to the ABI's `int`, because the generated call takes the declared argument widths as they are. This is the
  * device behind every read/write window.
  */
case class BusDpiDeviceParameter(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")

given upickle.default.ReadWriter[BusDpiDeviceParameter] = upickle.default.macroRW

class BusDpiDeviceLayers(parameter: BusDpiDeviceParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class BusDpiDeviceIO(parameter: BusDpiDeviceParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val bus = Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

class BusDpiDeviceProbe(parameter: BusDpiDeviceParameter)
    extends DVBundle[BusDpiDeviceParameter, BusDpiDeviceLayers](parameter)

@generator
object BusDpiDevice extends Generator[BusDpiDeviceParameter, BusDpiDeviceLayers, BusDpiDeviceIO, BusDpiDeviceProbe]:
    override def moduleName(parameter: BusDpiDeviceParameter): String = "BusDpiDevice"

    def architecture(parameter: BusDpiDeviceParameter) =
        val io = summon[Interface[BusDpiDeviceIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.dataWidth

        val respValid = RegInit(false.B)
        val respData = Reg(UInt(width))
        val respUser = Reg(UInt(parameter.userWidth))
        val respId = Reg(UInt(parameter.idWidth))

        io.bus.req.ready := (!respValid | io.bus.resp.ready) & io.resetN.asBool
        val fire = io.bus.req.valid & io.bus.req.ready
        val isRead = !io.bus.req.bits.wen
        val rdata = dpiUnclockedCall("bus_read", UInt(width), Some(Seq("addr")), Some("rdata"))(
            fire & isRead,
            io.bus.req.bits.addr
        )
        dpiClockedCall("bus_write", Some(Seq("addr", "wdata", "wstrb")))(
            io.clock,
            fire & io.bus.req.bits.wen,
            io.bus.req.bits.addr,
            io.bus.req.bits.wdata,
            io.bus.req.bits.wstrb.asBits.pad(32)
        )

        when(fire) {
            respValid := true.B
            respData := isRead ? (rdata, 0.U(width))
            respUser := io.bus.req.bits.user
            respId := io.bus.req.bits.id
        }.otherwise {
            when(respValid & io.bus.resp.ready) {
                respValid := false.B
            }
        }

        io.bus.resp.valid := respValid
        io.bus.resp.bits.data := respData
        io.bus.resp.bits.user := respUser
        io.bus.resp.bits.id := respId
