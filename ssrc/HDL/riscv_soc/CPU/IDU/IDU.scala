package riscv_cpu

import chisel3._
import chisel3.util._
import chisel3.util.BitPat
import chisel3.util.experimental.decode._
import org.chipsalliance.rvdecoderdb

import config._

import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.util._

import signal_value._
import bus_state._
import os.copy.over

class IDU_catch extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle {
        val clock = Input(Clock())
        val valid = Input(Bool())
        val pc    = Input(UInt(32.W))
        val Inst_Type = Input(UInt(2.W))
    })
    val code =
    s"""module IDU_catch(
    |   input clock,
    |   input valid,
    |   input [31:0] pc,
    |   input [1:0] Inst_Type
    |);
    |import "DPI-C" function void IDU_catch(input bit [31:0] pc, input bit [1:0] Inst_Type);
    |
    |always @(posedge clock) begin
    |   if (valid) begin
    |       IDU_catch(pc, Inst_Type);
    |   end
    |end
    |
    |endmodule
    """

    setInline("IDU_catch.v", code.stripMargin)
}

trait DecodeAPI {
    def Get_BitPat[T <: Data](Enum: T): BitPat = {
        BitPat(Enum.litValue.U(Enum.getWidth.W))
    }
}

case class rvInstructionPattern(val inst: rvdecoderdb.Instruction) extends DecodePattern {
    override def bitPat: BitPat = BitPat("b" + inst.encoding.toString())
}

object Special_inst extends DecodeField[rvInstructionPattern, Special_instTypeEnum.Type] with DecodeAPI{
    override def name: String = "special_inst"
    override def chiselType = Special_instTypeEnum()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "fence.i" => Get_BitPat(Special_instTypeEnum.fence_I)
            case _ => Get_BitPat(Special_instTypeEnum.None)
        }
    }
}

object Imm_Field extends DecodeField[rvInstructionPattern, Imm_TypeEnum.Type] with DecodeAPI{
    override def name: String = "imm"
    override def chiselType = Imm_TypeEnum()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.args.map(_.toString).collectFirst {
            case "imm12" | "shamtw" | "csr"     => Get_BitPat(Imm_TypeEnum.Imm_I)
            case "imm12hi" | "imm12lo"          => Get_BitPat(Imm_TypeEnum.Imm_S)
            case "bimm12hi" | "bimm12lo"        => Get_BitPat(Imm_TypeEnum.Imm_B)
            case "imm20"                        => Get_BitPat(Imm_TypeEnum.Imm_U)
            case "jimm20"                       => Get_BitPat(Imm_TypeEnum.Imm_J)
        }.getOrElse(BitPat.dontCare(Imm_TypeEnum.getWidth))
    }
}

object Bran_Field extends DecodeField[rvInstructionPattern, Bran_TypeEnum.Type] with DecodeAPI {
    override def name: String = "branch"
    override def chiselType = Bran_TypeEnum()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "beq"      => Get_BitPat(Bran_TypeEnum.Bran_Jeq)
            case "bne"      => Get_BitPat(Bran_TypeEnum.Bran_Jne)
            case "blt"      => Get_BitPat(Bran_TypeEnum.Bran_Jlt)
            case "bge"      => Get_BitPat(Bran_TypeEnum.Bran_Jge)
            case "bltu"     => Get_BitPat(Bran_TypeEnum.Bran_Jlt)
            case "bgeu"     => Get_BitPat(Bran_TypeEnum.Bran_Jge)
            case "jal"      => Get_BitPat(Bran_TypeEnum.Bran_Jmp)
            case "jalr"     => Get_BitPat(Bran_TypeEnum.Bran_Jmpr)
            case "ecall"    => Get_BitPat(Bran_TypeEnum.Bran_Jcsr)
            case "mret"     => Get_BitPat(Bran_TypeEnum.Bran_Jcsr)
            case _          => Get_BitPat(Bran_TypeEnum.Bran_NJmp)
        }
    }
}

object RegWr_Field extends DecodeField[rvInstructionPattern, RegWr_TypeEnum.Type] with DecodeAPI {
    override def name: String = "regwr"
    override def chiselType = RegWr_TypeEnum()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.args.map(_.toString).collectFirst {
            case "rd" => Get_BitPat(RegWr_TypeEnum.RegWr_Yes)
        }.getOrElse(Get_BitPat(RegWr_TypeEnum.RegWr_No))
    }
}

