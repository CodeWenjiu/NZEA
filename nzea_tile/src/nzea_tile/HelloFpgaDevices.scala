package nzea_tile

import chisel3._
import chisel3.util.Enum
import nzea_rtl.{FabricAddrRange, FabricBusRW}

/** HelloFPGA platform address map.
  *
  * Start addresses are kept aligned with the existing Yosys map:
  * - RAM base: 0x8000_0000
  * - UART base: 0x1000_0000
  *
  * Sizes are derived from IP interface widths:
  * - RAM: addra[14:0] with 32-bit data -> 2^15 words * 4B = 128 KiB
  * - UART AXI-Lite: 16-bit address -> 64 KiB window
  */
object HelloFpgaAddressMap {
  val ram = FabricAddrRange(base = BigInt("80000000", 16), size = BigInt("00020000", 16))
  val uart = FabricAddrRange(base = BigInt("10000000", 16), size = BigInt("00010000", 16))
  val ranges: Seq[FabricAddrRange] = Seq(ram, uart)
}

/** External UART pins exposed by HelloFPGA tile integration. */
class HelloFpgaUartIo extends Bundle {
  val txd = Output(Bool())
  val rxd = Input(Bool())
  val rtsn = Output(Bool())
  val ctsn = Input(Bool())
  val interrupt = Output(Bool())
}

/** Xilinx BRAM IP wrapper (module name: `ram`). */
class HelloFpgaRamBlackBox extends ExtModule {
  override def desiredName: String = "ram"
  val io = FlatIO(new Bundle {
    val clka = Input(Clock())
    val ena = Input(Bool())
    val wea = Input(UInt(1.W))
    val addra = Input(UInt(15.W))
    val dina = Input(UInt(32.W))
    val douta = Output(UInt(32.W))
  })
}

/** AXI-Lite UART IP wrapper (module name: `axilite_uart`). */
class HelloFpgaAxiLiteUartBlackBox(
  sysClk: Int = 100000000,
  baudRate: Int = 115200,
  fifoPtrBits: Int = 4
) extends ExtModule(
      Map(
        "SYS_CLK" -> new IntParam(sysClk),
        "BAUD_RATE" -> new IntParam(baudRate),
        "FIFO_PTR_BITS" -> new IntParam(fifoPtrBits)
      )
    ) {
  override def desiredName: String = "axilite_uart"
  val io = FlatIO(new Bundle {
    val resetn = Input(Bool())
    val clock = Input(Clock())

    val s_axi_awaddr = Input(UInt(16.W))
    val s_axi_awvalid = Input(Bool())
    val s_axi_awready = Output(Bool())

    val s_axi_wdata = Input(UInt(32.W))
    val s_axi_wvalid = Input(Bool())
    val s_axi_wready = Output(Bool())

    val s_axi_bresp = Output(UInt(2.W))
    val s_axi_bvalid = Output(Bool())
    val s_axi_bready = Input(Bool())

    val s_axi_araddr = Input(UInt(16.W))
    val s_axi_arvalid = Input(Bool())
    val s_axi_arready = Output(Bool())

    val s_axi_rdata = Output(UInt(32.W))
    val s_axi_rresp = Output(UInt(2.W))
    val s_axi_rvalid = Output(Bool())
    val s_axi_rready = Input(Bool())

    val interrupt = Output(Bool())
    val TxD = Output(Bool())
    val RxD = Input(Bool())
    val RTSn = Output(Bool())
    val CTSn = Input(Bool())
  })
}

/** FabricBus slave adapter for `ram` blackbox.
  *
  * - Accepts one outstanding Fabric request.
  * - Returns response one cycle later for both read and write.
  * - Read data comes from BRAM douta; write response data is zero.
  */
class HelloFpgaRamFabricSlave(
  addrWidth: Int,
  dataWidth: Int,
  userWidth: Int,
  idWidth: Int,
  baseAddr: BigInt
) extends Module {
  require(dataWidth == 32, s"HelloFpgaRamFabricSlave expects 32-bit data, got $dataWidth")

  val io = IO(new Bundle {
    val bus = Flipped(new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth))
  })

  private val bb = Module(new HelloFpgaRamBlackBox)
  private val flush = io.bus.resp.flush
  private val writeMaskAll = ((BigInt(1) << (dataWidth / 8)) - 1).U((dataWidth / 8).W)

  val busy = RegInit(false.B)
  val respUser = RegInit(0.U(userWidth.W))
  val respId = RegInit(0.U(idWidth.W))
  val isWrite = RegInit(false.B)

  val reqReady = !busy && !flush
  val reqFire = io.bus.req.valid && reqReady

  io.bus.req.ready := reqReady
  io.bus.req.flush := false.B

  io.bus.resp.valid := busy && !flush
  io.bus.resp.bits.data := Mux(isWrite, 0.U, bb.io.douta)
  io.bus.resp.bits.user := respUser
  io.bus.resp.bits.id := respId

  val localByteAddr = io.bus.req.bits.addr - baseAddr.U(addrWidth.W)
  bb.io.clka := clock
  bb.io.ena := reqFire
  bb.io.wea := Mux(reqFire && io.bus.req.bits.wen, 1.U, 0.U)
  bb.io.addra := localByteAddr(16, 2)
  bb.io.dina := io.bus.req.bits.wdata

  when(reqFire) {
    assert(io.bus.req.bits.addr(1, 0) === 0.U, "HelloFpgaRamFabricSlave: unaligned access")
    when(io.bus.req.bits.wen) {
      assert(io.bus.req.bits.wstrb === writeMaskAll, "HelloFpgaRamFabricSlave: partial write is unsupported")
    }
    respUser := io.bus.req.bits.user
    respId := io.bus.req.bits.id
    isWrite := io.bus.req.bits.wen
    busy := true.B
  }

  when(io.bus.resp.fire || flush) {
    busy := false.B
  }
}

