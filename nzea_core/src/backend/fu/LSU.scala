package nzea_core.backend.fu

import chisel3._
import chisel3.util.{Cat, Decoupled, Fill, Mux1H}
import nzea_core.{CoreBusReadWrite, CoreReq}

/** LSU write-back payload (rd_index from commit queue). */
class LsuOut extends Bundle {
  val rd_data = UInt(32.W)
}

/** LSU op: one-hot (LB, LH, LW, LBU, LHU, SB, SH, SW). */
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

/** LSU input: addr (from ISU, rs1+imm), lsuOp (ChiselEnum), rd_index (for load), storeData (rs2 for store). */
class LsuInput extends Bundle {
  val addr      = UInt(32.W)
  val lsuOp     = LsuOp()
  val rd_index  = UInt(5.W)
  val storeData = UInt(32.W)
}

/** LSU: bus type from busGen (e.g. from Core); load -> write-back to WBU; store -> in.ready when req.ready. */
class LSU(busGen: () => CoreBusReadWrite) extends Module {
  val io = IO(new Bundle {
    val in   = Flipped(Decoupled(new LsuInput))
    val out  = Decoupled(new LsuOut)
    val bus  = busGen()
  })

  val lsuOp   = io.in.bits.lsuOp
  val isLoad  = lsuOp === LsuOp.LB || lsuOp === LsuOp.LH || lsuOp === LsuOp.LW || lsuOp === LsuOp.LBU || lsuOp === LsuOp.LHU
  val isStore = lsuOp === LsuOp.SB || lsuOp === LsuOp.SH || lsuOp === LsuOp.SW

  val addr2    = io.in.bits.addr(1, 0)
  val storeData = io.in.bits.storeData
  val sbStrb = Mux(addr2 === 0.U, "b0001".U(4.W), Mux(addr2 === 1.U, "b0010".U(4.W), Mux(addr2 === 2.U, "b0100".U(4.W), "b1000".U(4.W))))
  val shStrb = Mux(addr2(1), "b1100".U(4.W), "b0011".U(4.W))
  val swStrb = "b1111".U(4.W)
  val wstrb = Mux1H(lsuOp.asUInt, Seq(0.U(4.W), 0.U(4.W), 0.U(4.W), 0.U(4.W), 0.U(4.W), sbStrb, shStrb, swStrb))

  // Store: only fire bus when we can also pass through to WB (for ordering)
  io.bus.req.valid := io.in.valid
  io.bus.req.bits.addr  := io.in.bits.addr
  io.bus.req.bits.wdata := storeData << (addr2 * 8.U)
  io.bus.req.bits.wen   := isStore
  io.bus.req.bits.wstrb := wstrb

  val rdata   = io.bus.resp.bits
  val byteSel = Mux(addr2 === 0.U, rdata(7, 0), Mux(addr2 === 1.U, rdata(15, 8), Mux(addr2 === 2.U, rdata(23, 16), rdata(31, 24))))
  val halfSel = Mux(addr2(1), rdata(31, 16), rdata(15, 0))
  val lb  = Cat(Fill(24, byteSel(7)), byteSel)
  val lh  = Cat(Fill(16, halfSel(15)), halfSel)
  val lw  = rdata
  val lbu = Cat(0.U(24.W), byteSel)
  val lhu = Cat(0.U(16.W), halfSel)
  val loadData = Mux1H(lsuOp.asUInt, Seq(lb, lh, lw, lbu, lhu, 0.U(32.W), 0.U(32.W), 0.U(32.W)))

  val loadDone = io.in.valid && isLoad && io.bus.req.ready && io.bus.resp.valid
  io.out.valid        := io.bus.resp.valid
  io.out.bits.rd_data := loadData

  io.bus.resp.ready := io.out.ready
  io.in.ready := io.bus.req.ready
}