object EXUAsrc_Field extends DecodeField[rvInstructionPattern, EXUAsrc_TypeEnum.Type] with DecodeAPI {
    override def name: String = "EXUAsrc"
    override def chiselType = EXUAsrc_TypeEnum()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "auipc"                            => Get_BitPat(EXUAsrc_TypeEnum.EXUAsrc_PC)
            case _ => i.inst.args.map(_.toString).collectFirst {
                case "rs1" => Get_BitPat(EXUAsrc_TypeEnum.EXUAsrc_RS1)
            }.getOrElse(BitPat.dontCare(EXUAsrc_TypeEnum.getWidth))
        }
    }
}

object EXUBsrc_Field extends DecodeField[rvInstructionPattern, EXUBsrc_TypeEnum.Type] with DecodeAPI {
    override def name: String = "EXUBsrc"
    override def chiselType = EXUBsrc_TypeEnum()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "mret" | "ecall" => Get_BitPat(EXUBsrc_TypeEnum.EXUBsrc_CSR)
            case _ => i.inst.args.map(_.toString).collectFirst {
                case "rs2" => Get_BitPat(EXUBsrc_TypeEnum.EXUBsrc_RS2)
                case "imm12" | "imm20" | "shamtw" => Get_BitPat(EXUBsrc_TypeEnum.EXUBsrc_IMM)
                case "csr" => Get_BitPat(EXUBsrc_TypeEnum.EXUBsrc_CSR)
            }.getOrElse(BitPat.dontCare(EXUBsrc_TypeEnum.getWidth))
        }
    }
}

object EXUctr_Field extends DecodeField[rvInstructionPattern, EXUctr_TypeEnum.Type] with DecodeAPI {
    override def name: String = "EXUctr"
    override def chiselType = EXUctr_TypeEnum()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "add" | "addi" | "auipc" | "jal" | "jalr" => Get_BitPat(EXUctr_TypeEnum.EXUctr_ADD)
            case "sub" | "beq" | "bne" => Get_BitPat(EXUctr_TypeEnum.EXUctr_SUB)
            case "xor" | "xori" => Get_BitPat(EXUctr_TypeEnum.EXUctr_XOR)
            case "or" | "ori" | "csrrs" => Get_BitPat(EXUctr_TypeEnum.EXUctr_OR)
            case "and" | "andi" => Get_BitPat(EXUctr_TypeEnum.EXUctr_AND)
            case "slt" | "slti" | "blt" | "bge" => Get_BitPat(EXUctr_TypeEnum.EXUctr_Less_S)
            case "sltu" | "sltiu" | "bltu" | "bgeu" => Get_BitPat(EXUctr_TypeEnum.EXUctr_Less_U)
            case "sll" | "slli" => Get_BitPat(EXUctr_TypeEnum.EXUctr_SLL)
            case "srl" | "srli" => Get_BitPat(EXUctr_TypeEnum.EXUctr_SRL)
            case "sra" | "srai" => Get_BitPat(EXUctr_TypeEnum.EXUctr_SRA)
            case "csrrw"=> Get_BitPat(EXUctr_TypeEnum.EXUctr_A)
            case "lui" => Get_BitPat(EXUctr_TypeEnum.EXUctr_B)
            case "lb" | "lh" | "lw" | "lbu" | "lhu"  => Get_BitPat(EXUctr_TypeEnum.EXUctr_LD)
            case "sb" | "sh" | "sw"  => Get_BitPat(EXUctr_TypeEnum.EXUctr_ST)
            case _ => BitPat.dontCare(EXUctr_TypeEnum.getWidth)
        }
    }
}

object MemOp_Field extends DecodeField[rvInstructionPattern, MemOp_TypeEnum.Type] with DecodeAPI {
    override def name: String = "memop"
    override def chiselType = MemOp_TypeEnum()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "lb"       => Get_BitPat(MemOp_TypeEnum.MemOp_1BS)
            case "lh"       => Get_BitPat(MemOp_TypeEnum.MemOp_2BS)
            case "lw"       => Get_BitPat(MemOp_TypeEnum.MemOp_4BU)
            case "lbu"      => Get_BitPat(MemOp_TypeEnum.MemOp_1BU)
            case "lhu"      => Get_BitPat(MemOp_TypeEnum.MemOp_2BU)
            case "sb"       => Get_BitPat(MemOp_TypeEnum.MemOp_1BS)
            case "sh"       => Get_BitPat(MemOp_TypeEnum.MemOp_2BS)
            case "sw"       => Get_BitPat(MemOp_TypeEnum.MemOp_4BU)
            case _          => BitPat.dontCare(MemOp_TypeEnum.getWidth)
        }
    }
}

