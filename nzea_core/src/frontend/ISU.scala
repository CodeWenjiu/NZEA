package nzea_core.frontend

import chisel3._
import chisel3.util.{Decoupled, Mux1H, MuxLookup}
import nzea_core.backend.fu.{AluInput, AluOp}

/** ISU pre-dispatch control for ALU: which opA/opB to pass and whether/how to pre-process operands. */
object AluIsuCtrl extends chisel3.ChiselEnum {
  val Rs1Rs2  = Value  // opA=rs1, opB=rs2 (R-type)
  val Rs1Imm  = Value  // opA=rs1, opB=imm (I-type arith)
  val ImmZero = Value  // opA=imm, opB=0 → result=imm (LUI)
  val PcImm   = Value  // opA=pc, opB=imm (AUIPC)
  val Slt     = Value  // pre: signed rs1<rs2 → opA=0/1, opB=0, aluOp=Add (SLT)
  val Sltu    = Value  // pre: unsigned rs1<rs2 (SLTU)
  val SltImm  = Value  // pre: signed rs1<imm (SLTI)
  val SltuImm = Value  // pre: unsigned rs1<imm (SLTIU)
}

/** ALU ISU ctrl width; used by IsuCtrlWidth (max over FUs). */
object AluIsuCtrlWidth {
  val Width: Int = AluIsuCtrl.getWidth
}

/** isu_ctrl union width: max over all FUs' ISU ctrl widths; extend Seq when adding FUs. */
object IsuCtrlWidth {
  val Width: Int = Seq(AluIsuCtrlWidth.Width).max
}

/** ISU: interpret isu_ctrl by fu_type, do per-FU pre-dispatch work, then send fu_op as ChiselEnum to each FU. */
class ISU(addrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in   = Flipped(Decoupled(new IDUOut(addrWidth)))
    val alu  = Decoupled(new AluInput)
    val bru  = Decoupled(new Bundle {})
    val lsu  = Decoupled(new Bundle {})
    val sysu = Decoupled(new Bundle {})
  })

  val fu_type  = io.in.bits.fu_type
  val isu_ctrl = io.in.bits.isu_ctrl
  val outs     = Seq(io.alu, io.bru, io.lsu, io.sysu)
  val fuTypes  = Seq(FuType.ALU, FuType.BRU, FuType.LSU, FuType.SYSU)

  // ALU path: choose opA/opB and pre-process from isu_ctrl, then set aluOp
  val (aluIsuCtrl, _) = AluIsuCtrl.safe(isu_ctrl)
  val rs1  = io.in.bits.rs1
  val rs2  = io.in.bits.rs2
  val imm  = io.in.bits.imm
  val pc   = io.in.bits.pc
  val aluOpFromFuOp = AluOp.safe(io.in.bits.fu_op)._1

  val cmpSltRs2  = Mux(rs1.asSInt < rs2.asSInt, 1.U(32.W), 0.U(32.W))
  val cmpSltuRs2 = Mux(rs1 < rs2, 1.U(32.W), 0.U(32.W))
  val cmpSltImm  = Mux(rs1.asSInt < imm.asSInt, 1.U(32.W), 0.U(32.W))
  val cmpSltuImm = Mux(rs1 < imm, 1.U(32.W), 0.U(32.W))

  val ctrlKey = aluIsuCtrl.asUInt
  val aluOpA = MuxLookup(ctrlKey, rs1)(Seq(
    AluIsuCtrl.ImmZero.asUInt -> imm,
    AluIsuCtrl.PcImm.asUInt   -> pc,
    AluIsuCtrl.Slt.asUInt     -> cmpSltRs2,
    AluIsuCtrl.Sltu.asUInt    -> cmpSltuRs2,
    AluIsuCtrl.SltImm.asUInt  -> cmpSltImm,
    AluIsuCtrl.SltuImm.asUInt -> cmpSltuImm
  ))

  val aluOpB = MuxLookup(ctrlKey, rs2)(Seq(
    AluIsuCtrl.ImmZero.asUInt -> 0.U(32.W),
    AluIsuCtrl.PcImm.asUInt   -> imm,
    AluIsuCtrl.Slt.asUInt     -> 0.U(32.W),
    AluIsuCtrl.Sltu.asUInt    -> 0.U(32.W),
    AluIsuCtrl.SltImm.asUInt  -> 0.U(32.W),
    AluIsuCtrl.SltuImm.asUInt -> 0.U(32.W),
    AluIsuCtrl.Rs1Imm.asUInt  -> imm
  ))

  val aluOp = MuxLookup(ctrlKey, aluOpFromFuOp)(Seq(
    AluIsuCtrl.ImmZero.asUInt -> AluOp.Add,
    AluIsuCtrl.PcImm.asUInt   -> AluOp.Add,
    AluIsuCtrl.Slt.asUInt     -> AluOp.Add,
    AluIsuCtrl.Sltu.asUInt    -> AluOp.Add,
    AluIsuCtrl.SltImm.asUInt  -> AluOp.Add,
    AluIsuCtrl.SltuImm.asUInt -> AluOp.Add
  ))

  io.alu.valid := io.in.valid && (fu_type === FuType.ALU)
  io.alu.bits.opA      := aluOpA
  io.alu.bits.opB      := aluOpB
  io.alu.bits.aluOp    := aluOp
  io.alu.bits.rd_index := io.in.bits.rd_index

  Seq((io.bru, FuType.BRU), (io.lsu, FuType.LSU), (io.sysu, FuType.SYSU)).foreach { case (out, t) =>
    out.valid := io.in.valid && (fu_type === t)
    out.bits := DontCare
  }
  io.in.ready := Mux1H(fuTypes.map(_ === fu_type), outs.map(_.ready))
}
