package nzea_core.middle.isu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.front.idu.IduOut
import nzea_core.isa.Isa
import nzea_core.middle.ExecSlot
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class AssembleParameter(isa: Isa, defaultPc: Long) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[AssembleParameter] = upickle.default.macroRW

class AssembleLayers(parameter: AssembleParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class AssembleIO(parameter: AssembleParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** From the middle's ID/IS register: the decoded payload, in the cycle the register presents it. */
    val decoded = Flipped(new IduOut(parameter.config))

    /** The read's pace back to that register: set when this cycle's read can place the payload's operands. */
    val decoded_ready = Aligned(Bool())

    /** From the read: the operands it placed, and whether every one of them is settled. */
    val src_a = Flipped(UInt(width))
    val src_b = Flipped(UInt(width))
    val src_c = Flipped(UInt(width))
    val operands_ready = Flipped(Bool())

    /** From the IS/EX register: its room for a new uop — the register's own accept side, its flush rule included. */
    val moved_ready = Flipped(Bool())

    /** To the IS/EX register: the uop the read produced, with the destination and CSR address riding in it. */
    val moved = Aligned(new ExecSlot(parameter.config))

class AssembleProbe(parameter: AssembleParameter) extends DVBundle[AssembleParameter, AssembleLayers](parameter)

/** The assembly: the uop the read produces into the middle's IS/EX register.
  *
  * Combinational: the ID/IS and IS/EX registers are not this unit's — the read side of the latter belongs to the
  * execute stage — and this unit is what sits between the read and the register's write side, the same rule the
  * front-end follows around [[nzea_core.front.idu.Idu]]. The operands arrive already placed from
  * [[nzea_core.middle.isu.OperandRead]], together with the read's feasibility; this unit assembles the `moved` payload,
  * carrying the fetch's predicted next pc through untouched, and paces the ID/IS register: the payload is consumed when
  * its operands are placed and the register below has room.
  */
@generator
object Assemble extends Generator[AssembleParameter, AssembleLayers, AssembleIO, AssembleProbe]:
    override def moduleName(parameter: AssembleParameter): String = "Assemble"

    def architecture(parameter: AssembleParameter) =
        val io = summon[Interface[AssembleIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        /** ID/IS is consumed when the read has placed the payload's operands — the read owns that test, including the
          * in-flight writers it had to wait for — and the register below reports room: its own accept side already
          * carries its flush rule, so a handover the flush refuses never reaches here.
          */
        def canPlaceOperands(): Referable[Bool] =
            io.moved_ready & io.operands_ready

        /** What moves into IS/EX: the read's operands, and the payload's held fields with the destination and the CSR
          * address riding in it.
          */
        def moveUop(): Unit =
            val dec = io.decoded
            io.moved.pc := dec.pc
            io.moved.next_pc := dec.next_pc
            io.moved.fall_through := dec.fall_through
            io.moved.pht_count := dec.pht_count
            io.moved.imm := dec.imm
            io.moved.src_a := io.src_a
            io.moved.src_b := io.src_b
            io.moved.src_c := io.src_c
            io.moved.backend := dec.backend
            io.moved.op := dec.op
            io.moved.rd_index := dec.rd_index
            io.moved.csr_addr := dec.csr_addr

        io.decoded_ready := canPlaceOperands()
        moveUop()