object csr_ctr_Field extends DecodeField[rvInstructionPattern, CSR_TypeEnum.Type] with DecodeAPI {
    override def name: String = "csr_ctr"
    override def chiselType = CSR_TypeEnum()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "ecall" => Get_BitPat(CSR_TypeEnum.CSR_R1W2)
            case "mret"  => Get_BitPat(CSR_TypeEnum.CSR_R1W0)
            case _       => i.inst.args.map(_.toString).collectFirst {
                case "csr" => Get_BitPat(CSR_TypeEnum.CSR_R1W1)
            }.getOrElse(Get_BitPat(CSR_TypeEnum.CSR_N))
        }
    }
}

object PC_Field extends DecodeField[rvInstructionPattern, UInt] with DecodeAPI {
    override def name: String = "pc"
    override def chiselType = UInt(2.W)
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "lb" | "lh" | "lw" | "lbu" | "lhu" | "sb" | "sh" | "sw" => BitPat("b01")
            case _ => i.inst.args.map(_.toString()).collectFirst {
                case "csr" => BitPat("b10")
            }.getOrElse(BitPat("b00"))
        }
    }
}

class IDU extends Module{
    val io = IO(new Bundle{
        val IFU_2_IDU     = Flipped(Decoupled(Input(new BUS_IFU_2_IDU)))
        val REG_2_IDU     = Input(new BUS_REG_2_IDU)

        val IDU_2_EXU     = Decoupled(Output(new BUS_IDU_2_EXU))
        val IDU_2_REG     = Output(new BUS_IDU_2_REG)
    })

    io.IDU_2_EXU.valid := io.IFU_2_IDU.valid
    io.IFU_2_IDU.ready := io.IDU_2_EXU.ready

    io.IDU_2_REG.GPR_Aaddr := io.IFU_2_IDU.bits.data(19, 15)
    io.IDU_2_REG.GPR_Baddr := io.IFU_2_IDU.bits.data(24, 20)

    val instTable = rvdecoderdb.instructions(os.pwd / "rvdecoderdb" / "rvdecoderdbtest" / "jvm" / "riscv-opcodes")

    val rv32iExceptInstructions = 
        Set("sbreak", "scall", "pause", "fence.tso", "fence", "slli_rv32", "srli_rv32", "srai_rv32")
    val rviTargetSets = Set("rv_i")
    val rv32iTargetSets = Set("rv32_i")
    val rvsysTargetSets = Set("rv_system")
    val rvzicsrTargetSets = Set("rv_zicsr")
    val rvzifencei = Set("rv_zifencei")

    val rviInstList = instTable
        .filter(instr => rviTargetSets.contains(instr.instructionSet.name))
        .filter(instr => !rv32iExceptInstructions.contains(instr.name))
        .filter(_.pseudoFrom.isEmpty)
        .map(rvInstructionPattern(_))
        .toSeq
    val rv32iInstList = instTable
        .filter(instr => rv32iTargetSets.contains(instr.instructionSet.name))
        .filter(instr => !rv32iExceptInstructions.contains(instr.name))
        .map(rvInstructionPattern(_))
    val rvsysInstList = instTable
        .filter(instr => rvsysTargetSets.contains(instr.instructionSet.name))
        .filter(_.pseudoFrom.isEmpty)
        .map(rvInstructionPattern(_))
    val rvzicsrInstList = instTable
        .filter(instr => rvzicsrTargetSets.contains(instr.instructionSet.name))
        .filter(_.pseudoFrom.isEmpty)
        .map(rvInstructionPattern(_))
        .toSeq
    val rvzifenceiInstList = instTable
        .filter(instr => rvzifencei.contains(instr.instructionSet.name))
        .filter(_.pseudoFrom.isEmpty)
        .map(rvInstructionPattern(_))
        .toSeq

    val instList = rviInstList ++ rv32iInstList ++ rvsysInstList ++ rvzicsrInstList ++ rvzifenceiInstList

    val allField = Seq(Special_inst, Imm_Field, Bran_Field, EXUAsrc_Field, EXUBsrc_Field, EXUctr_Field, csr_ctr_Field, RegWr_Field, MemOp_Field)