/** FabricBus slave adapter for `axilite_uart` blackbox.
  *
  * - Serializes Fabric requests into AXI-Lite transactions (single outstanding).
  * - Supports both reads and writes; write response data is zero.
  * - `bresp/rresp` are checked (must be OKAY).
  */
class HelloFpgaUartFabricSlave(
  addrWidth: Int,
  dataWidth: Int,
  userWidth: Int,
  idWidth: Int,
  baseAddr: BigInt
) extends Module {
  require(dataWidth == 32, s"HelloFpgaUartFabricSlave expects 32-bit data, got $dataWidth")

  val io = IO(new Bundle {
    val bus = Flipped(new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth))
    val uart = new HelloFpgaUartIo
  })

  val bb = Module(new HelloFpgaAxiLiteUartBlackBox)
  val flush = io.bus.resp.flush
  val writeMaskAll = ((BigInt(1) << (dataWidth / 8)) - 1).U((dataWidth / 8).W)

  val sIdle :: sWrIssue :: sWrRespWait :: sRdIssue :: sRdRespWait :: sRespOut :: Nil = Enum(6)
  val state = RegInit(sIdle)

  val awDone = RegInit(false.B)
  val wDone = RegInit(false.B)

  val reqAddr = RegInit(0.U(addrWidth.W))
  val reqWdata = RegInit(0.U(dataWidth.W))
  val respData = RegInit(0.U(dataWidth.W))
  val respUser = RegInit(0.U(userWidth.W))
  val respId = RegInit(0.U(idWidth.W))

  io.bus.req.ready := (state === sIdle) && !flush
  io.bus.req.flush := false.B
  val reqFire = io.bus.req.valid && io.bus.req.ready

  io.bus.resp.valid := (state === sRespOut) && !flush
  io.bus.resp.bits.data := respData
  io.bus.resp.bits.user := respUser
  io.bus.resp.bits.id := respId

  bb.io.resetn := !reset.asBool
  bb.io.clock := clock

  val localReqAddr = reqAddr - baseAddr.U(addrWidth.W)
  bb.io.s_axi_awaddr := localReqAddr(15, 0)
  bb.io.s_axi_awvalid := (state === sWrIssue) && !awDone && !flush

  bb.io.s_axi_wdata := reqWdata
  bb.io.s_axi_wvalid := (state === sWrIssue) && !wDone && !flush

  bb.io.s_axi_bready := (state === sWrRespWait) && !flush

  bb.io.s_axi_araddr := localReqAddr(15, 0)
  bb.io.s_axi_arvalid := (state === sRdIssue) && !flush

  bb.io.s_axi_rready := (state === sRdRespWait) && !flush

  io.uart.txd := bb.io.TxD
  io.uart.rtsn := bb.io.RTSn
  io.uart.interrupt := bb.io.interrupt
  bb.io.RxD := io.uart.rxd
  bb.io.CTSn := io.uart.ctsn

  when(flush) {
    state := sIdle
    awDone := false.B
    wDone := false.B
  }.otherwise {
    when(reqFire) {
      reqAddr := io.bus.req.bits.addr
      reqWdata := io.bus.req.bits.wdata
      respUser := io.bus.req.bits.user
      respId := io.bus.req.bits.id
      when(io.bus.req.bits.wen) {
        assert(io.bus.req.bits.wstrb === writeMaskAll, "HelloFpgaUartFabricSlave: partial write is unsupported")
        awDone := false.B
        wDone := false.B
        state := sWrIssue
      }.otherwise {
        state := sRdIssue
      }
    }

    when(state === sWrIssue) {
      val awFire = !awDone && bb.io.s_axi_awready
      val wFire = !wDone && bb.io.s_axi_wready
      val awDoneNext = awDone || awFire
      val wDoneNext = wDone || wFire

      awDone := awDoneNext
      wDone := wDoneNext

      when(awDoneNext && wDoneNext) {
        state := sWrRespWait
      }
    }

    when(state === sWrRespWait && bb.io.s_axi_bvalid) {
      assert(bb.io.s_axi_bresp === 0.U, "HelloFpgaUartFabricSlave: AXI-Lite write response error")
      respData := 0.U
      state := sRespOut
    }

    when(state === sRdIssue && bb.io.s_axi_arready) {
      state := sRdRespWait
    }

    when(state === sRdRespWait && bb.io.s_axi_rvalid) {
      assert(bb.io.s_axi_rresp === 0.U, "HelloFpgaUartFabricSlave: AXI-Lite read response error")
      respData := bb.io.s_axi_rdata
      state := sRespOut
    }

    when(state === sRespOut && io.bus.resp.ready) {
      state := sIdle
    }
  }
}
