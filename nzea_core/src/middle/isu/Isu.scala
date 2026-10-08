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
import nzea_core.middle.rf.{ArfReadPort, ArfWrite, CsrReadPort, CsrWrite}
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

    /** The forward column's length — the in-flight capacity's shape, shared with the network that broadcasts it. */
    val offerCount: Int = ForwardParameter.offerCount(lsqDepth)

given upickle.default.ReadWriter[IsuParameter] = upickle.default.macroRW

class IsuLayers(parameter: IsuParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class IsuIO(parameter: IsuParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The ID/IS register, this unit's upstream end: the decoded payload, and the pace the read gives it. */
    val decoded = Flipped(Decoupled(new IduOut(parameter.config)))

    /** The IS/EX register's write side, this unit's downstream end: the uop the read produced, and the register's room
      * back — the read paces itself by it.
      */
    val moved = Aligned(Decoupled(new ExecSlot(parameter.config)))

    /** The register file's read side, from the reader's view: the indexes go out, the values come back. */
    val arf_rs1 = Flipped(new ArfReadPort(width))
    val arf_rs2 = Flipped(new ArfReadPort(width))

    /** The CSR file's read side, passed through to the read unit: the reader is the read's, the file is the middle's.
      */
    val csr_read = Flipped(new CsrReadPort(width))

    /** The forward network's face, passed through to the read unit: the columns it resolves its reads against and the
      * level's verdict for the retiring writes ([[nzea_core.back.ForwardBus]]).
      */
    val forward = Flipped(new ForwardBus(width, parameter.offerCount))

    /** The retiring instruction's writes — the middle's own producers, older than everything in the columns — the
      * register file's and the CSR file's.
      */
    val arf_commit = Flipped(ValidIO(new ArfWrite(width)))
    val csr_commit = Flipped(ValidIO(new CsrWrite(width)))

class IsuProbe(parameter: IsuParameter) extends DVBundle[IsuParameter, IsuLayers](parameter)

/** The issue stage: the register read and the assembly, combinational, between the middle's two elastic registers.
  *
  * The ID/IS and IS/EX registers are not this unit's — they are declared one level up, beside every other stage
  * boundary, which is the rule the front-end follows around its decode — and this unit is what sits between them: it
  * reads the ID/IS register and fills the IS/EX register's write side. The read side of that register belongs to the
  * execute stage, which is what presents the slot to the units. The operands come already placed from
  * [[nzea_core.middle.isu.OperandRead]], together with the read's feasibility and the CSR it settled; this unit packs
  * them into the `moved` payload, carrying the fetch's predicted next pc through untouched.
  *
  * The stage's own counter (`operand_wait`) is declared here, where the signals it watches are; the execute slot's
  * occupancy is the register's own and is declared beside it.
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

        /** The read: the payload's indexes and CSR address out to the files and the network, the answers back in, and
          * the placement of the three operand slots, with the pace this read can move at.
          */
        def readUnit(): Unit =
            read.io.clock := io.clock
            read.io.resetN := io.resetN
            read.io.decoded :<= io.decoded.bits
            read.io.rs1_data := io.arf_rs1.data
            read.io.rs2_data := io.arf_rs2.data
            io.arf_rs1.index := read.io.rs1_index
            io.arf_rs2.index := read.io.rs2_index
            // The read unit's own port is the connect's sink side; the boundary port is the source — the reader drives
            // the address out, the file's answer comes back in.
            read.io.csr_read :<>= io.csr_read

            // The forward face and the retiring writes: the read resolves its own indexes against the network's
            // columns, and the middle's own registers against the level's verdict.
            read.io.forward :<= io.forward
            read.io.arf_commit :<= io.arf_commit
            read.io.csr_commit :<= io.csr_commit

        /** The uop that moves: the read's operands and CSR, and the payload's held fields with the destination riding
          * in it — the IS/EX register's write side.
          */
        def moveUop(): Unit =
            val dec = io.decoded.bits
            io.moved.bits.pc := dec.pc
            io.moved.bits.next_pc := dec.next_pc
            io.moved.bits.fall_through := dec.fall_through
            io.moved.bits.pht_count := dec.pht_count
            io.moved.bits.imm := dec.imm
            io.moved.bits.src_a := read.io.src_a
            io.moved.bits.src_b := read.io.src_b
            io.moved.bits.src_c := read.io.src_c
            io.moved.bits.backend := dec.backend
            io.moved.bits.op := dec.op
            io.moved.bits.rd_index := dec.rd_index
            io.moved.bits.csr_type := read.io.csr_type
            io.moved.bits.csr_rdata := read.io.csr_rdata

        readUnit()
        moveUop()

        // The handover: ID/IS is consumed when the read has placed the payload's operands — the read owns that test,
        // including the in-flight writers it had to wait for — and the register below reports room: its own accept side
        // already carries its flush rule, so a handover the flush refuses never reaches here. The uop moves in that
        // cycle, so the register presents it the next one.
        io.decoded.ready := io.moved.ready & read.io.ready
        io.moved.valid := io.decoded.fire

        /** The counter the simulator reads back: the payload's pace, declared here where the signals it watches are.
          * The execute slot's occupancy is the register's own and lives beside it ([[nzea_core.middle.Middle]]).
          */
        def counters(): Unit =
            val wait =
                parameter.stats.stat("operand_wait", Regions.Throughput, "cycles a payload waits for its own operands")
            wait.inc(io.decoded.valid & io.moved.ready & !read.io.ready)

        if parameter.flow.counters then counters()
