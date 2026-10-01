package nzea_core.middle

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, Valid, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.{BypassLevel, CoreConfig}
import nzea_core.back.{BypassBus, IssueSlot, IssueUop, PathResult}
import nzea_core.front.idu.IduOut
import nzea_core.isa.Isa
import nzea_core.middle.isu.{IssueStage, IssueStageParameter}
import nzea_core.middle.rf.{Arf, ArfParameter, CsrFile, CsrFileParameter}
import nzea_core.middle.wbu.{Wbu, WbuParameter}
import nzea_rtl.{RtlFlow, StatScope}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class MiddleParameter(
    isa: Isa,
    defaultPc: Long,
    flow: RtlFlow,
    bypass: BypassLevel,
    stats: StatScope
) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[MiddleParameter] = upickle.default.macroRW

class MiddleLayers(parameter: MiddleParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class MiddleIO(parameter: MiddleParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val decoded = Flipped(Decoupled(new IduOut(parameter.config)))
    val redirect = Aligned(Valid(UInt(width)))
    val issue = Aligned(new IssueSlot(new IssueUop(parameter.config)))
    val complete = Flipped(ValidIO(new PathResult(width)))
    val bypass = Aligned(new BypassBus(width))
    val commit = Aligned(ValidIO(new CommitMsg(width)))
    val flush = Aligned(Bool())

class MiddleProbe(parameter: MiddleParameter) extends DVBundle[MiddleParameter, MiddleLayers](parameter)

/** In-order middle: its two stages — [[nzea_core.middle.isu.IssueStage]] (the read and the assembly between the
  * middle's two elastic registers) and [[nzea_core.middle.wbu.Wbu]] (the retire register, the commit and the flush) —
  * and the two files they share.
  *
  * The register and CSR files live here, at the one place both stages face them: their read sides belong to the issue
  * stage, their write sides to the write-back stage. The module binds the stages to each other — the retire facts back
  * to the read, the slot's destination forward, the flush across — wires the files' two sides, and presents the
  * middle's boundary to the core: the decoded stream in, the issue slot, the completion, the bypass bus and the
  * commit/flush/redirect out. The two stages share the `middle` scope, so the counters they declare land where they
  * always have.
  */
@generator
object Middle extends Generator[MiddleParameter, MiddleLayers, MiddleIO, MiddleProbe]:
    override def moduleName(parameter: MiddleParameter): String = "Middle"

    /** The block name the middle's statistics are attributed to; the parent's scope takes it as a segment. */
    val Source: String = "middle"

    def architecture(parameter: MiddleParameter) =
        val io = summon[Interface[MiddleIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val c = parameter.config
        val issueStage =
            IssueStage.instantiate(
                IssueStageParameter(c.isa, c.defaultPc, parameter.flow, parameter.bypass, parameter.stats)
            )
        val wbu = Wbu.instantiate(WbuParameter(c.isa, c.defaultPc, parameter.flow, parameter.stats))
        val csr = CsrFile.instantiate(CsrFileParameter(c.width))
        val arf = Arf.instantiate(ArfParameter(c.width))

        issueStage.io.clock := io.clock
        issueStage.io.resetN := io.resetN
        wbu.io.clock := io.clock
        wbu.io.resetN := io.resetN
        csr.io.clock := io.clock
        csr.io.resetN := io.resetN
        arf.io.clock := io.clock
        arf.io.resetN := io.resetN

        // The decoded stream in, and the issued uop out.
        issueStage.io.decoded.valid := io.decoded.valid
        io.decoded.ready := issueStage.io.decoded.ready
        issueStage.io.decoded.bits :<= io.decoded.bits
        io.issue :<= issueStage.io.issue

        // The completion: the write-back stage latches it, the issue stage paces on it.
        wbu.io.complete.valid := io.complete.valid
        wbu.io.complete.bits :<= io.complete.bits
        issueStage.io.complete := io.complete.valid

        // Between the stages: the slot's destination goes forward, the retire facts and the flush come back.
        wbu.io.rd_index := issueStage.io.slot_rd_index
        issueStage.io.wb_valid := wbu.io.wb_valid
        issueStage.io.wb_rd := wbu.io.wb_rd
        issueStage.io.flush := wbu.io.flush

        // The files both stages face: the read side is the issue stage's, the write side is the retire register's.
        csr.io.read_addr := issueStage.io.slot_csr_addr
        issueStage.io.csr_rdata := csr.io.read_data
        arf.io.rs1.index := issueStage.io.arf_rs1.index
        issueStage.io.arf_rs1.data := arf.io.rs1.data
        arf.io.rs2.index := issueStage.io.arf_rs2.index
        issueStage.io.arf_rs2.data := arf.io.rs2.data
        arf.io.write :<= wbu.io.arf_write
        csr.io.write :<= wbu.io.csr_write

        // The bypass bus, split at its ends: the read's indexes and answers on the issue side, the retire write on the
        // write-back side.
        io.bypass.rs1_index := issueStage.io.bypass_rs1_index
        io.bypass.rs2_index := issueStage.io.bypass_rs2_index
        issueStage.io.bypass_rs1 :<= io.bypass.rs1
        issueStage.io.bypass_rs2 :<= io.bypass.rs2
        io.bypass.commit :<= wbu.io.bypass_commit

        // The boundary's outputs.
        io.flush := wbu.io.flush
        io.redirect :<= wbu.io.redirect
        io.commit :<= wbu.io.commit
