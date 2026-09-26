package nzea_core.back.mem

import chisel3._
import chisel3.util.Mux1H
import nzea_core.{CoreChannels, CoreTagWidths}
import nzea_core.back.ExecPathPort
import nzea_core.isa.{CsrType, FuDecode, LsuOp}
import nzea_rtl.PrefixAdder
import nzea_config.core.CoreConfig

/** Memory execution path: one bus request per uop (held until accepted, killed by flush), tagged so a stale response
  * cannot fire the next uop's completion. The request keeper, the tag counter and the response match live here; the
  * merging point only sees the [[ExecPathPort]].
  */
class MemPath(width: Int, mmioRanges: Seq[(BigInt, BigInt)] = Seq.empty)(implicit config: CoreConfig) extends Module {
  private val tagWidth = CoreTagWidths.lsuTag
  private val waddrWidth = CoreTagWidths.regIndex

  val io = IO(new Bundle {
    val path = new ExecPathPort(width)
    val dbus = CoreChannels.dbus(width)
  })

  private val uopValid = io.path.issue.valid
  private val u = io.path.issue.bits
  private val flushing = io.path.flush

  private val lsuOp = LsuOp.safe(FuDecode.take(u.fu_op, LsuOp.getWidth))._1
  private val isLoad = LsuOp.isLoad(lsuOp)
  private val mem = Module(new MemUnit(width, tagWidth, waddrWidth, mmioRanges))
  io.dbus <> mem.io.dbus

  private val addr = PrefixAdder(u.rs1, u.imm)
  private val addr2 = addr(1, 0)

  private val sbStrb = Mux(
    addr2 === 0.U,
    "b0001".U(4.W),
    Mux(addr2 === 1.U, "b0010".U(4.W), Mux(addr2 === 2.U, "b0100".U, "b1000".U(4.W)))
  )

  private val shStrb = Mux(addr2(1), "b1100".U(4.W), "b0011".U(4.W))
  private val swStrb = "b1111".U(4.W)

  private val wstrb = Mux1H(
    lsuOp.asUInt,
    Seq(0.U(4.W), 0.U(4.W), 0.U(4.W), 0.U(4.W), 0.U(4.W), sbStrb, shStrb, swStrb)
  )

  private val wdata = u.rs2 << (addr2 * 8.U)

  private val uopEdge = uopValid && !RegNext(uopValid, false.B)
  private val memReqValid = RegInit(false.B)

  when(flushing) { memReqValid := false.B }.otherwise {
    when(memReqValid && mem.io.mem_req_ready) { memReqValid := false.B }
      .elsewhen(uopEdge) { memReqValid := true.B }
  }

  // Request tag (riding the tag echo): a stale response must not fire the next uop's completion.
  private val lsuTag = RegInit(0.U(tagWidth.W))
  private val curLsuTag = RegInit(0.U(tagWidth.W))
  when(uopEdge) {
    curLsuTag := lsuTag
    lsuTag := lsuTag + 1.U
  }
  private val respTagMatch = mem.io.completion.bits.tag === curLsuTag

  mem.io.mem_req.valid := memReqValid
  mem.io.mem_req.bits.addr := addr
  mem.io.mem_req.bits.wdata := wdata
  mem.io.mem_req.bits.wstrb := wstrb
  mem.io.mem_req.bits.lsuOp := lsuOp
  mem.io.mem_req.bits.tag := curLsuTag
  mem.io.mem_req.bits.waddr := 0.U
  mem.io.out.ready := true.B
  mem.io.out.flush := flushing

  private val loadDone = uopValid && isLoad && mem.io.out.valid && respTagMatch
  private val storeDone = uopValid && !isLoad && mem.io.completion.valid && respTagMatch

  io.path.done := loadDone || storeDone
  io.path.result.data := Mux(isLoad, mem.io.out.bits.data, 0.U(32.W))
  io.path.result.next_pc := u.pc + 4.U
  io.path.result.csr_type := CsrType.None
  io.path.result.csr_data := 0.U(32.W)
  io.path.result.is_mmio := mem.io.completion.bits.is_mmio

  io.path.redirect.valid := false.B
  io.path.redirect.bits := 0.U
}