package nzea_core.backend

import chisel3._
import chisel3.util.Mux1H
import nzea_core.PipeIO
import nzea_core.retire.rob.{Rob, RobMemReq}

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

  def isStore(op: LsuOp.Type): Bool = op === SB || op === SH || op === SW
  def isLoad(op: LsuOp.Type): Bool  = !isStore(op)
  def isStore(op: UInt): Bool       = op === SB.asUInt || op === SH.asUInt || op === SW.asUInt
  def isLoad(op: UInt): Bool         = !isStore(op)
}

/** AGU input: base, imm, lsuOp, storeData, pc; rob_id, p_rd from IS. p_rd for load completion. */
class AguInput(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val base      = UInt(32.W)
  val imm       = UInt(32.W)
  val lsuOp     = LsuOp()
  val storeData = UInt(32.W)
  val pc        = UInt(32.W)
  val rob_id    = UInt(robIdWidth.W)
  val p_rd      = UInt(prfAddrWidth.W)
}

/** AGU: computes addr; writes need_mem to Rob; enqueues mem data to Rob's LS_Queue. */
class AGU(robIdWidth: Int, prfAddrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new AguInput(robIdWidth, prfAddrWidth)))
    val rob_access = new nzea_core.retire.rob.RobAccessIO(robIdWidth)
    val ls_enq     = chisel3.util.Decoupled(new RobMemReq(robIdWidth, prfAddrWidth))
  })

  val addr      = io.in.bits.base + io.in.bits.imm
  val addr2     = addr(1, 0)
  val storeData = io.in.bits.storeData
  val sbStrb = Mux(addr2 === 0.U, "b0001".U(4.W), Mux(addr2 === 1.U, "b0010".U(4.W), Mux(addr2 === 2.U, "b0100".U(4.W), "b1000".U(4.W))))
  val shStrb = Mux(addr2(1), "b1100".U(4.W), "b0011".U(4.W))
  val swStrb = "b1111".U(4.W)
  val wstrb = Mux1H(io.in.bits.lsuOp.asUInt, Seq(0.U(4.W), 0.U(4.W), 0.U(4.W), 0.U(4.W), 0.U(4.W), sbStrb, shStrb, swStrb))
  val wdata = storeData << (addr2 * 8.U)

  val next_pc = io.in.bits.pc + 4.U
  val u = Rob.entryStateUpdate(
    io.in.valid, io.in.bits.rob_id, false.B,
    next_pc = next_pc)(robIdWidth)
  io.rob_access.valid := u.valid
  io.rob_access.bits := u.bits
  io.in.ready := io.rob_access.ready
  io.in.flush := io.rob_access.flush

  io.ls_enq.valid := io.rob_access.valid && io.rob_access.ready
  io.ls_enq.bits.rob_id := io.in.bits.rob_id
  io.ls_enq.bits.addr   := addr
  io.ls_enq.bits.wdata  := wdata
  io.ls_enq.bits.wstrb  := wstrb
  io.ls_enq.bits.lsuOp  := io.in.bits.lsuOp
  io.ls_enq.bits.p_rd   := io.in.bits.p_rd
}
