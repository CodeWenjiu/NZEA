package nzea_core.frontend

import chisel3._
import chisel3.util.BitPat
import chisel3.util.Decoupled
import chisel3.util.experimental.decode._

// -------- DecodePattern: instruction patterns (32-bit) --------

/** RISC-V instruction pattern: machine code (bitPat) + metadata for decode. */
case class RVInst(name: String, bitPatStr: String, instType: String) extends DecodePattern {
  def bitPat: BitPat = BitPat(bitPatStr)
}

/** RV32I instruction set. Bit layout: [31:25] funct7, [24:20] rs2, [19:15] rs1, [14:12] funct3, [11:7] rd, [6:0] opcode. */
object RiscvInsts {
  def n(n: Int) = "?" * n

  // U-type: LUI=0110111, AUIPC=0010111
  val LUI   = RVInst("LUI",   "b" + n(25) + "0110111", "U")
  val AUIPC = RVInst("AUIPC", "b" + n(25) + "0010111", "U")

  // J-type: JAL=1101111
  val JAL   = RVInst("JAL",   "b" + n(25) + "1101111", "J")

  // I-type (JALR=1100111, load=0000011, op-imm=0010011, system=1110011)
  val JALR  = RVInst("JALR",  "b" + n(12) + n(5) + "000" + n(5) + "1100111", "I")
  val LB    = RVInst("LB",    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0000011", "I")
  val LH    = RVInst("LH",    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0000011", "I")
  val LW    = RVInst("LW",    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0000011", "I")
  val LBU   = RVInst("LBU",   "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0000011", "I")
  val LHU   = RVInst("LHU",   "b" + n(7) + n(5) + n(5) + "101" + n(5) + "0000011", "I")
  val ADDI  = RVInst("ADDI",  "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0010011", "I")
  val SLTI  = RVInst("SLTI",  "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0010011", "I")
  val SLTIU = RVInst("SLTIU", "b" + n(7) + n(5) + n(5) + "011" + n(5) + "0010011", "I")
  val XORI  = RVInst("XORI",  "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0010011", "I")
  val ORI   = RVInst("ORI",   "b" + n(7) + n(5) + n(5) + "110" + n(5) + "0010011", "I")
  val ANDI  = RVInst("ANDI",  "b" + n(7) + n(5) + n(5) + "111" + n(5) + "0010011", "I")
  val SLLI  = RVInst("SLLI",  "b0000000" + n(5) + n(5) + "001" + n(5) + "0010011", "I")
  val SRLI  = RVInst("SRLI",  "b0000000" + n(5) + n(5) + "101" + n(5) + "0010011", "I")
  val SRAI  = RVInst("SRAI",  "b0100000" + n(5) + n(5) + "101" + n(5) + "0010011", "I")
  val ECALL = RVInst("ECALL", "b00000000000000000000000001110011", "I")
  val EBREAK= RVInst("EBREAK","b00000000000100000000000001110011", "I")
  val CSRRW  = RVInst("CSRRW",  "b" + n(12) + n(5) + "001" + n(5) + "1110011", "I")
  val CSRRS  = RVInst("CSRRS",  "b" + n(12) + n(5) + "010" + n(5) + "1110011", "I")
  val CSRRC  = RVInst("CSRRC",  "b" + n(12) + n(5) + "011" + n(5) + "1110011", "I")
  val CSRRWI = RVInst("CSRRWI", "b" + n(12) + n(5) + "101" + n(5) + "1110011", "I")
  val CSRRSI = RVInst("CSRRSI", "b" + n(12) + n(5) + "110" + n(5) + "1110011", "I")
  val CSRRCI = RVInst("CSRRCI", "b" + n(12) + n(5) + "111" + n(5) + "1110011", "I")

  // S-type: store=0100011
  val SB = RVInst("SB", "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0100011", "S")
  val SH = RVInst("SH", "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0100011", "S")
  val SW = RVInst("SW", "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0100011", "S")

  // B-type: branch=1100011
  val BEQ  = RVInst("BEQ",  "b" + n(7) + n(5) + n(5) + "000" + n(5) + "1100011", "B")
  val BNE  = RVInst("BNE",  "b" + n(7) + n(5) + n(5) + "001" + n(5) + "1100011", "B")
  val BLT  = RVInst("BLT",  "b" + n(7) + n(5) + n(5) + "100" + n(5) + "1100011", "B")
  val BGE  = RVInst("BGE",  "b" + n(7) + n(5) + n(5) + "101" + n(5) + "1100011", "B")
  val BLTU = RVInst("BLTU", "b" + n(7) + n(5) + n(5) + "110" + n(5) + "1100011", "B")
  val BGEU = RVInst("BGEU", "b" + n(7) + n(5) + n(5) + "111" + n(5) + "1100011", "B")

  // R-type: op=0110011
  val ADD  = RVInst("ADD",  "b0000000" + n(5) + n(5) + "000" + n(5) + "0110011", "R")
  val SUB  = RVInst("SUB",  "b0100000" + n(5) + n(5) + "000" + n(5) + "0110011", "R")
  val SLL  = RVInst("SLL",  "b0000000" + n(5) + n(5) + "001" + n(5) + "0110011", "R")
  val SLT  = RVInst("SLT",  "b0000000" + n(5) + n(5) + "010" + n(5) + "0110011", "R")
  val SLTU = RVInst("SLTU", "b0000000" + n(5) + n(5) + "011" + n(5) + "0110011", "R")
  val XOR  = RVInst("XOR",  "b0000000" + n(5) + n(5) + "100" + n(5) + "0110011", "R")
  val SRL  = RVInst("SRL",  "b0000000" + n(5) + n(5) + "101" + n(5) + "0110011", "R")
  val SRA  = RVInst("SRA",  "b0100000" + n(5) + n(5) + "101" + n(5) + "0110011", "R")
  val OR   = RVInst("OR",   "b0000000" + n(5) + n(5) + "110" + n(5) + "0110011", "R")
  val AND  = RVInst("AND",  "b0000000" + n(5) + n(5) + "111" + n(5) + "0110011", "R")

  val FENCE = RVInst("FENCE", "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0001111", "I")

  val all: Seq[RVInst] = Seq(
    LUI, AUIPC, JAL, JALR,
    BEQ, BNE, BLT, BGE, BLTU, BGEU,
    LB, LH, LW, LBU, LHU, SB, SH, SW,
    ADDI, SLTI, SLTIU, XORI, ORI, ANDI, SLLI, SRLI, SRAI,
    ADD, SUB, SLL, SLT, SLTU, XOR, SRL, SRA, OR, AND,
    FENCE, ECALL, EBREAK,
    CSRRW, CSRRS, CSRRC, CSRRWI, CSRRSI, CSRRCI
  )
}

// -------- ChiselEnums for decode output (maintainable) --------

object ImmType extends chisel3.ChiselEnum {
  val I, S, B, U, J = Value
}

// -------- DecodeField helpers --------

trait DecodeAPI {
  def bitPatFor[D <: chisel3.Data](v: D): BitPat = BitPat(v.litValue.U(v.getWidth.W))
}

// -------- DecodeFields --------

/** Outputs UInt(width) so DecodeBundle does not cast to ImmType (avoids W001); use ImmType.safe() when reading. */
object ImmTypeField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "imm_type"
  def chiselType = UInt(ImmType.getWidth.W)
  def genTable(inst: RVInst): BitPat = inst.instType match {
    case "I" => bitPatFor(ImmType.I)
    case "S" => bitPatFor(ImmType.S)
    case "B" => bitPatFor(ImmType.B)
    case "U" => bitPatFor(ImmType.U)
    case "J" => bitPatFor(ImmType.J)
    case _   => BitPat.dontCare(ImmType.getWidth)
  }
}

// -------- DecodedInst bundle & IDU module --------

class DecodedInst extends Bundle {
  val inst     = UInt(32.W)
  val immType  = ImmType()
}

class IDU extends Module {
  val io = IO(new Bundle {
    val inst    = Flipped(Decoupled(UInt(32.W)))
    val decoded = Decoupled(new DecodedInst)
  })

  val allInsts   = RiscvInsts.all
  val allFields  = Seq(ImmTypeField)
  val decodeTable = new DecodeTable(allInsts, allFields)
  val decodedBundle = decodeTable.decode(io.inst.bits)
  val (immType, _)  = ImmType.safe(decodedBundle(ImmTypeField))

  io.decoded.valid := io.inst.valid
  io.decoded.bits.inst     := io.inst.bits
  io.decoded.bits.immType  := immType
  io.inst.ready := io.decoded.ready
}
