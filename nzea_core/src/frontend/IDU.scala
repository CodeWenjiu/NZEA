package nzea_core.frontend

import chisel3._
import chisel3.util.BitPat
import chisel3.util.Decoupled
import chisel3.util.{Cat, Fill, Mux1H}
import chisel3.util.experimental.decode.{DecodeField, DecodePattern, TruthTable, QMCMinimizer, decoder}

// -------- DecodePattern: instruction patterns (32-bit) --------

/** One-hot encoding for Mux1H; better timing than binary. */
object ImmType extends chisel3.ChiselEnum {
  val I = Value((1 << 0).U)
  val S = Value((1 << 1).U)
  val B = Value((1 << 2).U)
  val U = Value((1 << 3).U)
  val J = Value((1 << 4).U)
}

/** RISC-V instruction pattern: machine code (bitPat) + imm type for decode. None = dontCare for QMC. */
case class RVInst(name: String, bitPatStr: String, immType: Option[ImmType.Type]) extends DecodePattern {
  def bitPat: BitPat = BitPat(bitPatStr)
}

/** RV32I instruction set. Bit layout: [31:25] funct7, [24:20] rs2, [19:15] rs1, [14:12] funct3, [11:7] rd, [6:0] opcode. */
object RiscvInsts {
  def n(n: Int) = "?" * n

  // U-type: LUI=0110111, AUIPC=0010111
  val LUI   = RVInst("LUI",   "b" + n(25) + "0110111", Some(ImmType.U))
  val AUIPC = RVInst("AUIPC", "b" + n(25) + "0010111", Some(ImmType.U))

  // J-type: JAL=1101111
  val JAL   = RVInst("JAL",   "b" + n(25) + "1101111", Some(ImmType.J))

  // I-type (JALR=1100111, load=0000011, op-imm=0010011, system=1110011)
  val JALR  = RVInst("JALR",  "b" + n(12) + n(5) + "000" + n(5) + "1100111", Some(ImmType.I))
  val LB    = RVInst("LB",    "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0000011", Some(ImmType.I))
  val LH    = RVInst("LH",    "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0000011", Some(ImmType.I))
  val LW    = RVInst("LW",    "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0000011", Some(ImmType.I))
  val LBU   = RVInst("LBU",   "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0000011", Some(ImmType.I))
  val LHU   = RVInst("LHU",   "b" + n(7) + n(5) + n(5) + "101" + n(5) + "0000011", Some(ImmType.I))
  val ADDI  = RVInst("ADDI",  "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0010011", Some(ImmType.I))
  val SLTI  = RVInst("SLTI",  "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0010011", Some(ImmType.I))
  val SLTIU = RVInst("SLTIU", "b" + n(7) + n(5) + n(5) + "011" + n(5) + "0010011", Some(ImmType.I))
  val XORI  = RVInst("XORI",  "b" + n(7) + n(5) + n(5) + "100" + n(5) + "0010011", Some(ImmType.I))
  val ORI   = RVInst("ORI",   "b" + n(7) + n(5) + n(5) + "110" + n(5) + "0010011", Some(ImmType.I))
  val ANDI  = RVInst("ANDI",  "b" + n(7) + n(5) + n(5) + "111" + n(5) + "0010011", Some(ImmType.I))
  val SLLI  = RVInst("SLLI",  "b0000000" + n(5) + n(5) + "001" + n(5) + "0010011", Some(ImmType.I))
  val SRLI  = RVInst("SRLI",  "b0000000" + n(5) + n(5) + "101" + n(5) + "0010011", Some(ImmType.I))
  val SRAI  = RVInst("SRAI",  "b0100000" + n(5) + n(5) + "101" + n(5) + "0010011", Some(ImmType.I))
  val ECALL = RVInst("ECALL", "b00000000000000000000000001110011", Some(ImmType.I))
  val EBREAK= RVInst("EBREAK","b00000000000100000000000001110011", Some(ImmType.I))
  val CSRRW  = RVInst("CSRRW",  "b" + n(12) + n(5) + "001" + n(5) + "1110011", Some(ImmType.I))
  val CSRRS  = RVInst("CSRRS",  "b" + n(12) + n(5) + "010" + n(5) + "1110011", Some(ImmType.I))
  val CSRRC  = RVInst("CSRRC",  "b" + n(12) + n(5) + "011" + n(5) + "1110011", Some(ImmType.I))
  val CSRRWI = RVInst("CSRRWI", "b" + n(12) + n(5) + "101" + n(5) + "1110011", Some(ImmType.I))
  val CSRRSI = RVInst("CSRRSI", "b" + n(12) + n(5) + "110" + n(5) + "1110011", Some(ImmType.I))
  val CSRRCI = RVInst("CSRRCI", "b" + n(12) + n(5) + "111" + n(5) + "1110011", Some(ImmType.I))

