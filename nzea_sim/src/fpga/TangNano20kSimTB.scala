package nzea_sim

import chisel3._
import chisel3.util._
import nzea_fpga.boards.tangnano20k.TangNano20kCore

/** FPGA simulation testbench: sends 1-word boot, checks readback. Output port `result` anchors the pass/fail state so
  * CIRCT won't optimize it away. 0 = running, 1 = pass, 2 = fail.
  */
class TangNano20kSimTB extends Module {

  val io = IO(new Bundle {
    val result = Output(UInt(2.W)) // anchor: 0=running, 1=pass, 2=fail
  })

  val baudDiv = 100_000_000 / 115200
  val nWords = 1
  val nBytes = 4 + 4 + 4 + nWords * 4

  val dut = Module(new TangNano20kCore(100_000_000, 115200))
  val stim = Module(new UartStimulus(baudDiv, nBytes))
  val mon = Module(new UartMonitor(baudDiv))

  dontTouch(dut.io)
  dontTouch(mon.io)

  dut.io.uart_rx := stim.io.txd
  dut.io.switch := 0.U
  mon.io.rxd := dut.io.uart_tx

  // Send buffer
  val buf = Wire(Vec(nBytes, UInt(8.W)))
  buf(0) := "h07".U; buf(1) := "hb0".U; buf(2) := "h07".U; buf(3) := "hb0".U
  buf(4) := "h00".U; buf(5) := "h00".U; buf(6) := "h00".U; buf(7) := "h00".U
  buf(8) := "h01".U; buf(9) := "h00".U; buf(10) := "h00".U; buf(11) := "h00".U
  buf(12) := "hef".U; buf(13) := "hbe".U; buf(14) := "had".U; buf(15) := "hde".U
  stim.io.bytes := buf
  stim.io.count := nBytes.U
  // Single-cycle start pulse after reset
  val startReg = RegInit(true.B)
  when(startReg) { startReg := false.B }
  stim.io.start := startReg

  // Receiver
  val recvWord = RegInit(0.U(32.W))
  val recvCnt = RegInit(0.U(4.W))
  val byteCnt = RegInit(0.U(2.W))
  val pass = RegInit(false.B)
  val fail = RegInit(false.B)

  dontTouch(pass)
  dontTouch(fail)

  when(mon.io.valid) {
    recvWord := Cat(mon.io.bits, recvWord(31, 8))
    byteCnt := byteCnt + 1.U
    when(byteCnt === 3.U) {
      when(
        recvCnt === (nWords - 1).U &&
          Cat(mon.io.bits, recvWord(31, 8)) === "hDEADBEEF".U
      ) {
        pass := true.B
      }.otherwise {
        fail := true.B
      }
      recvCnt := recvCnt + 1.U
    }
  }

  io.result := Mux(pass, 1.U, Mux(fail, 2.U, 0.U))

  when(pass) { printf(cf"PASS\n"); stop() }
  when(fail) { printf(cf"FAIL: expected 0xDEADBEEF\n"); stop() }
}

object TangNano20kSimTB {

  /** Write minimal clock/reset wrapper (SimTop) for iverilog. */
  def emitWrapper(outDir: String): Unit = {
    val w = new java.io.PrintWriter(s"$outDir/SimTop.sv")
    w.write(
      """|module SimTop;
         |  reg clk, rst_n;
         |  wire [1:0] result;
         |  reg [31:0] timeout;
         |  initial clk = 0;
         |  always #5 clk = ~clk;
         |  initial begin
         |    rst_n = 0; repeat(20) @(posedge clk); rst_n = 1;
         |    timeout = 0;
         |  end
         |  always @(posedge clk) timeout <= timeout + 1;
         |  TangNano20kSimTB dut(.clock(clk), .reset(~rst_n), .io_result(result));
         |  always @(posedge clk) if (|result) begin
         |    $display("[%0t] RESULT: result=%b", $time, result);
         |    $finish;
         |  end
         |  always @(posedge clk) if (timeout == 32'd5000000) begin
         |    $display("[%0t] TIMEOUT", $time); $finish;
         |  end
         |endmodule
         |""".stripMargin
    )
    w.close()
  }

}
