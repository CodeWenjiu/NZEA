package nzea_tile.platform.hellofpga

import chisel3._
import chisel3.util._
import nzea_rtl.FabricBusRW

class RamFabricSlave(
  addrWidth: Int,
  dataWidth: Int,
  userWidth: Int,
  idWidth: Int,
  baseAddr: BigInt
) extends Module {
  require(dataWidth == 32, s"RamFabricSlave expects 32-bit data, got $dataWidth")
  val io = IO(new Bundle {
    val bus = Flipped(new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth))
    val boot_wen   = Input(Bool())
    val boot_addr  = Input(UInt(15.W))
    val boot_wdata = Input(UInt(32.W))
  })

  private val depth        = 1 << 15
  private val flush        = io.bus.resp.flush
  private val writeMaskAll = ((BigInt(1) << (dataWidth / 8)) - 1).U((dataWidth / 8).W)
  val mem = SyncReadMem(depth, UInt(dataWidth.W), SyncReadMem.WriteFirst)

  val busCycle = RegInit(0.U(2.W))
  val isRead   = RegInit(false.B)
  val respUser = RegInit(0.U(userWidth.W))
  val respId   = RegInit(0.U(idWidth.W))
  val readData = RegInit(0.U(dataWidth.W))

  val reqReady = busCycle === 0.U && !flush
  val reqFire  = io.bus.req.valid && reqReady
  io.bus.req.ready := reqReady
  io.bus.req.flush := false.B
  io.bus.resp.valid      := busCycle === 3.U && !flush
  io.bus.resp.bits.data  := Mux(isRead, readData, 0.U)
  io.bus.resp.bits.user  := respUser
  io.bus.resp.bits.id    := respId

  val localByteAddr = io.bus.req.bits.addr - baseAddr.U(addrWidth.W)
  val wordAddr      = localByteAddr(16, 2)
  val wenClean = reqFire && io.bus.req.bits.wen
  val renClean = reqFire && !io.bus.req.bits.wen
  when(io.boot_wen) {
    mem.write(io.boot_addr, io.boot_wdata)
  }.elsewhen(wenClean) {
    mem.write(wordAddr, io.bus.req.bits.wdata)
  }
  val memRead = mem.read(wordAddr, renClean)
  readData := RegNext(memRead, 0.U(dataWidth.W))

  when(reqFire) {
    assert(io.bus.req.bits.addr(1, 0) === 0.U, "RamFabricSlave: unaligned access")
    when(io.bus.req.bits.wen) { assert(io.bus.req.bits.wstrb === writeMaskAll, "RamFabricSlave: partial write unsupported") }
    respUser := io.bus.req.bits.user; respId := io.bus.req.bits.id; isRead := !io.bus.req.bits.wen; busCycle := 1.U
  }
  when(busCycle === 1.U) { busCycle := 2.U }
  when(busCycle === 2.U) { busCycle := 3.U }
  when((busCycle === 3.U && io.bus.resp.ready) || flush) { busCycle := 0.U }
}
