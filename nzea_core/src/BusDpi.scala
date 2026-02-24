package nzea_core

import chisel3._
import chisel3.util.circt.dpi.{RawClockedVoidFunctionCall, RawUnclockedNonVoidFunctionCall}

/** Bridges Core ibus to DPI-C bus_read. Same-cycle response when possible; req.ready := !resp_pending to avoid combinational cycle. */
class IbusDpiBridge(addrWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new CoreBusReadOnly(addrWidth, dataWidth))
  })
  val resp_pending = RegInit(false.B)
  val resp_bits    = Reg(UInt(dataWidth.W))

  io.bus.req.ready := !resp_pending
  val fire = io.bus.req.valid && io.bus.req.ready

  val rdata = RawUnclockedNonVoidFunctionCall(
    "bus_read",
    Output(UInt(dataWidth.W)),
    Some(Seq("addr")),
    Some("rdata")
  )(fire, io.bus.req.bits)

  when(fire) {
    when(io.bus.resp.ready) {
      // Same-cycle delivery
    }.otherwise {
      resp_pending := true.B
      resp_bits    := rdata
    }
  }
  when(io.bus.resp.fire) { resp_pending := false.B }

  io.bus.resp.valid := (fire && io.bus.resp.ready) || resp_pending
  io.bus.resp.bits  := Mux(resp_pending, resp_bits, rdata)
}

/** Bridges Core dbus to DPI-C bus_read and bus_write. Read: same-cycle when core takes resp; else one-slot buffer. Write: always accept. */
class DbusDpiBridge(addrWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new CoreBusReadWrite(addrWidth, dataWidth))
  })
  val req = io.bus.req.bits
  val resp_pending = RegInit(false.B)
  val resp_bits    = Reg(UInt(dataWidth.W))

  io.bus.req.ready := Mux(req.wen, true.B, !resp_pending)
  val readFire  = io.bus.req.valid && !req.wen && io.bus.req.ready
  val writeFire = io.bus.req.valid && req.wen

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

  when(readFire) {
    when(io.bus.resp.ready) {
      // Same-cycle delivery
    }.otherwise {
      resp_pending := true.B
      resp_bits    := rdata
    }
  }
  when(io.bus.resp.fire) { resp_pending := false.B }

  io.bus.resp.valid := (readFire && io.bus.resp.ready) || resp_pending
  io.bus.resp.bits  := Mux(resp_pending, resp_bits, rdata)
}
