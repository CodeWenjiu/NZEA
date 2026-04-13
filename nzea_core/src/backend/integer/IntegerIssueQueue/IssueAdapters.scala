package nzea_core.backend.integer

import chisel3._
import chisel3.util.MuxLookup
import nzea_core.backend.integer.nnu.{NnInput, NnOp}
import nzea_core.frontend.{AluSrc, CsrType, FuDecode, FuType}
import nzea_rtl.PipeIO

/** FU issue adapters map a generic IQ entry + resolved source operands into FU-specific PipeIO payloads.
  * Keep this layer separate from IQ scheduling so source-select policy can evolve per FU without bloating IQ.
  */
object IssueAdapters {
  object Alu {
    def drive(
      valid: Bool,
      entry: IntegerIssueQueueEntry,
      rs1: UInt,
      rs2: UInt,
      out: PipeIO[AluInput]
    ): Unit = {
      out.valid := valid
      val (aluSrc0, _) = AluSrc.safe(FuDecode.take(entry.fu_src, AluSrc.getWidth))
      out.bits.opA := MuxLookup(aluSrc0.asUInt, rs1)(Seq(
        AluSrc.ImmZero.asUInt -> entry.imm,
        AluSrc.PcImm.asUInt   -> entry.pc
      ))
      out.bits.opB := MuxLookup(aluSrc0.asUInt, rs2)(Seq(
        AluSrc.Rs1Imm.asUInt  -> entry.imm,
        AluSrc.ImmZero.asUInt -> 0.U(32.W),
        AluSrc.PcImm.asUInt   -> entry.imm
      ))
      out.bits.aluOp  := AluOp.safe(FuDecode.take(entry.fu_op, AluOp.getWidth))._1
      out.bits.pc     := entry.pc
      out.bits.rob_id := entry.rob_id
      out.bits.p_rd   := entry.p_rd
    }
  }

  object Bru {
    def drive(
      valid: Bool,
      entry: IntegerIssueQueueEntry,
      rs1: UInt,
      rs2: UInt,
      out: PipeIO[BruInput]
    ): Unit = {
      out.valid := valid
      out.bits.pc           := entry.pc
      out.bits.pred_next_pc := entry.pred_next_pc
      out.bits.offset       := entry.imm
      out.bits.rs1          := rs1
      out.bits.rs2          := rs2
      out.bits.bruOp        := BruOp.safe(FuDecode.take(entry.fu_op, BruOp.getWidth))._1
      out.bits.rob_id       := entry.rob_id
      out.bits.p_rd         := entry.p_rd
    }
  }

  object Agu {
    def drive(
      valid: Bool,
      entry: IntegerIssueQueueEntry,
      rs1: UInt,
      rs2: UInt,
      out: PipeIO[AguInput]
    ): Unit = {
      out.valid := valid
      out.bits.base      := rs1
      out.bits.imm       := entry.imm
      out.bits.lsuOp     := LsuOp.safe(FuDecode.take(entry.fu_op, LsuOp.getWidth))._1
      out.bits.storeData := rs2
      out.bits.pc        := entry.pc
      out.bits.rob_id    := entry.rob_id
      out.bits.p_rd      := entry.p_rd
      out.bits.lsq_id    := entry.lsq_id
    }
  }

  object Mul {
    def drive(
      valid: Bool,
      entry: IntegerIssueQueueEntry,
      rs1: UInt,
      rs2: UInt,
      out: PipeIO[MulInput]
    ): Unit = {
      out.valid := valid
      out.bits.opA   := rs1
      out.bits.opB   := rs2
      out.bits.mulOp := MulOp.safe(FuDecode.take(entry.fu_op, MulOp.getWidth))._1
      out.bits.pc    := entry.pc
      out.bits.rob_id := entry.rob_id
      out.bits.p_rd   := entry.p_rd
    }
  }

  object Div {
    def drive(
      valid: Bool,
      entry: IntegerIssueQueueEntry,
      rs1: UInt,
      rs2: UInt,
      out: PipeIO[DivInput]
    ): Unit = {
      out.valid := valid
      out.bits.opA   := rs1
      out.bits.opB   := rs2
      out.bits.divOp := DivOp.safe(FuDecode.take(entry.fu_op, DivOp.getWidth))._1
      out.bits.pc    := entry.pc
      out.bits.rob_id := entry.rob_id
      out.bits.p_rd   := entry.p_rd
    }
  }

  object Sysu {
    def drive(
      valid: Bool,
      entry: IntegerIssueQueueEntry,
      rs1: UInt,
      csrRdata: UInt,
      out: PipeIO[SysuInput]
    ): Unit = {
      out.valid := valid
      out.bits.rob_id   := entry.rob_id
      out.bits.pc       := entry.pc
      out.bits.p_rd     := entry.p_rd
      out.bits.csr_type := Mux(entry.fu_type === FuType.SYSU, CsrType.fromAddr(entry.csr_addr), CsrType.None)
      out.bits.csr_rdata := csrRdata
      out.bits.rs1_val   := rs1
      out.bits.sysuOp    := SysuOp.safe(FuDecode.take(entry.fu_op, SysuOp.getWidth))._1
      out.bits.imm       := entry.imm
    }
  }

  object Nnu {
    def drive(
      valid: Bool,
      entry: IntegerIssueQueueEntry,
      rs1: UInt,
      rs2: UInt,
      out: PipeIO[NnInput]
    ): Unit = {
      out.valid := valid
      out.bits.nnOp   := NnOp.safe(FuDecode.take(entry.fu_op, NnOp.getWidth))._1
      out.bits.rs1    := rs1
      out.bits.rs2    := rs2
      out.bits.pc     := entry.pc
      out.bits.rob_id := entry.rob_id
      out.bits.p_rd   := entry.p_rd
    }
  }
}
