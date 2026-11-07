package nzea_lab.generators

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_lab.{LabGenerator, ParamValues}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The course's single-cycle RV32I: 32-bit only, no CSRs, no interrupts. */
case class SimpleCpuParameter(dataWidth: Int) extends Parameter:
    require(dataWidth == 32, s"the course's simple_cpu is RV32I, got dataWidth $dataWidth")

given upickle.default.ReadWriter[SimpleCpuParameter] = upickle.default.macroRW

class SimpleCpuLayers(parameter: SimpleCpuParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class SimpleCpuIO(parameter: SimpleCpuParameter) extends HWBundle(parameter):
    val clk = Flipped(Clock())
    val rst = Flipped(Bool())
    val PC = Aligned(UInt(parameter.dataWidth))
    val Instruction = Flipped(UInt(parameter.dataWidth))
    val Address = Aligned(UInt(parameter.dataWidth))
    val MemWrite = Aligned(Bool())
    val Write_data = Aligned(UInt(parameter.dataWidth))
    val Write_strb = Aligned(UInt(4))
    val Read_data = Flipped(UInt(parameter.dataWidth))
    val MemRead = Aligned(Bool())

class SimpleCpuProbe(parameter: SimpleCpuParameter) extends DVBundle[SimpleCpuParameter, SimpleCpuLayers](parameter)

/** The course's single-cycle RV32I.
  *
  * `alu`, `reg_file` and `shifter` are external modules: the CPU's dump names them but carries no bodies, and the
  * course's separately generated files supply those. `Instruction` is the asynchronous read of the shared memory at
  * `PC`, so the whole instruction executes in the cycle its address is on `PC`.
  *
  * The ALU also timeshares the address and jump-target arithmetic: loads/stores put `rs1 + imm` on `Address`, JAL/JALR
  * put their target there, and AUIPC its `pc + imm`. Branches keep the ALU on the comparison and add `pc + imm` with a
  * plain adder, so no second `alu` instance is needed.
  */
@generator
object SimpleCpuGen
    extends Generator[SimpleCpuParameter, SimpleCpuLayers, SimpleCpuIO, SimpleCpuProbe],
      LabGenerator[SimpleCpuParameter]:

    val name = "simple_cpu"

    val doc = "single-cycle RV32I (course interface)"

    def parameter(values: ParamValues): SimpleCpuParameter = SimpleCpuParameter(values.int("dataWidth"))

    override def moduleName(parameter: SimpleCpuParameter): String = "simple_cpu"

    override def submodules: Seq[String] = Seq("alu", "reg_file", "shifter")

    /** The course testbench compares `RF_wen`/`RF_waddr`/`RF_wdata` by name instead of watching the register file's own
      * ports, so the lowering must not inline them.
      */
    override def preserveNames: Boolean = true

    def architecture(parameter: SimpleCpuParameter) =
        val io = summon[Interface[SimpleCpuIO]]
        given ClockScope = ClockScope.posedge(io.clk)
        val width = parameter.dataWidth

        val inst: Node[Bits] = Node(io.Instruction.asBits)
        val opcode = Node(inst.bits(6, 0)).asUInt
        val rd = Node(inst.bits(11, 7)).asUInt
        val funct3 = Node(inst.bits(14, 12)).asUInt
        val rs1 = Node(inst.bits(19, 15)).asUInt
        val rs2 = Node(inst.bits(24, 20)).asUInt
        val funct7 = Node(inst.bits(31, 25)).asUInt
        val bit31 = inst.bit(31).asBits
        val bit7 = inst.bit(7).asBits
        val bit20 = inst.bit(20).asBits

        def sext(bits: Referable[Bits], from: Int): Node[UInt] =
            Node(fill(width - from, bits.bit(from - 1)) ## bits).asUInt

        val immI = sext(inst.bits(31, 20), 12)
        val immS = sext(inst.bits(31, 25) ## inst.bits(11, 7), 12)
        val immB = sext(bit31 ## bit7 ## inst.bits(30, 25) ## inst.bits(11, 8) ## 0.B(1), 13)
        val immU = Node(inst.bits(31, 12) ## 0.B(12)).asUInt
        val immJ = sext(bit31 ## inst.bits(19, 12) ## bit20 ## inst.bits(30, 21) ## 0.B(1), 21)

        val isR = opcode === 0x33.U(7)
        val isI = opcode === 0x13.U(7)
        val isLoad = opcode === 0x03.U(7)
        val isStore = opcode === 0x23.U(7)
        val isBranch = opcode === 0x63.U(7)
        val isJal = opcode === 0x6f.U(7)
        val isJalr = opcode === 0x67.U(7)
        val isLui = opcode === 0x37.U(7)
        val isAuipc = opcode === 0x17.U(7)

        val rAluOp = muxCase(
            0.U(3),
            Seq(
                ((funct3 === 0.U(3)) & (funct7 === 0x20.U(7)), 6.U(3)),
                (funct3 === 0.U(3), 2.U(3)),
                (funct3 === 7.U(3), 0.U(3)),
                (funct3 === 6.U(3), 1.U(3)),
                (funct3 === 4.U(3), 4.U(3)),
                (funct3 === 2.U(3), 7.U(3)),
                (funct3 === 3.U(3), 3.U(3))
            )
        )
        val iAluOp = muxCase(
            2.U(3),
            Seq(
                (funct3 === 7.U(3), 0.U(3)),
                (funct3 === 6.U(3), 1.U(3)),
                (funct3 === 4.U(3), 4.U(3)),
                (funct3 === 2.U(3), 7.U(3)),
                (funct3 === 3.U(3), 3.U(3))
            )
        )
        val branchAluOp = muxCase(
            6.U(3),
            Seq(
                (funct3 === 4.U(3), 7.U(3)),
                (funct3 === 5.U(3), 7.U(3)),
                (funct3 === 6.U(3), 3.U(3)),
                (funct3 === 7.U(3), 3.U(3))
            )
        )
        val aluOp = muxCase(
            0.U(3),
            Seq(
                (isR, rAluOp),
                (isI, iAluOp),
                (isLoad | isStore | isJal | isJalr | isAuipc, 2.U(3)),
                (isBranch, branchAluOp)
            )
        )

        val regWrite = isR | isI | isLoad | isJal | isJalr | isLui | isAuipc
        val aluASel = isJal | isAuipc
        val aluBSel = isI | isLoad | isStore | isJal | isJalr | isAuipc | isLui
        val wbSel = muxCase(
            0.U(2),
            Seq(
                (isLoad, 1.U(2)),
                (isJal | isJalr, 2.U(2)),
                (isLui, 3.U(2))
            )
        )
        val shiftSel = (isR | isI) & ((funct3 === 1.U(3)) | (funct3 === 5.U(3)))
        val shiftOp = muxCase(
            0.U(2),
            Seq(
                ((isR | isI) & (funct3 === 5.U(3)) & (funct7 === 0x20.U(7)), 3.U(2)),
                ((isR | isI) & (funct3 === 5.U(3)), 2.U(2))
            )
        )
        val immForAlu = muxCase(
            immI,
            Seq(
                (isStore, immS),
                (isBranch, immB),
                (isJal, immJ),
                (isLui | isAuipc, immU)
            )
        )

        val rf = RegFileBlackBox.instantiate(RegFileParameter(width, 5))
        rf.io.clk := io.clk
        rf.io.raddr1 := rs1
        rf.io.raddr2 := rs2

        val alu = AluBlackBox.instantiate(AluParameter(width))
        val aluA = aluASel ? (io.PC, rf.io.rdata1)
        val aluB = aluBSel ? (immForAlu, rf.io.rdata2)
        alu.io.A := aluA
        alu.io.B := aluB
        alu.io.ALUop := aluOp
        val aluResult = alu.io.Result
        val aluZero = alu.io.Zero
        val aluLess = Node(aluResult.asBits).bit(0)

        val shifter = ShifterBlackBox.instantiate(ShifterParameter(width))
        val shiftAmount = muxCase(
            Node(rf.io.rdata2.asBits.bits(4, 0)).asUInt,
            Seq((isI, Node(immI.asBits.bits(4, 0)).asUInt))
        )
        shifter.io.A := rf.io.rdata1
        shifter.io.B := shiftAmount
        shifter.io.Shiftop := shiftOp
        val shiftResult = shifter.io.Result

        val pc = Reg(UInt(width))
        val pcPlus4 = Node((pc + 4.U(width)).asBits.bits(width - 1, 0)).asUInt
        val branchTarget = Node((pc + immB).asBits.bits(width - 1, 0)).asUInt
        val jalrTarget = Node((aluResult.asBits & ~(1.U(width)).asBits).asUInt)
        val jumpTarget = muxCase(
            branchTarget,
            Seq(
                (isJal, aluResult),
                (isJalr, jalrTarget)
            )
        )
        val branchCond = muxCase(
            false.B,
            Seq(
                (isBranch & (funct3 === 0.U(3)), aluZero),
                (isBranch & (funct3 === 1.U(3)), !aluZero),
                (isBranch & (funct3 === 4.U(3)), aluLess),
                (isBranch & (funct3 === 5.U(3)), !aluLess),
                (isBranch & (funct3 === 6.U(3)), aluLess),
                (isBranch & (funct3 === 7.U(3)), !aluLess)
            )
        )
        val nextPc = muxCase(
            pcPlus4,
            Seq(
                (isBranch & branchCond, branchTarget),
                (isJal | isJalr, jumpTarget)
            )
        )

        val byteOffset = Node(aluResult.asBits.bits(1, 0)).asUInt
        val shifted = Node((io.Read_data >> (byteOffset << 3)).asBits.pad(width)).asUInt
        val loadByte = sext(shifted.asBits.bits(7, 0), 8)
        val loadByteU = Node(0.B(24) ## shifted.asBits.bits(7, 0)).asUInt
        val loadHalf = sext(shifted.asBits.bits(15, 0), 16)
        val loadHalfU = Node(0.B(16) ## shifted.asBits.bits(15, 0)).asUInt
        val loadValue = muxCase(
            shifted,
            Seq(
                (funct3 === 0.U(3), loadByte),
                (funct3 === 1.U(3), loadHalf),
                (funct3 === 4.U(3), loadByteU),
                (funct3 === 5.U(3), loadHalfU)
            )
        )

        val storeShifted = Node((rf.io.rdata2 << (byteOffset << 3)).asBits.bits(width - 1, 0)).asUInt
        val writeData = muxCase(
            rf.io.rdata2,
            Seq(
                (funct3 === 0.U(3), storeShifted),
                (funct3 === 1.U(3), storeShifted)
            )
        )
        val strbBase = muxCase(
            15.U(4),
            Seq(
                (funct3 === 0.U(3), 1.U(4)),
                (funct3 === 1.U(3), 3.U(4))
            )
        )
        val writeStrb = Node((strbBase << byteOffset).asBits.bits(3, 0)).asUInt

        val wbFromMux = muxCase(
            aluResult,
            Seq(
                (wbSel === 1.U(2), loadValue),
                (wbSel === 2.U(2), pcPlus4),
                (wbSel === 3.U(2), immU)
            )
        )
        val wbValue = shiftSel ? (shiftResult, wbFromMux)

        val RF_wen = Wire(Bool())
        val RF_waddr = Wire(UInt(5))
        val RF_wdata = Wire(UInt(width))
        RF_wen := regWrite
        RF_waddr := rd
        RF_wdata := wbValue
        rf.io.wen := RF_wen
        rf.io.waddr := RF_waddr
        rf.io.wdata := RF_wdata

        when(io.rst) {
            pc := 0.U(width)
        }.otherwise {
            pc := nextPc
        }

        io.PC := pc
        io.Address := aluResult
        io.MemWrite := isStore
        io.Write_data := writeData
        io.Write_strb := writeStrb
        io.MemRead := isLoad
