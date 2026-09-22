package nzea_core.back

import chisel3._
import chisel3.util.{MuxCase, Valid}
import nzea_rtl.PipeIO
import nzea_core.front.{CsrType, PrfWriteBundle}
import nzea_core.back.FuCompletion

/** SYSU op: CSR ops + ECALL/EBREAK/FENCE. Decoded from funct3 like other FU ops. */
object SysuOp extends chisel3.ChiselEnum {
  val CSRRW = Value // 001
  val CSRRS = Value // 010
  val CSRRC = Value // 011
  val CSRRWI = Value // 101
  val CSRRSI = Value // 110
  val CSRRCI = Value // 111
  val ECALL = Value
  val EBREAK = Value
  val FENCE = Value
}

/** SYSU FU input: tag, pc, waddr from IS; csr_type, csr_rdata, rs1_val, sysuOp, imm (zimm for I-type). */
class SysuInput(tagWidth: Int, waddrWidth: Int) extends Bundle {
  val tag = UInt(tagWidth.W)
  val pc = UInt(32.W)
  val waddr = UInt(waddrWidth.W)
  val csr_type = CsrType()
  val csr_rdata = UInt(32.W)
  val rs1_val = UInt(32.W)
  val sysuOp = SysuOp()
  val imm = UInt(32.W)
}

/** SYSU FU: CSR read/write, ECALL/EBREAK/FENCE; writes rd to PRF and CSR side effects into ROB (CSR regs updated at
  * commit in [[nzea_core.Core]]).
  */
class SYSU(tagWidth: Int, waddrWidth: Int) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new SysuInput(tagWidth, waddrWidth)))
    val completion = Output(Valid(new FuCompletion(tagWidth)))
    val out = new PipeIO(new PrfWriteBundle(waddrWidth))
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

  val csr_wdata = MuxCase(
    0.U(32.W),
    Seq(
      (io.in.bits.sysuOp === SysuOp.CSRRW) -> io.in.bits.rs1_val,
      (io.in.bits.sysuOp === SysuOp.CSRRWI) -> io.in.bits.imm,
      (io.in.bits.sysuOp === SysuOp.CSRRS) -> (io.in.bits.csr_rdata | op_val),
      (io.in.bits.sysuOp === SysuOp.CSRRSI) -> (io.in.bits.csr_rdata | op_val),
      (io.in.bits.sysuOp === SysuOp.CSRRC) -> (io.in.bits.csr_rdata & ~op_val),
      (io.in.bits.sysuOp === SysuOp.CSRRCI) -> (io.in.bits.csr_rdata & ~op_val)
    )
  )

  val do_csr_write = io.in.bits.csr_type =/= CsrType.None && MuxCase(
    true.B,
    Seq(
      (io.in.bits.sysuOp === SysuOp.CSRRS) -> (io.in.bits.rs1_val =/= 0.U),
      (io.in.bits.sysuOp === SysuOp.CSRRC) -> (io.in.bits.rs1_val =/= 0.U),
      (io.in.bits.sysuOp === SysuOp.CSRRSI) -> (io.in.bits.imm =/= 0.U),
      (io.in.bits.sysuOp === SysuOp.CSRRCI) -> (io.in.bits.imm =/= 0.U)
    )
  )

  val u = FuCompletion.report(
    io.in.valid,
    io.in.bits.tag,
    is_done = true.B,
    next_pc = next_pc,
    csr_type = Mux(do_csr_write, io.in.bits.csr_type, CsrType.None),
    csr_data = Mux(do_csr_write, csr_wdata, 0.U)
  )(tagWidth)

  io.completion <> u
  io.in.ready := io.out.ready

  io.out.valid := u.valid
  io.out.bits.waddr := io.in.bits.waddr
  io.out.bits.data := csr_rd_val
  io.in.flush := io.out.flush
}
