package nzea_core.backend.fu

import chisel3._
import chisel3.util.{Mux1H}
import nzea_core.PipeIO
import nzea_core.backend.{Rob, RobState}

/** LsuOp: one-hot (LB, LH, LW, LBU, LHU, SB, SH, SW). Kept for decode/AGU. */
object LsuOp extends chisel3.ChiselEnum {
  val LB  = Value((1 << 0).U)
  val LH  = Value((1 << 1).U)
  val LW  = Value((1 << 2).U)
  val LBU = Value((1 << 3).U)
  val LHU = Value((1 << 4).U)
  val SB  = Value((1 << 5).U)
  val SH  = Value((1 << 6).U)
  val SW  = Value((1 << 7).U)
}

/** MemUnit request: addr, wdata, wstrb, lsuOp, pred_next_pc, rob_id. */
class AguMemReq(robIdWidth: Int) extends Bundle {
  val addr         = UInt(32.W)
  val wdata        = UInt(32.W)
  val wstrb        = UInt(4.W)
  val lsuOp        = LsuOp()
  val pred_next_pc = UInt(32.W)
  val rob_id       = UInt(robIdWidth.W)
}

/** AGU input: base, imm, lsuOp, storeData; rob_id, pred_next_pc from IS. robIdWidth from upper level. */
class AguInput(robIdWidth: Int) extends Bundle {
  val base         = UInt(32.W)
  val imm          = UInt(32.W)
  val lsuOp        = LsuOp()
  val storeData    = UInt(32.W)
  val rob_id       = UInt(robIdWidth.W)
  val pred_next_pc = UInt(32.W)
}

/** AGU: computes addr; writes WaitingForMem to Rob; sends mem_req to MemUnit. */
class AGU(robIdWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new AguInput(robIdWidth)))
    val rob_access = new nzea_core.backend.RobAccessIO(robIdWidth)
  })

  val addr      = io.in.bits.base + io.in.bits.imm
  val addr2     = addr(1, 0)
  val storeData = io.in.bits.storeData
  val sbStrb = Mux(addr2 === 0.U, "b0001".U(4.W), Mux(addr2 === 1.U, "b0010".U(4.W), Mux(addr2 === 2.U, "b0100".U(4.W), "b1000".U(4.W))))
  val shStrb = Mux(addr2(1), "b1100".U(4.W), "b0011".U(4.W))
  val swStrb = "b1111".U(4.W)
  val wstrb = Mux1H(io.in.bits.lsuOp.asUInt, Seq(0.U(4.W), 0.U(4.W), 0.U(4.W), 0.U(4.W), 0.U(4.W), sbStrb, shStrb, swStrb))
  val wdata = storeData << (addr2 * 8.U)

  val u = Rob.entryStateUpdate(
    io.in.valid, io.in.bits.rob_id, RobState.WaitingForMem, 0.U(32.W),
    mem_addr = addr, mem_wdata = wdata, mem_wstrb = wstrb,
    mem_lsuOp = io.in.bits.lsuOp, mem_pred_next_pc = io.in.bits.pred_next_pc)(robIdWidth)
  io.rob_access.valid := u.valid
  io.rob_access.bits := u.bits
  io.in.ready := true.B
  io.in.flush := io.rob_access.flush
}
