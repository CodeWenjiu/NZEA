package nzea_sim

import chisel3._
import chisel3.util._
import nzea_config.SynthPlatform
import nzea_core.config.CoreConfig
import nzea_tile.TileElaborate

/** Tile simulation testbench: UART boot + commit tracking + UART monitor. Replaces tb.sv. `io.result` anchors the
  * module to prevent CIRCT dead-code elimination.
  */
class TileSimTB(implicit config: CoreConfig) extends Module {

  val baudDiv = 100_000_000 / 100000 // matches DUT UartTx
  val platform = SynthPlatform.Fpga

  // Hardcoded test program: writes 'H' to UART, triggers finisher, loops
  val prog = Seq[BigInt](
    BigInt("100002b7", 16), // lui  x5, 0x10000   (UART base)
    BigInt("04800313", 16), // addi x6, x0, 72    ('H')
    BigInt("0062a023", 16), // sw   x6, 0(x5)     (write UART)
    BigInt("200002b7", 16), // lui  x5, 0x20000   (finisher base)
    BigInt("0002a023", 16), // sw   x0, 0(x5)     (trigger finisher)
    BigInt("0000006f", 16) // j    .              (loop)
  )

  val nWords = prog.length
  val nBytes = 12 + nWords * 4

  // ---- IO ----
  val io = IO(new Bundle {
    val result = Output(UInt(2.W)) // anchor: 0=running, 1=pass, 2=fail
  })

  dontTouch(io)

  // ---- DUT ----
  val dut = Module(new TileElaborate.Top(sim = false, platform = platform, clockHz = 100_000_000))
  dontTouch(dut.io)

  // ---- UART stimulus (send prog via BootFsm protocol) ----
  val stim = Module(new UartStimulus(baudDiv, nBytes))
  dontTouch(stim.io)

  val buf = Wire(Vec(nBytes, UInt(8.W)))
  // Magic: 0xB007B007 (byte-reversed: 07 B0 07 B0)
  buf(0) := "h07".U; buf(1) := "hb0".U; buf(2) := "h07".U; buf(3) := "hb0".U
  // Address: 0x00000000
  buf(4) := "h00".U; buf(5) := "h00".U; buf(6) := "h00".U; buf(7) := "h00".U
  // Size
  val sizeU = nWords.U(32.W)
  buf(8) := sizeU(7, 0); buf(9) := sizeU(15, 8)
  buf(10) := sizeU(23, 16); buf(11) := sizeU(31, 24)

  // Data words (byte-reversed)
  for (i <- 0 until nWords) {
    val w = prog(i).U(32.W)
    buf(12 + i * 4 + 0) := w(7, 0)
    buf(12 + i * 4 + 1) := w(15, 8)
    buf(12 + i * 4 + 2) := w(23, 16)
    buf(12 + i * 4 + 3) := w(31, 24)
  }

  stim.io.bytes := buf
  stim.io.count := nBytes.U

  // Start pulse: one cycle at cycle 100
  val startCnt = RegInit(0.U(16.W))
  val startReg = RegInit(false.B)
  startCnt := startCnt + 1.U

  when(startCnt === 100.U) {
    startReg := true.B
  }.otherwise {
    startReg := false.B
  }

  stim.io.start := startReg

  // ---- Connections ----
  dut.io.uart_rxd := stim.io.txd

  // ---- UART monitor ----
  val mon = Module(new UartRxDisplay(baudDiv))
  dontTouch(mon.io)
  mon.io.rxd := dut.io.uart_txd

  // ---- Commit tracker ----
  val tracker = Module(new CommitTracker(maxCycles = 50000000))
  dontTouch(tracker.io)

  tracker.io.commitMsgValid := dut.io.commit_msg.valid
  tracker.io.commitMsgNextPC := dut.io.commit_msg.bits.next_pc
  tracker.io.commitMsgRdIndex := dut.io.commit_msg.bits.rd_index
  tracker.io.commitMsgRdValue := dut.io.commit_msg.bits.rd_value
  tracker.io.finishPassed := dut.io.finish

  io.result := tracker.io.result

}

object TileSimTB {

  /** Emit minimal clock/reset wrapper (SimTop) for iverilog. */
  def emitWrapper(outDir: String, platform: String, isa: String): Unit = {
    val w = new java.io.PrintWriter(s"$outDir/SimTop.sv")
    w.write(s"""|module SimTop;
                |  reg clk, rst_n;
                |  wire [1:0] result;
                |  reg [31:0] timeout;
                |
                |  initial clk = 0;
                |  always #5 clk = ~clk;
                |
                |  initial begin
                |    if ($$test$$plusargs("WAVE")) begin
                |      $$dumpfile("tb.fst");
                |      $$dumpvars(0, SimTop);
                |    end
                |    rst_n = 0; repeat(20) @(posedge clk); rst_n = 1;
                |    timeout = 0;
                |  end
                |
                |  always @(posedge clk) timeout <= timeout + 1;
                |
                |  TileSimTB dut (.clock(clk), .reset(~rst_n), .io_result(result));
                |  always @(posedge clk) if (|result) begin
                |    $$display("[%0t] RESULT: result=%b (%s)", $$time, result,
                |      result == 2'b01 ? "PASS" : "FAIL");
                |    $$display("Waveform: build/sim/tile/$platform/$isa/hw/iverilog/tb.fst");
                |    $$finish;
                |  end
                |
                |  always @(posedge clk) if (timeout == 32'd50000000) begin
                |    $$display("[%0t] TIMEOUT", $$time);
                |    $$finish;
                |  end
                |endmodule
                |""".stripMargin)
    w.close()
  }

}
