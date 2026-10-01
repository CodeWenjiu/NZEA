package nzea_core.middle

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.CsrType
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One CSR write, applied at commit: which CSR the instruction addressed, and the value to store. */
class CsrWrite(width: Int) extends Bundle:
    val csr_type = Aligned(UInt(CsrType.width))
    val data = Aligned(UInt(width))

case class CsrFileParameter(width: Int) extends Parameter

given upickle.default.ReadWriter[CsrFileParameter] = upickle.default.macroRW

class CsrFileLayers(parameter: CsrFileParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class CsrFileIO(parameter: CsrFileParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val write = Flipped(ValidIO(new CsrWrite(parameter.width)))
    val read_addr = Flipped(UInt(12))
    val read_data = Aligned(UInt(parameter.width))

class CsrFileProbe(parameter: CsrFileParameter) extends DVBundle[CsrFileParameter, CsrFileLayers](parameter)

/** Machine-mode CSRs: written at commit, read combinationally by address while the instruction is in execute.
  *
  * A read in the cycle of a write sees the write: with a bypass link the instruction in execute can be reading a CSR
  * the instruction committing in that same cycle is writing, and the register file's own value is one instruction
  * stale.
  *
  * `mstatus` resets to 0x1800 (matching the legacy core: MPP = M), the rest to zero. An address that maps to no
  * implemented CSR reads as zero, and a write to one is dropped — the baseline has no illegal-CSR trap.
  */
@generator
object CsrFile extends Generator[CsrFileParameter, CsrFileLayers, CsrFileIO, CsrFileProbe]:
    override def moduleName(parameter: CsrFileParameter): String = "CsrFile"

    def architecture(parameter: CsrFileParameter) =
        val io = summon[Interface[CsrFileIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.width
        val mstatus = RegInit(0x1800.U(width))
        val mtvec = RegInit(0.U(width))
        val mepc = RegInit(0.U(width))
        val mcause = RegInit(0.U(width))
        val mscratch = RegInit(0.U(width))

        when(io.write.valid & (io.write.bits.csr_type =/= CsrType.None.lit.U(CsrType.width))) {
            when(io.write.bits.csr_type === CsrType.Mstatus.lit.U(CsrType.width)) { mstatus := io.write.bits.data }
            when(io.write.bits.csr_type === CsrType.Mtvec.lit.U(CsrType.width)) { mtvec := io.write.bits.data }
            when(io.write.bits.csr_type === CsrType.Mepc.lit.U(CsrType.width)) { mepc := io.write.bits.data }
            when(io.write.bits.csr_type === CsrType.Mcause.lit.U(CsrType.width)) { mcause := io.write.bits.data }
            when(io.write.bits.csr_type === CsrType.Mscratch.lit.U(CsrType.width)) { mscratch := io.write.bits.data }
        }

        val addressed = CsrType.fromAddr(io.read_addr)
        val writeThrough = io.write.valid & (io.write.bits.csr_type =/= CsrType.None.lit.U(CsrType.width)) &
            (io.write.bits.csr_type === addressed)
        val stored: Referable[UInt] = muxCase(
            0.U(width),
            Seq(
                (addressed === CsrType.Mstatus.lit.U(CsrType.width), mstatus),
                (addressed === CsrType.Mtvec.lit.U(CsrType.width), mtvec),
                (addressed === CsrType.Mepc.lit.U(CsrType.width), mepc),
                (addressed === CsrType.Mcause.lit.U(CsrType.width), mcause),
                (addressed === CsrType.Mscratch.lit.U(CsrType.width), mscratch)
            )
        )
        io.read_data := writeThrough ? (io.write.bits.data, stored)