  // S-type: store=0100011
  val SB = RVInst("SB", "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0100011", Some(ImmType.S))
  val SH = RVInst("SH", "b" + n(7) + n(5) + n(5) + "001" + n(5) + "0100011", Some(ImmType.S))
  val SW = RVInst("SW", "b" + n(7) + n(5) + n(5) + "010" + n(5) + "0100011", Some(ImmType.S))

  // B-type: branch=1100011
  val BEQ  = RVInst("BEQ",  "b" + n(7) + n(5) + n(5) + "000" + n(5) + "1100011", Some(ImmType.B))
  val BNE  = RVInst("BNE",  "b" + n(7) + n(5) + n(5) + "001" + n(5) + "1100011", Some(ImmType.B))
  val BLT  = RVInst("BLT",  "b" + n(7) + n(5) + n(5) + "100" + n(5) + "1100011", Some(ImmType.B))
  val BGE  = RVInst("BGE",  "b" + n(7) + n(5) + n(5) + "101" + n(5) + "1100011", Some(ImmType.B))
  val BLTU = RVInst("BLTU", "b" + n(7) + n(5) + n(5) + "110" + n(5) + "1100011", Some(ImmType.B))
  val BGEU = RVInst("BGEU", "b" + n(7) + n(5) + n(5) + "111" + n(5) + "1100011", Some(ImmType.B))

  // R-type: no imm, dontCare for QMC
  val ADD  = RVInst("ADD",  "b0000000" + n(5) + n(5) + "000" + n(5) + "0110011", None)
  val SUB  = RVInst("SUB",  "b0100000" + n(5) + n(5) + "000" + n(5) + "0110011", None)
  val SLL  = RVInst("SLL",  "b0000000" + n(5) + n(5) + "001" + n(5) + "0110011", None)
  val SLT  = RVInst("SLT",  "b0000000" + n(5) + n(5) + "010" + n(5) + "0110011", None)
  val SLTU = RVInst("SLTU", "b0000000" + n(5) + n(5) + "011" + n(5) + "0110011", None)
  val XOR  = RVInst("XOR",  "b0000000" + n(5) + n(5) + "100" + n(5) + "0110011", None)
  val SRL  = RVInst("SRL",  "b0000000" + n(5) + n(5) + "101" + n(5) + "0110011", None)
  val SRA  = RVInst("SRA",  "b0100000" + n(5) + n(5) + "101" + n(5) + "0110011", None)
  val OR   = RVInst("OR",   "b0000000" + n(5) + n(5) + "110" + n(5) + "0110011", None)
  val AND  = RVInst("AND",  "b0000000" + n(5) + n(5) + "111" + n(5) + "0110011", None)

  val FENCE = RVInst("FENCE", "b" + n(7) + n(5) + n(5) + "000" + n(5) + "0001111", None)

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

// -------- DecodeField helpers --------

trait DecodeAPI {
  def bitPatFor[D <: chisel3.Data](v: D): BitPat = BitPat(v.litValue.U(v.getWidth.W))
}

// -------- DecodeFields --------

/** Outputs UInt(width) so decode never casts to ImmType (avoids W001); use ImmType.safe() when reading. */
object ImmTypeField extends DecodeField[RVInst, UInt] with DecodeAPI {
  def name = "imm_type"
  def chiselType = UInt(ImmType.getWidth.W)
  def genTable(inst: RVInst): BitPat = inst.immType.fold(BitPat.dontCare(ImmType.getWidth))(bitPatFor)
}

// -------- IDU module --------

class IDU(addrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in     = Flipped(Decoupled(new nzea_core.IFUOut(addrWidth)))
    val out = Decoupled(new nzea_core.IDUOut(addrWidth))
  })

  // Build TruthTable from RiscvInsts + ImmTypeField, then decode with QMCMinimizer only (no Espresso).
  val allInsts   = RiscvInsts.all
  val mapping    = allInsts.map(p => (p.bitPat, ImmTypeField.genTable(p)))
  val default    = BitPat(ImmType.I.litValue.U(ImmType.getWidth.W))
  val table      = TruthTable(mapping, default)
  val decodedRaw = decoder(QMCMinimizer, io.in.bits.inst, table)
  val (immType, _) = ImmType.safe(decodedRaw)

  val inst = io.in.bits.inst
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  val immB = Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  val immU = Cat(inst(31, 12), 0.U(12.W))
  val immJ = Cat(Fill(11, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))

  val imm = Mux1H(immType.asUInt, Seq(immI, immS, immB, immU, immJ))

  io.out.valid := io.in.valid
  io.out.bits.pc  := io.in.bits.pc
  io.out.bits.imm := imm
  io.in.ready := io.out.ready
}
