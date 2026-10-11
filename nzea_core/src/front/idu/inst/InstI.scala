package nzea_core.front.idu.inst

import nzea_core.back.int.{AluOp, BruOp, SysuOp}
import nzea_core.back.mem.LsuOp
import nzea_core.isa.{Srcs, Xlen}
import Dispatch.*

/** The base ISA's rows — RV32I with the system and CSR forms, plus what RV64I adds — and the order they land in the
  * table.
  *
  * Both widths' rows live here because they are one specification: [[rows]] builds RV32I's order, re-encoding the three
  * shift immediates in place and appending the 64-bit additions, which is how the wider ISA is defined.
  */
private[idu] object InstI:

    // U-type: LUI=0110111, AUIPC=0010111
    val LUI = RVInst("LUI", uType("0110111"), Some(ImmType.U), alu(AluOp.Add, Srcs.ImmZero))
    val AUIPC = RVInst("AUIPC", uType("0010111"), Some(ImmType.U), alu(AluOp.Add, Srcs.PcImm))

    // J-type: JAL=1101111
    val JAL = RVInst("JAL", uType("1101111"), Some(ImmType.J), bru(BruOp.JAL, Srcs.PcImm))

    // I-type
    val JALR = RVInst("JALR", iType("000", "1100111"), Some(ImmType.I), bru(BruOp.JALR, Srcs.Rs1Imm))

    val LB = RVInst("LB", iType("000", "0000011"), Some(ImmType.I), mem(LsuOp.LB, Srcs.Rs1Imm))
    val LH = RVInst("LH", iType("001", "0000011"), Some(ImmType.I), mem(LsuOp.LH, Srcs.Rs1Imm))
    val LW = RVInst("LW", iType("010", "0000011"), Some(ImmType.I), mem(LsuOp.LW, Srcs.Rs1Imm))
    val LBU = RVInst("LBU", iType("100", "0000011"), Some(ImmType.I), mem(LsuOp.LBU, Srcs.Rs1Imm))
    val LHU = RVInst("LHU", iType("101", "0000011"), Some(ImmType.I), mem(LsuOp.LHU, Srcs.Rs1Imm))

    val ADDI = RVInst("ADDI", iType("000", "0010011"), Some(ImmType.I), alu(AluOp.Add, Srcs.Rs1Imm))
    val SLTI = RVInst("SLTI", iType("010", "0010011"), Some(ImmType.I), alu(AluOp.Slt, Srcs.Rs1Imm))
    val SLTIU = RVInst("SLTIU", iType("011", "0010011"), Some(ImmType.I), alu(AluOp.Sltu, Srcs.Rs1Imm))
    val XORI = RVInst("XORI", iType("100", "0010011"), Some(ImmType.I), alu(AluOp.Xor, Srcs.Rs1Imm))
    val ORI = RVInst("ORI", iType("110", "0010011"), Some(ImmType.I), alu(AluOp.Or, Srcs.Rs1Imm))
    val ANDI = RVInst("ANDI", iType("111", "0010011"), Some(ImmType.I), alu(AluOp.And, Srcs.Rs1Imm))

    // The shift immediates: a five-bit shift amount under funct7, bit 25 pinned to zero.
    val SLLI = RVInst("SLLI", rType("0000000", "001", "0010011"), Some(ImmType.I), alu(AluOp.Sll, Srcs.Rs1Imm))
    val SRLI = RVInst("SRLI", rType("0000000", "101", "0010011"), Some(ImmType.I), alu(AluOp.Srl, Srcs.Rs1Imm))
    val SRAI = RVInst("SRAI", rType("0100000", "101", "0010011"), Some(ImmType.I), alu(AluOp.Sra, Srcs.Rs1Imm))

    val ECALL = RVInst(
        "ECALL",
        "b00000000000000000000000001110011",
        Some(ImmType.I),
        sysu(SysuOp.ECALL, Srcs.NoOperand),
        gprWr = false
    )

    val EBREAK = RVInst(
        "EBREAK",
        "b00000000000100000000000001110011",
        Some(ImmType.I),
        sysu(SysuOp.EBREAK, Srcs.NoOperand),
        gprWr = false
    )

    val CSRRW = RVInst("CSRRW", iType("001", "1110011"), Some(ImmType.I), sysu(SysuOp.CSRRW, Srcs.Rs1Csr))
    val CSRRS = RVInst("CSRRS", iType("010", "1110011"), Some(ImmType.I), sysu(SysuOp.CSRRS, Srcs.Rs1Csr))
    val CSRRC = RVInst("CSRRC", iType("011", "1110011"), Some(ImmType.I), sysu(SysuOp.CSRRC, Srcs.Rs1Csr))
    val CSRRWI = RVInst("CSRRWI", iType("101", "1110011"), Some(ImmType.Z), sysu(SysuOp.CSRRWI, Srcs.ImmCsr))
    val CSRRSI = RVInst("CSRRSI", iType("110", "1110011"), Some(ImmType.Z), sysu(SysuOp.CSRRSI, Srcs.ImmCsr))
    val CSRRCI = RVInst("CSRRCI", iType("111", "1110011"), Some(ImmType.Z), sysu(SysuOp.CSRRCI, Srcs.ImmCsr))

    // S-type: rs1=base, rs2=data; no rd
    val SB = RVInst("SB", iType("000", "0100011"), Some(ImmType.S), mem(LsuOp.SB, Srcs.Rs1ImmRs2), gprWr = false)
    val SH = RVInst("SH", iType("001", "0100011"), Some(ImmType.S), mem(LsuOp.SH, Srcs.Rs1ImmRs2), gprWr = false)
    val SW = RVInst("SW", iType("010", "0100011"), Some(ImmType.S), mem(LsuOp.SW, Srcs.Rs1ImmRs2), gprWr = false)

    // B-type: the pc it branches from, then rs1, rs2 for the compare; no rd
    val BEQ = RVInst("BEQ", iType("000", "1100011"), Some(ImmType.B), bru(BruOp.BEQ, Srcs.PcRs1Rs2), gprWr = false)
    val BNE = RVInst("BNE", iType("001", "1100011"), Some(ImmType.B), bru(BruOp.BNE, Srcs.PcRs1Rs2), gprWr = false)
    val BLT = RVInst("BLT", iType("100", "1100011"), Some(ImmType.B), bru(BruOp.BLT, Srcs.PcRs1Rs2), gprWr = false)
    val BGE = RVInst("BGE", iType("101", "1100011"), Some(ImmType.B), bru(BruOp.BGE, Srcs.PcRs1Rs2), gprWr = false)
    val BLTU = RVInst("BLTU", iType("110", "1100011"), Some(ImmType.B), bru(BruOp.BLTU, Srcs.PcRs1Rs2), gprWr = false)
    val BGEU = RVInst("BGEU", iType("111", "1100011"), Some(ImmType.B), bru(BruOp.BGEU, Srcs.PcRs1Rs2), gprWr = false)

    // R-type: rs1, rs2
    val ADD = RVInst("ADD", rType("0000000", "000", "0110011"), None, alu(AluOp.Add, Srcs.Rs1Rs2))
    val SUB = RVInst("SUB", rType("0100000", "000", "0110011"), None, alu(AluOp.Sub, Srcs.Rs1Rs2))
    val SLL = RVInst("SLL", rType("0000000", "001", "0110011"), None, alu(AluOp.Sll, Srcs.Rs1Rs2))
    val SLT = RVInst("SLT", rType("0000000", "010", "0110011"), None, alu(AluOp.Slt, Srcs.Rs1Rs2))
    val SLTU = RVInst("SLTU", rType("0000000", "011", "0110011"), None, alu(AluOp.Sltu, Srcs.Rs1Rs2))
    val XOR = RVInst("XOR", rType("0000000", "100", "0110011"), None, alu(AluOp.Xor, Srcs.Rs1Rs2))
    val SRL = RVInst("SRL", rType("0000000", "101", "0110011"), None, alu(AluOp.Srl, Srcs.Rs1Rs2))
    val SRA = RVInst("SRA", rType("0100000", "101", "0110011"), None, alu(AluOp.Sra, Srcs.Rs1Rs2))
    val OR = RVInst("OR", rType("0000000", "110", "0110011"), None, alu(AluOp.Or, Srcs.Rs1Rs2))
    val AND = RVInst("AND", rType("0000000", "111", "0110011"), None, alu(AluOp.And, Srcs.Rs1Rs2))

    val FENCE = RVInst("FENCE", iType("000", "0001111"), None, sysu(SysuOp.FENCE, Srcs.NoOperand), gprWr = false)

    /** RV64 re-encodes the shift immediates: funct6 and a six-bit shift amount, bit 25 included. Same instruction
      * names, and [[rows]] lands them on the base rows' positions.
      */
    val SLLI64 = RVInst("SLLI", shift64("000000", "001"), Some(ImmType.I), alu(AluOp.Sll, Srcs.Rs1Imm))
    val SRLI64 = RVInst("SRLI", shift64("000000", "101"), Some(ImmType.I), alu(AluOp.Srl, Srcs.Rs1Imm))
    val SRAI64 = RVInst("SRAI", shift64("010000", "101"), Some(ImmType.I), alu(AluOp.Sra, Srcs.Rs1Imm))

    // The 64-bit additions of the base ISA. They are 32-bit instruction words like the rest, so the tables read them the
    // same way; what makes them RV64 is the register width they name.
    val LD = RVInst("LD", iType("011", "0000011"), Some(ImmType.I), mem(LsuOp.LD, Srcs.Rs1Imm))
    val LWU = RVInst("LWU", iType("110", "0000011"), Some(ImmType.I), mem(LsuOp.LWU, Srcs.Rs1Imm))
    val SD = RVInst("SD", iType("011", "0100011"), Some(ImmType.S), mem(LsuOp.SD, Srcs.Rs1ImmRs2), gprWr = false)

    val ADDIW = RVInst("ADDIW", iType("000", "0011011"), Some(ImmType.I), alu(AluOp.Addw, Srcs.Rs1Imm))
    val SLLIW = RVInst("SLLIW", rType("0000000", "001", "0011011"), Some(ImmType.I), alu(AluOp.Sllw, Srcs.Rs1Imm))
    val SRLIW = RVInst("SRLIW", rType("0000000", "101", "0011011"), Some(ImmType.I), alu(AluOp.Srlw, Srcs.Rs1Imm))
    val SRAIW = RVInst("SRAIW", rType("0100000", "101", "0011011"), Some(ImmType.I), alu(AluOp.Sraw, Srcs.Rs1Imm))

    val ADDW = RVInst("ADDW", rType("0000000", "000", "0111011"), None, alu(AluOp.Addw, Srcs.Rs1Rs2))
    val SUBW = RVInst("SUBW", rType("0100000", "000", "0111011"), None, alu(AluOp.Subw, Srcs.Rs1Rs2))
    val SLLW = RVInst("SLLW", rType("0000000", "001", "0111011"), None, alu(AluOp.Sllw, Srcs.Rs1Rs2))
    val SRLW = RVInst("SRLW", rType("0000000", "101", "0111011"), None, alu(AluOp.Srlw, Srcs.Rs1Rs2))
    val SRAW = RVInst("SRAW", rType("0100000", "101", "0111011"), None, alu(AluOp.Sraw, Srcs.Rs1Rs2))

    /** The table order for one register width: RV32I's rows with the three shift immediates re-encoded in place where
      * the registers are 64 bits wide, then the 64-bit additions appended.
      */
    def rows(xlen: Xlen): Seq[RVInst] =
        val shifts = if xlen == Xlen.Rv64 then Seq(SLLI64, SRLI64, SRAI64) else Seq(SLLI, SRLI, SRAI)
        val added =
            if xlen == Xlen.Rv64 then Seq(LD, LWU, SD, ADDIW, SLLIW, SRLIW, SRAIW, ADDW, SUBW, SLLW, SRLW, SRAW)
            else Seq.empty
        Seq(
            LUI,
            AUIPC,
            JAL,
            JALR,
            BEQ,
            BNE,
            BLT,
            BGE,
            BLTU,
            BGEU,
            LB,
            LH,
            LW,
            LBU,
            LHU,
            SB,
            SH,
            SW,
            ADDI,
            SLTI,
            SLTIU,
            XORI,
            ORI,
            ANDI
        ) ++ shifts ++ Seq(
            ADD,
            SUB,
            SLL,
            SLT,
            SLTU,
            XOR,
            SRL,
            SRA,
            OR,
            AND,
            FENCE,
            ECALL,
            EBREAK,
            CSRRW,
            CSRRS,
            CSRRC,
            CSRRWI,
            CSRRSI,
            CSRRCI
        ) ++ added
