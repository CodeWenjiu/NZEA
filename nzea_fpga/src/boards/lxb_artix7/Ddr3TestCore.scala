package nzea_fpga.boards.lxb_artix7

import chisel3._
import chisel3.util._
import nzea_device.ddr3.{Ddr3Adapter, Ddr3PhysIo, Ddr3Subsystem}

/** DDR3 validation — write+read+compare at 5 addresses, one cycle delay between write and read. */
class Ddr3TestCore(addrW: Int, dataW: Int, userW: Int, idW: Int) extends Module {

  val io = IO(new Bundle {
    val ddr3 = new Ddr3PhysIo
    val rst_n = Input(Bool())
    val pass = Output(Bool())
    val fail = Output(Bool())
    val testBits = Output(UInt(5.W))
    val dbg_fsm = Output(UInt(4.W))
    val dbg_calib_done = Output(Bool())
    val dbg_app_rdy = Output(Bool())
    val dbg_app_wdf_rdy = Output(Bool())
    val dbg_app_rd_data_valid = Output(Bool())
    val dbg_app_en = Output(Bool())
    val dbg_app_cmd = Output(UInt(3.W))
    val dbg_app_addr = Output(UInt(29.W))
    val dbg_rd_data = Output(UInt(32.W))
  })

  val adapter = Module(new Ddr3Adapter(addrW, dataW, userW, idW))
  val ddr3Sub = Module(new Ddr3Subsystem)
  ddr3Sub.io.ddr3.clk_200m := io.ddr3.clk_200m
  ddr3Sub.io.rst_n := io.rst_n
  adapter.io.mig <> ddr3Sub.io.mig

  io.ddr3.dq <> ddr3Sub.io.ddr3.dq
  io.ddr3.dqs_p <> ddr3Sub.io.ddr3.dqs_p
  io.ddr3.dqs_n <> ddr3Sub.io.ddr3.dqs_n
  io.ddr3.addr := ddr3Sub.io.ddr3.addr
  io.ddr3.ba := ddr3Sub.io.ddr3.ba
  io.ddr3.ras_n := ddr3Sub.io.ddr3.ras_n
  io.ddr3.cas_n := ddr3Sub.io.ddr3.cas_n
  io.ddr3.we_n := ddr3Sub.io.ddr3.we_n
  io.ddr3.ck_p := ddr3Sub.io.ddr3.ck_p
  io.ddr3.ck_n := ddr3Sub.io.ddr3.ck_n
  io.ddr3.cke := ddr3Sub.io.ddr3.cke
  io.ddr3.odt := ddr3Sub.io.ddr3.odt
  io.ddr3.reset_n := ddr3Sub.io.ddr3.reset_n
  io.ddr3.dm := ddr3Sub.io.ddr3.dm

  val addrs = VecInit(Seq(0x00, 0x04, 0x10, 0x100, 0x1000).map(_.U(addrW.W)))

  val datas = VecInit(
    Seq(
      0xcafebabeL.U(dataW.W),
      0xdeadbeefL.U(dataW.W),
      0x12345678L.U(dataW.W),
      0xa5a5a5a5L.U(dataW.W),
      0xffffffffL.U(dataW.W)
    )
  )

  val nTests = addrs.size

  val idx = RegInit(0.U(log2Ceil(nTests).W))
  val testBitsReg = RegInit(0.U(nTests.W))
  val done = RegInit(false.B)

  // FSM: wait_calib → write → ack_write → delay → read → ack_read → next
  val sIdle :: sWr :: sWrAck :: sDelay :: sRd :: sRdAck :: sNext :: sDone :: Nil = Enum(8)
  val state = RegInit(sIdle)
  val delayCnt = RegInit(0.U(4.W))

  io.pass := done && testBitsReg.andR
  io.fail := done && !testBitsReg.andR
  io.testBits := testBitsReg

  // Defaults
  adapter.io.bus.req.valid := false.B
  adapter.io.bus.req.bits.addr := addrs(idx)
  adapter.io.bus.req.bits.wdata := datas(idx)
  adapter.io.bus.req.bits.wen := true.B
  adapter.io.bus.req.bits.wstrb := 0xf.U
  adapter.io.bus.req.bits.user := 0.U
  adapter.io.bus.req.bits.id := 0.U
  adapter.io.bus.resp.flush := false.B
  adapter.io.bus.resp.ready := false.B
  adapter.io.boot.valid := false.B
  adapter.io.boot.bits.addr := 0.U
  adapter.io.boot.bits.wdata := 0.U

  switch(state) {
    is(sIdle) {
      when(ddr3Sub.io.mig.calib_done) { state := sWr }
    }
    is(sWr) {
      adapter.io.bus.req.valid := true.B
      when(adapter.io.bus.req.ready) { state := sWrAck }
    }
    is(sWrAck) {
      adapter.io.bus.resp.ready := true.B
      when(adapter.io.bus.resp.valid) { state := sDelay; delayCnt := 0.U }
    }
    is(sDelay) {
      delayCnt := delayCnt + 1.U
      when(delayCnt === 15.U) { state := sRd }
    }
    is(sRd) {
      adapter.io.bus.req.valid := true.B
      adapter.io.bus.req.bits.wen := false.B
      when(adapter.io.bus.req.ready) { state := sRdAck }
    }
    is(sRdAck) {
      adapter.io.bus.resp.ready := true.B
      when(adapter.io.bus.resp.valid) { state := sNext }
    }
    is(sNext) {
      testBitsReg := testBitsReg | Mux(
        adapter.io.bus.resp.bits.data === datas(idx),
        1.U << idx,
        0.U
      )
      when(idx === (nTests - 1).U) {
        state := sDone
      }.otherwise {
        idx := idx + 1.U
        state := sWr
      }
    }
    is(sDone) {
      done := true.B
    }
  }

  io.dbg_fsm := state
  io.dbg_calib_done := ddr3Sub.io.mig.calib_done
  io.dbg_app_rdy := ddr3Sub.io.mig.app_rdy
  io.dbg_app_wdf_rdy := ddr3Sub.io.mig.app_wdf_rdy
  io.dbg_app_rd_data_valid := ddr3Sub.io.mig.app_rd_data_valid
  io.dbg_app_en := adapter.io.mig.app_en
  io.dbg_app_cmd := adapter.io.mig.app_cmd
  io.dbg_app_addr := adapter.io.mig.app_addr
  io.dbg_rd_data := adapter.io.bus.resp.bits.data
}
