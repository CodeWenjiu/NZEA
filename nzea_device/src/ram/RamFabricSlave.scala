package nzea_device.ram

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
    val boot_wen = Input(Bool())
    val boot_addr = Input(UInt(15.W))
    val boot_wdata = Input(UInt(32.W))
  })

  private val depth = 1 << 15
  private val flush = io.bus.resp.flush

  val busCycle = RegInit(0.U(2.W))
  val isRead = RegInit(false.B)
  val respUser = RegInit(0.U(userWidth.W))
  val respId = RegInit(0.U(idWidth.W))
  val readData = RegInit(0.U(dataWidth.W))

  val reqReady = busCycle === 0.U && !flush
  val reqFire = io.bus.req.valid && reqReady
  io.bus.req.ready := reqReady
  io.bus.req.flush := false.B
  io.bus.resp.valid := busCycle === 3.U && !flush
  io.bus.resp.bits.data := Mux(isRead, readData, 0.U)
  io.bus.resp.bits.user := respUser
  io.bus.resp.bits.id := respId

  val localByteAddr = io.bus.req.bits.addr - baseAddr.U(addrWidth.W)
  val wordAddr = localByteAddr(16, 2)
  val wenClean = reqFire && io.bus.req.bits.wen
  val renClean = reqFire && !io.bus.req.bits.wen
  val wstrb = io.bus.req.bits.wstrb

  val memBytes = Seq.tabulate(4)(_ => SyncReadMem(depth, UInt(8.W), SyncReadMem.WriteFirst))

  val wrAddr = Mux(io.boot_wen, io.boot_addr, wordAddr)
  val wrEn = io.boot_wen || wenClean

  for (i <- 0 until 4) {
    val wrData = Mux(io.boot_wen, io.boot_wdata(8 * i + 7, 8 * i), io.bus.req.bits.wdata(8 * i + 7, 8 * i))
    val wrMask = io.boot_wen || wstrb(i)
    when(wrEn && wrMask) { memBytes(i).write(wrAddr, wrData) }
  }

  val rdataBytes = VecInit.tabulate(4)(i => memBytes(i).read(wordAddr, renClean))
  readData := RegNext(Cat(rdataBytes(3), rdataBytes(2), rdataBytes(1), rdataBytes(0)), 0.U(dataWidth.W))

  when(reqFire) {
    assert(io.bus.req.bits.addr(1, 0) === 0.U, "RamFabricSlave: unaligned access")
    respUser := io.bus.req.bits.user; respId := io.bus.req.bits.id; isRead := !io.bus.req.bits.wen; busCycle := 1.U
  }

  when(busCycle === 1.U) { busCycle := 2.U }
  when(busCycle === 2.U) { busCycle := 3.U }
  when((busCycle === 3.U && io.bus.resp.ready) || flush) { busCycle := 0.U }
}
