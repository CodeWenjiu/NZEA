package nzea_core.dpi

import chisel3._
import chisel3.util.Valid
import nzea_config.NzeaConfig
import chisel3.util.circt.dpi.{RawClockedVoidFunctionCall, RawUnclockedNonVoidFunctionCall}
import nzea_core._

/** Bridges Core ibus to DPI-C bus_read. 2-cycle pipeline via 2x PipelineConnect.
  * Flush clears in-flight; req.flush/resp.flush propagated from bus.flush. */
class IbusDpiBridge(addrWidth: Int, dataWidth: Int, userWidth: Int = 0) extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new CoreBusReadOnly(addrWidth, dataWidth, userWidth))
  })
  val flush = io.bus.flush
  io.bus.req.flush := flush

  val respType = new CoreResp(dataWidth, userWidth)
  val internalResp = Wire(new PipeIO(respType))
  val stage1 = Wire(new PipeIO(respType))
  stage1.flush := flush

  io.bus.req.ready := internalResp.ready
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

  PipelineConnect(internalResp, stage1)
  PipelineConnect(stage1, io.bus.resp)
}

/** Bridges Core dbus to DPI-C bus_read and bus_write. Read and write both go through 2-cycle pipeline.
  * Store also waits for resp (for fault handling). Flush clears in-flight. */
class DbusDpiBridge(addrWidth: Int, dataWidth: Int, userWidth: Int = 0) extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new CoreBusReadWrite(addrWidth, dataWidth, userWidth))
  })
  val flush = io.bus.flush
  io.bus.req.flush := flush

  val req = io.bus.req.bits
  val isRead = !req.wen
  val respType = new CoreResp(dataWidth, userWidth)

  val internalResp = Wire(new PipeIO(respType))
  val stage1 = Wire(new PipeIO(respType))
  val readRespOut = Wire(new PipeIO(respType))
  stage1.flush := flush
  readRespOut.flush := flush

  io.bus.req.ready := internalResp.ready
  val fire = io.bus.req.valid && io.bus.req.ready
  val readFire  = fire && isRead
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

  PipelineConnect(internalResp, stage1)
  PipelineConnect(stage1, readRespOut)
  readRespOut.ready := io.bus.resp.ready

  io.bus.resp.valid := readRespOut.valid
  io.bus.resp.bits.data := readRespOut.bits.data
  io.bus.resp.bits.user := readRespOut.bits.user
}

/** Bridges Core commit_msg to DPI-C commit_trace. Called on each committed instruction. */
class CommitDpiBridge(implicit config: NzeaConfig) extends Module {
  val io = IO(new Bundle {
    val commit_msg = Input(Valid(new retire.CommitMsg(config.prfAddrWidth)))
  })

  RawClockedVoidFunctionCall(
    "commit_trace",
    Some(Seq("next_pc", "gpr_addr", "gpr_data", "mem_count", "is_load"))
  )(clock, io.commit_msg.valid, io.commit_msg.bits.next_pc, io.commit_msg.bits.rd_index.pad(32), io.commit_msg.bits.rd_value, io.commit_msg.bits.mem_count, io.commit_msg.bits.is_load)
}