    require(instList.map(_.bitPat.getWidth).distinct.size == 1, "All instructions must have the same width")
    def Decode_bundle: DecodeBundle = new DecodeBundle(allField)
    val table: TruthTable = TruthTable(
        instList.map { op => op.bitPat -> allField.reverse.map(field => field.genTable(op)).reduce(_ ## _) },
        allField.reverse.map(_.default).reduce(_ ## _)
    )
    def Decode_decode(input: UInt): DecodeBundle = chisel3.util.experimental.decode.decoder(QMCMinimizer, input, table).asTypeOf(Decode_bundle)

    val rvdecoderResult = chisel3.util.experimental.decode.decoder(QMCMinimizer, io.IFU_2_IDU.bits.data, table).asTypeOf(Decode_bundle)
    
    if(Config.Simulate) {
        val catchTable = new DecodeTable(instList, Seq(PC_Field))
        val catchResult = catchTable.decode(io.IFU_2_IDU.bits.data)
        val Catch = Module(new IDU_catch)
        Catch.io.clock := clock
        Catch.io.valid := io.IDU_2_EXU.fire && !reset.asBool
        Catch.io.pc := io.IFU_2_IDU.bits.PC
        Catch.io.Inst_Type := catchResult(PC_Field)
    }

    // io.IDU_2_IFU.hazard := rvdecoderResult(Special_inst) === Special_instTypeEnum.fence_I

    val imm = MuxLookup(rvdecoderResult(Imm_Field), 0.U)(
        Seq(
            Imm_TypeEnum.Imm_I -> Cat(Fill(21, io.IFU_2_IDU.bits.data(31)), io.IFU_2_IDU.bits.data(31, 20)),
            Imm_TypeEnum.Imm_U -> Cat(io.IFU_2_IDU.bits.data(31, 12), Fill(12, 0.U)),
            Imm_TypeEnum.Imm_S -> Cat(Fill(20, io.IFU_2_IDU.bits.data(31)), io.IFU_2_IDU.bits.data(31, 25), io.IFU_2_IDU.bits.data(11, 7)),
            Imm_TypeEnum.Imm_B -> Cat(Fill(20, io.IFU_2_IDU.bits.data(31)), io.IFU_2_IDU.bits.data(7), io.IFU_2_IDU.bits.data(30, 25), io.IFU_2_IDU.bits.data(11, 8), 0.U(1.W)),
            Imm_TypeEnum.Imm_J -> Cat(Fill(12, io.IFU_2_IDU.bits.data(31)), io.IFU_2_IDU.bits.data(19, 12), io.IFU_2_IDU.bits.data(20), io.IFU_2_IDU.bits.data(30, 21), 0.U(1.W)),
        )
    )

    val csr_raddr = MuxLookup(rvdecoderResult(csr_ctr_Field), io.IFU_2_IDU.bits.data(31, 20))(
        Seq(
            CSR_TypeEnum.CSR_R1W0 -> "h341".U,
            CSR_TypeEnum.CSR_R1W2 -> "h305".U,
        )
    )

    val gpr_waddr = Mux(rvdecoderResult(RegWr_Field) === RegWr_TypeEnum.RegWr_Yes, io.IFU_2_IDU.bits.data(10, 7), 0.U(4.W))

    io.IDU_2_REG.CSR_raddr         <> csr_raddr

    val EXU_A = MuxLookup(rvdecoderResult(EXUAsrc_Field), 0.U)(Seq(
        EXUAsrc_TypeEnum.EXUAsrc_RS1 -> io.REG_2_IDU.GPR_Adata,
        EXUAsrc_TypeEnum.EXUAsrc_PC  -> io.IFU_2_IDU.bits.PC,
    ))

    val EXU_B = MuxLookup(rvdecoderResult(EXUBsrc_Field), 0.U)(Seq(
        EXUBsrc_TypeEnum.EXUBsrc_RS2 -> io.REG_2_IDU.GPR_Bdata,
        EXUBsrc_TypeEnum.EXUBsrc_IMM -> imm,
        EXUBsrc_TypeEnum.EXUBsrc_CSR -> io.REG_2_IDU.CSR_rdata,
    ))

    io.IDU_2_EXU.bits.Branch       <> rvdecoderResult(Bran_Field)   
    io.IDU_2_EXU.bits.MemOp        <> rvdecoderResult(MemOp_Field)  
    io.IDU_2_EXU.bits.EXU_A        <> EXU_A             
    io.IDU_2_EXU.bits.EXU_B        <> EXU_B              
    io.IDU_2_EXU.bits.EXUctr       <> rvdecoderResult(EXUctr_Field) 
    io.IDU_2_EXU.bits.csr_ctr      <> rvdecoderResult(csr_ctr_Field)
    io.IDU_2_EXU.bits.Imm          <> imm            
    io.IDU_2_EXU.bits.GPR_waddr    <> gpr_waddr   
    io.IDU_2_EXU.bits.PC           <> io.IFU_2_IDU.bits.PC        
}
