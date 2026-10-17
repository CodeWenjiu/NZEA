package nzea_core.middle.isu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.front.idu.IduOut
import nzea_core.isa.Isa
import nzea_core.back.{ExecSlot, ForwardBus, ForwardParameter}
import nzea_core.middle.rf.{ArfPort, CsrPort}
import nzea_core.stats.Regions
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class IsuParameter(
    isa: Isa,
    defaultPc: Long,
    flow: RtlFlow,
    lsqDepth: Int,
    stats: StatScope
) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

    val offerCount: Int = ForwardParameter.offerCount(lsqDepth)

given upickle.default.ReadWriter[IsuParameter] = upickle.default.macroRW

class IsuLayers(parameter: IsuParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class IsuIO(parameter: IsuParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    val decoded = Flipped(Decoupled(new IduOut(parameter.config)))

    val moved = Aligned(Decoupled(new ExecSlot(parameter.config)))

    /** The register file's read side, from the reader's view. rs1 is port 0, rs2 port 1. */
    val arf_read = Flipped(StaticVec[ArfPort, 2](new ArfPort(width)))

    val csr_read = Flipped(new CsrPort(width))

    val forward = Flipped(new ForwardBus(width, parameter.offerCount))

class IsuProbe(parameter: IsuParameter) extends DVBundle[IsuParameter, IsuLayers](parameter)

/** The issue stage: the register read and the assembly, combinational, between the middle's two elastic registers.
  *
  * The ID/IS and IS/EX registers are not this unit's — they are declared one level up, beside every other stage
  * boundary, which is the rule the front-end follows around its decode — and this unit is what sits between them: it
  * reads the ID/IS register and fills the IS/EX register's write side, whose read side belongs to the execute stage.
  * The operands come already placed from [[nzea_core.middle.isu.OperandRead]]; this unit packs them into the `moved`
  * payload.
  */
@generator
object Isu extends Generator[IsuParameter, IsuLayers, IsuIO, IsuProbe]:
    override def moduleName(parameter: IsuParameter): String = "Isu"

    def architecture(parameter: IsuParameter) =
        val io = summon[Interface[IsuIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val read =
            OperandRead.instantiate(OperandReadParameter(parameter.isa, parameter.defaultPc, parameter.lsqDepth))

        read.io.clock := io.clock
        read.io.resetN := io.resetN
        read.io.decoded :<= io.decoded.bits
        io.arf_read.ref(0).index := read.io.rs1_index
        io.arf_read.ref(1).index := read.io.rs2_index
        read.io.rs1_data := io.arf_read.ref(0).data
        read.io.rs2_data := io.arf_read.ref(1).data
        read.io.rs1_commit_hit := io.arf_read.ref(0).commit_hit
        read.io.rs2_commit_hit := io.arf_read.ref(1).commit_hit
        read.io.csr_read :<>= io.csr_read

        read.io.forward :<= io.forward

        val dec = io.decoded.bits
        io.moved.bits.pc := dec.pc
        io.moved.bits.next_pc := dec.next_pc
        io.moved.bits.fall_through := dec.fall_through
        io.moved.bits.pht_count := dec.pht_count
        io.moved.bits.src.ref(0) := read.io.src_a
        io.moved.bits.src.ref(1) := read.io.src_b
        io.moved.bits.src.ref(2) := read.io.src_c
        io.moved.bits.src.ref(3) := read.io.src_d
        io.moved.bits.backend := dec.backend
        io.moved.bits.op := dec.op
        io.moved.bits.rd_index := dec.rd_index
        io.moved.bits.csr_type := read.io.csr_type

        // The handover: ID/IS is consumed when the read has placed the payload's operands — the read owns that test,
        // including the in-flight writers it had to wait for — and the register below reports room: its own accept side
        // already carries the flush rule, so a handover the flush refuses never reaches here. The uop moves in that
        // cycle, so the register presents it the next one.
        io.decoded.ready := io.moved.ready & read.io.ready
        io.moved.valid := io.decoded.fire

        def counters(): Unit =
            val wait =
                parameter.stats.stat("operand_wait", Regions.Throughput, "cycles a payload waits for its own operands")
            wait.inc(io.decoded.valid & io.moved.ready & !read.io.ready)

        if parameter.flow.counters then counters()
