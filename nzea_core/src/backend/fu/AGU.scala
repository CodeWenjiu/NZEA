package nzea_core.backend.fu

import chisel3._
import chisel3.util.{Decoupled, Mux1H}

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

/** AGU output: addr, wdata, wstrb, lsuOp for WBU/MemUnit to perform memory access. */
class AguOut extends Bundle {
  val addr   = UInt(32.W)
  val wdata  = UInt(32.W)
  val wstrb  = UInt(4.W)
  val lsuOp  = LsuOp()
}

/** AGU input: addr (from ISU, rs1+imm), lsuOp, storeData (rs2 for store). */
class AguInput extends Bundle {
  val addr      = UInt(32.W)
  val lsuOp     = LsuOp()
  val storeData = UInt(32.W)
}

/** AGU: generates addr, wdata, wstrb from input; passes to WBU. No bus. */
class AGU extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new AguInput))
    val out = Decoupled(new AguOut)
  })

  val addr2     = io.in.bits.addr(1, 0)
  val storeData = io.in.bits.storeData
  val sbStrb = Mux(addr2 === 0.U, "b0001".U(4.W), Mux(addr2 === 1.U, "b0010".U(4.W), Mux(addr2 === 2.U, "b0100".U(4.W), "b1000".U(4.W))))
  val shStrb = Mux(addr2(1), "b1100".U(4.W), "b0011".U(4.W))
  val swStrb = "b1111".U(4.W)
  val wstrb = Mux1H(io.in.bits.lsuOp.asUInt, Seq(0.U(4.W), 0.U(4.W), 0.U(4.W), 0.U(4.W), 0.U(4.W), sbStrb, shStrb, swStrb))
  val wdata = storeData << (addr2 * 8.U)

  io.out.valid        := io.in.valid
  io.out.bits.addr    := io.in.bits.addr
  io.out.bits.wdata   := wdata
  io.out.bits.wstrb   := wstrb
  io.out.bits.lsuOp   := io.in.bits.lsuOp
  io.in.ready         := io.out.ready
}
