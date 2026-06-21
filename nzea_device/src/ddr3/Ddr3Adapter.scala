package nzea_device.ddr3

import chisel3._
import chisel3.util._
import nzea_rtl.{BootReq, FabricBusRW}

/** Bridges FabricBusRW + BootReq to MIG native UI (128-bit, 100 MHz).
  *
  * Boot writes have priority. Bus reads and writes share bandwidth. Width adaptation: 32-bit FabricBus → 128-bit DDR
  * (lower 32 bits used).
  */
class Ddr3Adapter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int
) extends Module {
  require(dataWidth == 32)

  val io = IO(new Bundle {
    val bus = Flipped(new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth))
    val boot = Flipped(Valid(new BootReq(15)))
    // MIG native UI (128-bit)
    val app_addr = Output(UInt(29.W))
    val app_cmd = Output(UInt(3.W))
    val app_en = Output(Bool())
    val app_rdy = Input(Bool())
    val app_wdf_data = Output(UInt(128.W))
    val app_wdf_wren = Output(Bool())
    val app_wdf_end = Output(Bool())
    val app_wdf_rdy = Input(Bool())
    val app_wdf_mask = Output(UInt(16.W))
    val app_rd_data = Input(UInt(128.W))
    val app_rd_data_valid = Input(Bool())
    val calib_done = Input(Bool())
  })

  // ── Fixed outputs ──
  io.app_wdf_mask := 0xffff.U
  io.app_wdf_end := true.B

  // ── FSM ──
  val sIdle :: sBoot :: sBusCmd :: sBusWait :: Nil = Enum(4)
  val state = RegInit(sIdle)

  val bootAddr = RegInit(0.U(15.W))
  val bootWdata = RegInit(0.U(32.W))
  val busAddr = RegInit(0.U(addrWidth.W))
  val busWdata = RegInit(0.U(dataWidth.W))
  val busIsRead = RegInit(false.B)
  val busUser = RegInit(0.U(userWidth.W))
  val busId = RegInit(0.U(idWidth.W))

  val migReady = io.app_rdy && io.app_wdf_rdy
  val busFlush = io.bus.resp.flush

  // ── Defaults ──
  io.bus.req.ready := false.B
  io.bus.resp.valid := false.B
  io.bus.resp.bits.data := 0.U
  io.bus.resp.bits.user := 0.U
  io.bus.resp.bits.id := 0.U
  io.bus.req.flush := false.B

  io.app_en := false.B
  io.app_cmd := "b000".U
  io.app_addr := 0.U
  io.app_wdf_wren := false.B
  io.app_wdf_data := 0.U

  switch(state) {
    is(sIdle) {
      when(io.calib_done && !busFlush) {
        when(io.boot.valid) {
          bootAddr := io.boot.bits.addr
          bootWdata := io.boot.bits.wdata
          state := sBoot
        }.elsewhen(io.bus.req.valid) {
          busAddr := io.bus.req.bits.addr
          busWdata := io.bus.req.bits.wdata
          busIsRead := !io.bus.req.bits.wen
          busUser := io.bus.req.bits.user
          busId := io.bus.req.bits.id
          state := sBusCmd
        }
      }
    }
    is(sBoot) {
      io.app_en := migReady
      io.app_cmd := "b000".U
      io.app_addr := Cat(bootAddr(12, 0), 0.U(3.W))
      io.app_wdf_wren := migReady
      io.app_wdf_data := Cat(0.U(96.W), bootWdata)
      when(migReady) { state := sIdle }
    }
    is(sBusCmd) {
      when(io.app_rdy && (busIsRead || io.app_wdf_rdy)) {
        io.app_en := true.B
        io.app_cmd := Mux(busIsRead, "b001".U, "b000".U)
        io.app_addr := busAddr(28, 3)
        io.bus.req.ready := true.B
        when(busIsRead) {
          state := sBusWait
        }.otherwise {
          io.app_wdf_wren := true.B
          io.app_wdf_data := Cat(0.U(96.W), busWdata)
          state := sIdle
        }
      }
    }
    is(sBusWait) {
      when(io.app_rd_data_valid) {
        io.bus.resp.valid := true.B
        io.bus.resp.bits.data := io.app_rd_data(31, 0)
        io.bus.resp.bits.user := busUser
        io.bus.resp.bits.id := busId
        state := sIdle
      }
    }
  }

  // Write ack: respond same cycle as cmd accept
  when(state === sBusCmd && io.app_rdy && !busIsRead && io.app_wdf_rdy) {
    io.bus.resp.valid := true.B
    io.bus.resp.bits.user := busUser
    io.bus.resp.bits.id := busId
  }

}
