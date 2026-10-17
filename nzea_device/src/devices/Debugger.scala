package nzea_device.devices

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.RtlFlow
import nzea_rtl.stdlib.*
import nzea_sideband.CommitTrace
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class DebuggerParameter(width: Int, gprIndexWidth: Int, csrAddrWidth: Int, flow: RtlFlow) extends Parameter:
    require(width >= 1, s"width must be >= 1, got $width")
    require(gprIndexWidth >= 1, s"gprIndexWidth must be >= 1, got $gprIndexWidth")
    require(csrAddrWidth >= 1, s"csrAddrWidth must be >= 1, got $csrAddrWidth")

given upickle.default.ReadWriter[DebuggerParameter] = upickle.default.macroRW

class DebuggerLayers(parameter: DebuggerParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class DebuggerIO(parameter: DebuggerParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    val trace =
        Flipped(ValidIO(new CommitTrace(parameter.width, parameter.gprIndexWidth, parameter.csrAddrWidth)))

class DebuggerProbe(parameter: DebuggerParameter) extends DVBundle[DebuggerParameter, DebuggerLayers](parameter)

/** The debugger device: it observes the core's commit trace and hands it to whoever consumes it, the choice being the
  * flow's.
  *
  * The commit trace is a sideband — it is not on the bus — and its natural consumer is a debugger: a real JTAG TAP that
  * streams retired instructions out. That is left as a TODO; the bridge flow's implementation forwards each commit to
  * the simulator, while a synthesis flow has no consumer yet, so the device is inert and its trace input goes unread.
  * The trace's DPI lives here rather than in a level top: every interface is attached to a device, and the DPI is the
  * device's own affair.
  */
@generator
object Debugger extends Generator[DebuggerParameter, DebuggerLayers, DebuggerIO, DebuggerProbe]:
    override def moduleName(parameter: DebuggerParameter): String = "Debugger"

    def architecture(parameter: DebuggerParameter) =
        val io = summon[Interface[DebuggerIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        if parameter.flow.bridges then
            dpiClockedCall(
                "commit_trace",
                Some(Seq("next_pc", "csr_valid", "csr_addr", "csr_data", "gpr_addr", "gpr_data", "is_mmio"))
            )(
                io.clock,
                io.trace.valid,
                io.trace.bits.next_pc,
                io.trace.bits.csr_valid,
                io.trace.bits.csr_addr.asBits.pad(32),
                io.trace.bits.csr_data,
                io.trace.bits.rd_index.asBits.pad(32),
                io.trace.bits.rd_value,
                io.trace.bits.is_mmio
            )
