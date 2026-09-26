package nzea_core.dpi

import chisel3._
import chisel3.util.Valid
import nzea_core.CommitMsg
import nzea_config.core.CoreConfig
import chisel3.util.circt.dpi.{RawClockedVoidFunctionCall, RawUnclockedNonVoidFunctionCall}
import nzea_rtl._

/** Bridges Core ibus to DPI-C bus_read. 2-cycle pipeline via 2x PipelineConnect. Bus channels are plain Decoupled:
  * cancellation is master-side (epoch digest), so the bridge pipeline never needs flushing — every accepted request
  * simply produces its response. (bus_read is side-effect free.)
  */
class IbusDpiBridge(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Module {

  val io = IO(new Bundle {
    val bus = Flipped(new LiteBusRO(addrWidth, dataWidth, userWidth, idWidth))
  })

  val respType = new LiteResp(dataWidth, userWidth, idWidth)
  val internalResp = Wire(new PipeIO(respType))
  val stage1 = Wire(new PipeIO(respType))
  // Internal pipeline keeps the PipeIO container (PipelineConnect), bus side is Decoupled-only.
  internalResp.flush := false.B
  stage1.flush := false.B

  io.bus.req.ready := internalResp.ready && !reset.asBool // reset refuses traffic (fabric concern, see Ifu)
  val reqFire = io.bus.req.valid && io.bus.req.ready

  val rdata = RawUnclockedNonVoidFunctionCall(
    "bus_read",
    Output(UInt(dataWidth.W)),
    Some(Seq("addr")),
    Some("rdata")
  )(reqFire, io.bus.req.bits.addr)

  internalResp.valid := reqFire
  internalResp.bits.data := rdata
  internalResp.bits.user := io.bus.req.bits.user
  internalResp.bits.id := io.bus.req.bits.id

  PipelineConnect(internalResp, stage1)
  // stage1 (PipeIO) -> bus resp (Decoupled): manual tail connection.
  io.bus.resp.valid := stage1.valid
  io.bus.resp.bits := stage1.bits
  stage1.ready := io.bus.resp.ready
}

/** Bridges Core dbus to DPI-C bus_read and bus_write. Read and write both go through 2-cycle pipeline. Store also waits
  * for resp (for fault handling). Bus channels are plain Decoupled: cancellation is master-side, the bridge pipeline
  * never needs flushing (reads are side-effect free; writes, once executed, are not un-executable and their response
  * is digested by the master if stale).
  */
class DbusDpiBridge(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Module {

  val io = IO(new Bundle {
    val bus = Flipped(new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth))
  })

  val req = io.bus.req.bits
  val isRead = !req.wen
  val respType = new LiteResp(dataWidth, userWidth, idWidth)

  val internalResp = Wire(new PipeIO(respType))
  val stage1 = Wire(new PipeIO(respType))
  val readRespOut = Wire(new PipeIO(respType))
  // Internal pipeline keeps the PipeIO container (PipelineConnect), bus side is Decoupled-only.
  internalResp.flush := false.B
  stage1.flush := false.B
  readRespOut.flush := false.B

  io.bus.req.ready := internalResp.ready && !reset.asBool // reset refuses traffic (fabric concern, see Ifu)
  val fire = io.bus.req.valid && io.bus.req.ready
  val readFire = fire && isRead
  val writeFire = fire && req.wen

  val rdata = RawUnclockedNonVoidFunctionCall(
    "bus_read",
    Output(UInt(dataWidth.W)),
    Some(Seq("addr")),
    Some("rdata")
  )(readFire, req.addr)

  RawClockedVoidFunctionCall(
    "bus_write",
    Some(Seq("addr", "wdata", "wstrb"))
  )(clock, writeFire, req.addr, req.wdata, req.wstrb.pad(32))

  internalResp.valid := fire
  internalResp.bits.data := Mux(readFire, rdata, 0.U(dataWidth.W))
  internalResp.bits.user := req.user
  internalResp.bits.id := req.id

  PipelineConnect(internalResp, stage1)
  PipelineConnect(stage1, readRespOut)
  readRespOut.ready := io.bus.resp.ready

  io.bus.resp.valid := readRespOut.valid
  io.bus.resp.bits.data := readRespOut.bits.data
  io.bus.resp.bits.user := readRespOut.bits.user
  io.bus.resp.bits.id := readRespOut.bits.id
}

/** Bridges Core commit_msg to DPI-C commit_trace. Called on each committed instruction. */
class CommitDpiBridge(implicit config: CoreConfig) extends Module {

  val io = IO(new Bundle {
    val commit_msg = Input(Valid(new CommitMsg))
  })

  val (csr_imm, csr_valid) = nzea_core.isa.CsrType.toImmValid(io.commit_msg.bits.csr_type)

  RawClockedVoidFunctionCall(
    "commit_trace",
    Some(Seq("next_pc", "csr_valid", "csr_addr", "csr_data", "gpr_addr", "gpr_data", "is_mmio"))
  )(
    clock,
    io.commit_msg.valid,
    io.commit_msg.bits.next_pc,
    csr_valid,
    csr_imm.pad(32),
    io.commit_msg.bits.csr_data,
    io.commit_msg.bits.rd_index.pad(32),
    io.commit_msg.bits.rd_value,
    io.commit_msg.bits.is_mmio
  )

}
