package nzea_device.dpi

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The commit trace a debugger observes: the architectural facts of one retired instruction.
  *
  * Structurally the core's `CommitMsg`, declared here so the debugger needs no core types: the CSR is an architectural
  * `(valid, addr)` pair, not the core's internal `CsrType` code.
  */
class CommitTrace(width: Int, gprIndexWidth: Int, csrAddrWidth: Int) extends Bundle:
    val next_pc = Aligned(UInt(width))
    val rd_index = Aligned(UInt(gprIndexWidth))
    val rd_value = Aligned(UInt(width))
    val is_mmio = Aligned(Bool())
    val csr_valid = Aligned(Bool())
    val csr_addr = Aligned(UInt(csrAddrWidth))
    val csr_data = Aligned(UInt(width))

case class DebuggerParameter(width: Int, gprIndexWidth: Int, csrAddrWidth: Int) extends Parameter:
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

/** The debugger device: it observes the core's commit trace and, for now, forwards it to remu's `commit_trace`.
  *
  * The commit trace is a sideband — it is not on the bus — and its natural consumer is a debugger: a real JTAG TAP that
  * streams retired instructions out. That is left as a TODO; the bridge flow's implementation forwards each commit to
  * the simulator. Putting the trace's DPI here rather than in a level top keeps the rule the tile follows: every
  * interface is attached to a device, and the DPI is the device's own affair.
  */
@generator
object Debugger extends Generator[DebuggerParameter, DebuggerLayers, DebuggerIO, DebuggerProbe]:
    override def moduleName(parameter: DebuggerParameter): String = "Debugger"

    def architecture(parameter: DebuggerParameter) =
        val io = summon[Interface[DebuggerIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

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
