package riscv_soc.cpu.frontend

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

import riscv_soc.bus._
import signal_value._
import os.copy.over

class IDU_catch extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle {
        val clock = Input(Clock())
        val valid = Input(Bool())
        val pc    = Input(UInt(32.W))
    })
    val code =
    s"""module IDU_catch(
    |   input clock,
    |   input valid,
    |   input [31:0] pc
    |);
    |import "DPI-C" function void IDU_catch(input bit [31:0] pc);
    |
    |always @(posedge clock) begin
    |   if (valid) begin
    |       IDU_catch(pc);
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

object IsLogic_Field extends DecodeField[rvInstructionPattern, IsLogic.Type] with DecodeAPI {
    override def name: String = "is_logic"
    override def chiselType = IsLogic()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "beq"              => Get_BitPat(IsLogic.EQ)
            case "bne"              => Get_BitPat(IsLogic.NE)
            case "slt" | "blt"      => Get_BitPat(IsLogic.LT)
            case "bge"              => Get_BitPat(IsLogic.GE)
            case "bltu" | "sltu"    => Get_BitPat(IsLogic.LTU)
            case "bgeu"             => Get_BitPat(IsLogic.GEU)
            case "slti"             => Get_BitPat(IsLogic.SLTI)
            case "sltiu"            => Get_BitPat(IsLogic.SLTIU)
            case _ => BitPat.dontCare(IsLogic.getWidth)
        }
    }
}

object Inst_Type_Field extends DecodeField[rvInstructionPattern, Inst_Type.Type] with DecodeAPI {
    override def name: String = "inst_type"
    override def chiselType = Inst_Type()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "lb" | "lh" | "lw" | "lbu" | "lhu" | "sb" | "sh" | "sw" => Get_BitPat(Inst_Type.LS)
            case _ => Get_BitPat(Inst_Type.AL)
        }
    }
}

object SRCA_Field extends DecodeField[rvInstructionPattern, SRCA.Type] with DecodeAPI {
    override def name: String = "srca"
    override def chiselType = SRCA()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "lui" | "slti" | "sltiu" | "slt" | "sltu" => Get_BitPat(SRCA.ZERO)
            case "auipc" | "jal" |
                 "beq" | "bne"  | "blt"  | "bge"  | "bltu" | "bgeu" => Get_BitPat(SRCA.PC)
            case _ => Get_BitPat(SRCA.RS1)
        }
    }
}

object SRCB_Field extends DecodeField[rvInstructionPattern, SRCB.Type] with DecodeAPI {
    override def name: String = "srcb"
    override def chiselType = SRCB()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "lui"  | "auipc" | "jal" | "jalr" | "addi" |
                 "xori" | "ori"   | "andi"| 
                 "slli" | "srli"  | "srai" => Get_BitPat(SRCB.IMM)

            case "beq"  | "bne"  | "blt"  | "bge"  | "bltu" | "bgeu" => Get_BitPat(SRCB.LogicBranch)

            case "slti" | "sltiu" | "slt" | "sltu" => Get_BitPat(SRCB.LogicSet)

            case _ => Get_BitPat(SRCB.RS2)
        }
    }
}

object AlCtrl_Field extends DecodeField[rvInstructionPattern, AlCtrl.Type] with DecodeAPI {
    override def name: String = "al_ctrl"
    override def chiselType = AlCtrl()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "lui"  | "auipc" | 
                 "jal" | "jalr" | 
                 "beq" | "bne"  | "blt"  | "bge"  | "bltu" | "bgeu" |
                 "slti" | "sltiu" | "slt" | "sltu" |
                 "add" | "addi" => Get_BitPat(AlCtrl.ADD)

            case "sub" => Get_BitPat(AlCtrl.SUB)

            case "xori" | "xor" => Get_BitPat(AlCtrl.XOR)
            case "or"   | "ori" => Get_BitPat(AlCtrl.OR)
            case "and" | "andi" => Get_BitPat(AlCtrl.AND)

            case "sll" | "slli" => Get_BitPat(AlCtrl.SLL)
            case "srl" | "srli" => Get_BitPat(AlCtrl.SRL)
            case "sra" | "srai" => Get_BitPat(AlCtrl.SRA)

            case _ => BitPat.dontCare(AlCtrl.getWidth)
        }
    }
}

object LsCtrl_Field extends DecodeField[rvInstructionPattern, LsCtrl.Type] with DecodeAPI {
    override def name: String = "ls_ctrl"
    override def chiselType = LsCtrl()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "lb" => Get_BitPat(LsCtrl.LB)
            case "lh" => Get_BitPat(LsCtrl.LH)
            case "lw" => Get_BitPat(LsCtrl.LW)

            case "lbu" => Get_BitPat(LsCtrl.LBU)
            case "lhu" => Get_BitPat(LsCtrl.LHU)

            case "sb" => Get_BitPat(LsCtrl.SB)
            case "sh" => Get_BitPat(LsCtrl.SH)
            case "sw" => Get_BitPat(LsCtrl.SW)

            case _    => BitPat.dontCare(LsCtrl.getWidth)
        }
    }
}

object WbCtrl_Field extends DecodeField[rvInstructionPattern, WbCtrl.Type] with DecodeAPI {
    override def name: String = "wb_ctrl"
    override def chiselType = WbCtrl()
    override def genTable(i: rvInstructionPattern): BitPat = {
        i.inst.name match {
            case "jal" | "jalr"|
                 "beq" | "bne" | "blt" | "bge" | "bltu" | "bgeu" => Get_BitPat(WbCtrl.Jump)

            case "fence" | "ecall" | "ebreak" => BitPat.dontCare(WbCtrl.getWidth)
            
            case _ => Get_BitPat(WbCtrl.Write_GPR)
        }
    }
}

object GPR_Write_Field extends DecodeField[rvInstructionPattern, Bool] with DecodeAPI {
    override def name: String = "gpr_write_not"
    override def chiselType = Bool()
    override def genTable(i: rvInstructionPattern): BitPat = {
        if (i.inst.args.map(_.toString).contains("rd")) {
            Get_BitPat(true.B)
        } else {
            Get_BitPat(false.B)
        }
    }
}

object RS1_Used_Field extends DecodeField[rvInstructionPattern, Bool] with DecodeAPI {
    override def name: String = "rs1_used"
    override def chiselType = Bool()
    override def genTable(i: rvInstructionPattern): BitPat = {
        if (i.inst.args.map(_.toString).contains("rs1")) {
            Get_BitPat(true.B)
        } else {
            Get_BitPat(false.B)
        }
    }
}

object RS2_Used_Field extends DecodeField[rvInstructionPattern, Bool] with DecodeAPI {
    override def name: String = "rs2_used"
    override def chiselType = Bool()
    override def genTable(i: rvInstructionPattern): BitPat = {
        if (i.inst.args.map(_.toString).contains("rs2")) {
            Get_BitPat(true.B)
        } else {
            Get_BitPat(false.B)
        }
    }
}

class IDU extends Module {
    val io = IO(new Bundle {
        val IFU_2_IDU = Flipped(Decoupled(Input(new IFU_2_IDU)))
        
        val IDU_2_ISU = Decoupled(Output(new IDU_2_ISU))

        val IDU_2_REG = Output(new IDU_2_REG)
        val REG_2_IDU = Input(new REG_2_IDU)

        val WB_Bypass = Flipped(ValidIO(Output(new WB_Bypass)))

        val IDU_GPR_READMSG = Output(new IDU_GPR_READMSG)
    })

    io.IDU_2_ISU.valid := io.IFU_2_IDU.valid
    io.IFU_2_IDU.ready := io.IDU_2_ISU.ready

    val rs1_addr_origin = io.IFU_2_IDU.bits.inst(19, 15)
    val rs2_addr_origin = io.IFU_2_IDU.bits.inst(24, 20)

    io.IDU_2_REG.rs1_addr := rs1_addr_origin
    io.IDU_2_REG.rs2_addr := rs2_addr_origin

    def conflict(rs: UInt, rd: UInt) = (rs === rd)
    def conflict_gpr(rs: UInt, rd:UInt) = (conflict(rs, rd) && (rs =/= 0.U))
    def conflict_gpr_valid(rs: UInt) = 
        (conflict_gpr(rs, io.WB_Bypass.bits.gpr_waddr) & io.WB_Bypass.valid)

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

    val allField = Seq(Inst_Type_Field, Imm_Field, GPR_Write_Field, RS1_Used_Field, RS2_Used_Field, IsLogic_Field, SRCA_Field, SRCB_Field, AlCtrl_Field, LsCtrl_Field, WbCtrl_Field)

    require(instList.map(_.bitPat.getWidth).distinct.size == 1, "All instructions must have the same width")
    def Decode_bundle: DecodeBundle = new DecodeBundle(allField)
    val table: TruthTable = TruthTable(
        instList.map { op => op.bitPat -> allField.reverse.map(field => field.genTable(op)).reduce(_ ## _) },
        allField.reverse.map(_.default).reduce(_ ## _)
    )
    def Decode_decode(input: UInt): DecodeBundle = chisel3.util.experimental.decode.decoder(QMCMinimizer, input, table).asTypeOf(Decode_bundle)
    
    val inst = io.IFU_2_IDU.bits.inst

    val rvdecoderResult = chisel3.util.experimental.decode.decoder(QMCMinimizer, inst, table).asTypeOf(Decode_bundle)
    
    val imm = MuxLookup(rvdecoderResult(Imm_Field), 0.U)(
        Seq(
            Imm_TypeEnum.Imm_I -> Cat(Fill(21, inst(31)), inst(31, 20)),
            Imm_TypeEnum.Imm_U -> Cat(inst(31, 12), Fill(12, 0.U)),
            Imm_TypeEnum.Imm_S -> Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7)),
            Imm_TypeEnum.Imm_B -> Cat(Fill(20, inst(31)), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)),
            Imm_TypeEnum.Imm_J -> Cat(Fill(12, inst(31)), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W)),
        )
    )

    val rs1_addr_fix = Mux(rvdecoderResult(RS1_Used_Field), io.IFU_2_IDU.bits.inst(19, 15), 0.U(5.W))
    val rs2_addr_fix = Mux(rvdecoderResult(RS2_Used_Field), io.IFU_2_IDU.bits.inst(24, 20), 0.U(5.W))

    io.IDU_GPR_READMSG.rs1_addr := rs1_addr_fix
    io.IDU_GPR_READMSG.rs2_addr := rs2_addr_fix

    val rs1_conflict = conflict_gpr_valid(rs1_addr_fix)
    val rs2_conflict = conflict_gpr_valid(rs2_addr_fix)

    val rs1_val = Mux(rs1_conflict, io.WB_Bypass.bits.gpr_wdata, io.REG_2_IDU.rs1_val)
    val rs2_val = Mux(rs2_conflict, io.WB_Bypass.bits.gpr_wdata, io.REG_2_IDU.rs2_val)

    io.IDU_2_ISU.bits.PC := io.IFU_2_IDU.bits.PC
    io.IDU_2_ISU.bits.trap.traped := false.B
    io.IDU_2_ISU.bits.trap.trap_type := Trap_type.Ebreak

    io.IDU_2_ISU.bits.rs1_val := rs1_val
    io.IDU_2_ISU.bits.rs2_val := rs2_val

    io.IDU_2_ISU.bits.gpr_waddr := Mux(rvdecoderResult(GPR_Write_Field), inst(11, 7), 0.U(5.W))
    io.IDU_2_ISU.bits.imm := imm

    io.IDU_2_ISU.bits.is_ctrl.inst_Type := rvdecoderResult(Inst_Type_Field)
    io.IDU_2_ISU.bits.is_ctrl.isLogic := rvdecoderResult(IsLogic_Field)
    io.IDU_2_ISU.bits.is_ctrl.srca := rvdecoderResult(SRCA_Field)
    io.IDU_2_ISU.bits.is_ctrl.srcb := rvdecoderResult(SRCB_Field)

    io.IDU_2_ISU.bits.al_ctrl := rvdecoderResult(AlCtrl_Field)
    io.IDU_2_ISU.bits.ls_ctrl := rvdecoderResult(LsCtrl_Field)

    io.IDU_2_ISU.bits.wb_ctrl := rvdecoderResult(WbCtrl_Field)

    if(Config.Simulate) {
        val Catch = Module(new IDU_catch)
        Catch.io.clock := clock
        Catch.io.valid := io.IDU_2_ISU.fire && !reset.asBool
        Catch.io.pc := io.IFU_2_IDU.bits.PC
    }
}
