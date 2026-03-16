package nzea_core.backend

import chisel3._
import chisel3.util.Mux1H
import nzea_core.PipeIO
import nzea_core.retire.rob.{Rob, LsWriteReq}

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

/** AGU input: base, imm, lsuOp, storeData, pc; rob_id, p_rd, lsq_id from IS. */
class AguInput(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int = 4) extends Bundle {
  val base      = UInt(32.W)
  val imm       = UInt(32.W)
  val lsuOp     = LsuOp()
  val storeData = UInt(32.W)
  val pc        = UInt(32.W)
  val rob_id    = UInt(robIdWidth.W)
  val p_rd      = UInt(prfAddrWidth.W)
  val lsq_id    = UInt(lsqIdWidth.W)
}

/** AGU: computes addr; writes to Rob; writes addr/wdata/wstrb to LS_Queue[lsq_id] via ls_write. */
class AGU(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = Flipped(new PipeIO(new AguInput(robIdWidth, prfAddrWidth, lsqIdWidth)))
    val rob_access = new nzea_core.retire.rob.RobAccessIO(robIdWidth)
    val ls_write   = new PipeIO(new LsWriteReq(lsqIdWidth))
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
  io.in.ready := true.B

  io.ls_write.valid := io.in.valid
  io.ls_write.bits.lsq_id := io.in.bits.lsq_id
  io.ls_write.bits.addr   := addr
  io.ls_write.bits.wdata  := wdata
  io.ls_write.bits.wstrb  := wstrb
  io.in.flush := io.ls_write.flush
}
