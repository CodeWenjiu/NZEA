package nzea_device.devices

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_device.fatal
import nzea_rtl.bus.LiteBusRW
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The body of a device that exists on the address map but has no implementation yet — the hardware `todo!()`, not a
  * device kind: no address map entry names it, and a device with more than one implementation calls it from the branch
  * that has none ([[PeripheralBridge]] in a synthesis flow).
  *
  * The bus handshake is answered so a master cannot hang, the read data is a defined zero so the window is
  * synthesizable, and an access stops the simulation — loud instead of feeding the program plausible data.
  */
object Todo:

    def answer(
        bus: Referable[LiteBusRW],
        resetN: Referable[Bool],
        dataWidth: Int,
        userWidth: Int,
        idWidth: Int
    )(using
        ClockScope,
        ResetScope,
        Arena,
        Context,
        Block,
        TypeImpl,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Unit =
        val respValid = RegInit(false.B)
        val respUser = Reg(UInt(userWidth))
        val respId = Reg(UInt(idWidth))

        bus.req.ready := (!respValid | bus.resp.ready) & resetN
        val fire = bus.req.valid & bus.req.ready
        fatal(summon[ClockScope].clock, fire)
        when(fire) {
            respValid := true.B
            respUser := bus.req.bits.user
            respId := bus.req.bits.id
        }.otherwise {
            when(respValid & bus.resp.ready) {
                respValid := false.B
            }
        }

        bus.resp.valid := respValid
        bus.resp.bits.data := 0.U(dataWidth)
        bus.resp.bits.user := respUser
        bus.resp.bits.id := respId
