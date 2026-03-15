package nzea_core.backend

import chisel3._
import chisel3.util.{MuxCase, Valid}
import nzea_core.{PipeIO, PipeIOConsumer}
import nzea_core.frontend.{CsrType, CsrWriteBundle, PrfWriteBundle}
import nzea_core.retire.rob.Rob

/** SYSU op: CSR ops + ECALL/EBREAK/FENCE. Decoded from funct3 like other FU ops. */
object SysuOp extends chisel3.ChiselEnum {
  val CSRRW  = Value  // 001
  val CSRRS  = Value  // 010
  val CSRRC  = Value  // 011
  val CSRRWI = Value  // 101
  val CSRRSI = Value  // 110
  val CSRRCI = Value  // 111
  val ECALL  = Value
  val EBREAK = Value
  val FENCE  = Value
}

/** SYSU FU input: rob_id, pc, p_rd from IS; csr_type, csr_rdata, rs1_val, sysuOp, imm (zimm for I-type). */
class SysuInput(robIdWidth: Int, prfAddrWidth: Int) extends Bundle {
  val rob_id    = UInt(robIdWidth.W)
  val pc        = UInt(32.W)
  val p_rd      = UInt(prfAddrWidth.W)
  val csr_type  = CsrType()
  val csr_rdata = UInt(32.W)
  val rs1_val   = UInt(32.W)
  val sysuOp    = SysuOp()
  val imm       = UInt(32.W)
}

/** SYSU FU: CSR read/write, ECALL/EBREAK/FENCE; writes result to Rob and PRF. */
class SYSU(robIdWidth: Int, prfAddrWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in         = new PipeIOConsumer(new SysuInput(robIdWidth, prfAddrWidth))
    val rob_access = new nzea_core.retire.rob.RobAccessIO(robIdWidth)
    val prf_write  = new PipeIO(new PrfWriteBundle(prfAddrWidth))
    val csr_write  = Output(Valid(new CsrWriteBundle))
  })
  val next_pc = io.in.bits.pc + 4.U
  // CSR read result: old value for rd
  val csr_rd_val = io.in.bits.csr_rdata

  // rs1/zimm: CSRRWI/RSI/RCI use imm (zimm), others use rs1_val
  val op_val = Mux(
    io.in.bits.sysuOp === SysuOp.CSRRWI || io.in.bits.sysuOp === SysuOp.CSRRSI || io.in.bits.sysuOp === SysuOp.CSRRCI,
    io.in.bits.imm,
    io.in.bits.rs1_val
  )
  val csr_wdata = MuxCase(0.U(32.W), Seq(
    (io.in.bits.sysuOp === SysuOp.CSRRW)  -> io.in.bits.rs1_val,
    (io.in.bits.sysuOp === SysuOp.CSRRWI) -> io.in.bits.imm,
    (io.in.bits.sysuOp === SysuOp.CSRRS)  -> (io.in.bits.csr_rdata | op_val),
    (io.in.bits.sysuOp === SysuOp.CSRRSI) -> (io.in.bits.csr_rdata | op_val),
    (io.in.bits.sysuOp === SysuOp.CSRRC)  -> (io.in.bits.csr_rdata & ~op_val),
    (io.in.bits.sysuOp === SysuOp.CSRRCI)  -> (io.in.bits.csr_rdata & ~op_val)
  ))
  val do_csr_write = io.in.bits.csr_type =/= CsrType.None && MuxCase(true.B, Seq(
    (io.in.bits.sysuOp === SysuOp.CSRRS)  -> (io.in.bits.rs1_val =/= 0.U),
    (io.in.bits.sysuOp === SysuOp.CSRRC)  -> (io.in.bits.rs1_val =/= 0.U),
    (io.in.bits.sysuOp === SysuOp.CSRRSI) -> (io.in.bits.imm =/= 0.U),
    (io.in.bits.sysuOp === SysuOp.CSRRCI)  -> (io.in.bits.imm =/= 0.U)
  ))

  val u = Rob.entryStateUpdate(
    io.in.valid, io.in.bits.rob_id, is_done = true.B, next_pc = next_pc,
    csr_type = Mux(do_csr_write, io.in.bits.csr_type, CsrType.None),
    csr_data = Mux(do_csr_write, csr_wdata, 0.U)
  )(robIdWidth)
  io.rob_access.valid := u.valid
  io.rob_access.bits := u.bits
  io.in.ready := io.prf_write.ready

  io.csr_write.valid := u.valid && do_csr_write
  io.csr_write.bits.csr_type := io.in.bits.csr_type
  io.csr_write.bits.data := csr_wdata

  io.prf_write.valid := u.valid && io.in.bits.p_rd =/= 0.U
  io.prf_write.bits.addr := io.in.bits.p_rd
  io.prf_write.bits.data := csr_rd_val
}
