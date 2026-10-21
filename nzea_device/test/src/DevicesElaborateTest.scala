package nzea_device

import nzea_rtl.{RtlFlow, TestFlow}
import nzea_rtl.stdlib.SyncRamPorts
import nzea_device.devices.*
import utest.*

/** Each device elaborates through the real flow and carries the ports its platform wiring expects; in the bridge flow a
  * device answers with the simulator instead of building its hardware.
  */
object DevicesElaborateTest extends TestSuite:

    private lazy val finisher = TestFlow.lower(
        SifiveTestFinisher.moduleName(SifiveTestFinisherParameter(32, 32, 4, 1, RtlFlow.Yosys)),
        cfg =>
            SifiveTestFinisher.main(
                Array(
                    "config",
                    cfg.toString,
                    "--addrWidth",
                    "32",
                    "--dataWidth",
                    "32",
                    "--userWidth",
                    "4",
                    "--idWidth",
                    "1",
                    "--flow",
                    "yosys"
                )
            ),
        cfg => SifiveTestFinisher.main(Array("design", cfg.toString))
    )

    private lazy val clint = TestFlow.lower(
        Clint.moduleName(ClintParameter(32, 32, 4, 1, 0x02000000L, 25000000, 1000000, RtlFlow.Yosys)),
        cfg =>
            Clint.main(
                Array(
                    "config",
                    cfg.toString,
                    "--addrWidth",
                    "32",
                    "--dataWidth",
                    "32",
                    "--userWidth",
                    "4",
                    "--idWidth",
                    "1",
                    "--base",
                    "33554432",
                    "--clockHz",
                    "25000000",
                    "--timeHz",
                    "1000000",
                    "--flow",
                    "yosys"
                )
            ),
        cfg => Clint.main(Array("design", cfg.toString))
    )

    private lazy val ram = TestFlow.lower(
        RamFabricSlave.moduleName(
            RamFabricSlaveParameter(32, 32, 4, 1, 0x80000000L, 131072L, SyncRamPorts.OneRW, RtlFlow.Yosys)
        ),
        cfg =>
            RamFabricSlave.main(
                Array(
                    "config",
                    cfg.toString,
                    "--addrWidth",
                    "32",
                    "--dataWidth",
                    "32",
                    "--userWidth",
                    "4",
                    "--idWidth",
                    "1",
                    "--base",
                    "2147483648",
                    "--size",
                    "131072",
                    "--ports",
                    "1rw",
                    "--flow",
                    "yosys"
                )
            ),
        cfg => RamFabricSlave.main(Array("design", cfg.toString))
    )

    /** The RAM built for the bridge flow: its memory is the simulator's, so no hardware is built. */
    private lazy val ramBridged = TestFlow.lower(
        RamFabricSlave.moduleName(
            RamFabricSlaveParameter(32, 32, 4, 1, 0x80000000L, 131072L, SyncRamPorts.OneRW, RtlFlow.Verilator)
        ),
        cfg =>
            RamFabricSlave.main(
                Array(
                    "config",
                    cfg.toString,
                    "--addrWidth",
                    "32",
                    "--dataWidth",
                    "32",
                    "--userWidth",
                    "4",
                    "--idWidth",
                    "1",
                    "--base",
                    "2147483648",
                    "--size",
                    "131072",
                    "--ports",
                    "1rw",
                    "--flow",
                    "verilator"
                )
            ),
        cfg => RamFabricSlave.main(Array("design", cfg.toString))
    )

    private lazy val uart = TestFlow.lower(
        Uart.moduleName(UartParameter(32, 32, 4, 1, 0x10000000L, 25000000, 115200, RtlFlow.Verilator)),
        cfg =>
            Uart.main(
                Array(
                    "config",
                    cfg.toString,
                    "--addrWidth",
                    "32",
                    "--dataWidth",
                    "32",
                    "--userWidth",
                    "4",
                    "--idWidth",
                    "1",
                    "--base",
                    "268435456",
                    "--clockHz",
                    "25000000",
                    "--lineHz",
                    "115200",
                    "--flow",
                    "verilator"
                )
            ),
        cfg => Uart.main(Array("design", cfg.toString))
    )

    /** The same device in a synthesis flow: the register file and the transmitter are built instead of the bridge. */
    private lazy val uartHw = TestFlow.lower(
        Uart.moduleName(UartParameter(32, 32, 4, 1, 0x10000000L, 25000000, 115200, RtlFlow.Yosys)),
        cfg =>
            Uart.main(
                Array(
                    "config",
                    cfg.toString,
                    "--addrWidth",
                    "32",
                    "--dataWidth",
                    "32",
                    "--userWidth",
                    "4",
                    "--idWidth",
                    "1",
                    "--base",
                    "268435456",
                    "--clockHz",
                    "25000000",
                    "--lineHz",
                    "115200",
                    "--flow",
                    "yosys"
                )
            ),
        cfg => Uart.main(Array("design", cfg.toString))
    )

    private lazy val busDpi = TestFlow.lower(
        BusDpiDevice.moduleName(BusDpiDeviceParameter(32, 32, 4, 1)),
        cfg =>
            BusDpiDevice.main(
                Array(
                    "config",
                    cfg.toString,
                    "--addrWidth",
                    "32",
                    "--dataWidth",
                    "32",
                    "--userWidth",
                    "4",
                    "--idWidth",
                    "1"
                )
            ),
        cfg => BusDpiDevice.main(Array("design", cfg.toString))
    )

    private lazy val ibusDpi = TestFlow.lower(
        IbusDpiDevice.moduleName(IbusDpiDeviceParameter(32, 32, 4, 1)),
        cfg =>
            IbusDpiDevice.main(
                Array(
                    "config",
                    cfg.toString,
                    "--addrWidth",
                    "32",
                    "--dataWidth",
                    "32",
                    "--userWidth",
                    "4",
                    "--idWidth",
                    "1"
                )
            ),
        cfg => IbusDpiDevice.main(Array("design", cfg.toString))
    )

    private lazy val debugger = TestFlow.lower(
        Debugger.moduleName(DebuggerParameter(32, 5, 12, RtlFlow.Verilator)),
        cfg =>
            Debugger.main(
                Array(
                    "config",
                    cfg.toString,
                    "--width",
                    "32",
                    "--gprIndexWidth",
                    "5",
                    "--csrAddrWidth",
                    "12",
                    "--flow",
                    "verilator"
                )
            ),
        cfg => Debugger.main(Array("design", cfg.toString))
    )

    /** The blinker at full duty: the plain toggle, with no PWM window built. */
    private lazy val blink = TestFlow.lower(
        Blink.moduleName(BlinkParameter(28, 1.0)),
        cfg => Blink.main(Array("config", cfg.toString, "--width", "28", "--duty", "1")),
        cfg => Blink.main(Array("design", cfg.toString))
    )

    /** The blinker dimmed: its lit phase is gated by a counted window. */
    private lazy val blinkDim = TestFlow.lower(
        Blink.moduleName(BlinkParameter(28, 0.25)),
        cfg => Blink.main(Array("config", cfg.toString, "--width", "28", "--duty", "0.25")),
        cfg => Blink.main(Array("design", cfg.toString))
    )

    val tests = Tests:
        test("the finisher is a bus slave with a finished flag"):
            assert(finisher.contains("module SifiveTestFinisher("))
            for port <- Seq("bus_req_valid", "bus_resp_ready", "bus_req_bits_wen", "finished")
            do assert(finisher.contains(port))

        test("the CLINT is a bus slave with the timer interrupt"):
            assert(clint.contains("module Clint("))
            for port <- Seq("bus_req_valid", "bus_resp_valid", "timer_interrupt")
            do assert(clint.contains(port))

        test("the RAM is a bus slave"):
            assert(ram.contains("module RamFabricSlave("))
            for port <- Seq("bus_req_valid", "bus_resp_valid", "bus_req_bits_wstrb")
            do assert(ram.contains(port))

        test("in the bridge flow a device answers with the simulator instead of building its hardware"):
            assert(ramBridged.contains("module RamFabricSlave("))
            assert(ramBridged.contains("bus_read") && ramBridged.contains("bus_write"))
            assert(!ramBridged.contains("SyncRam"))

        test("the UART prints through hardware in a synthesis flow, and answers with the simulator in the bridge flow"):
            assert(uart.contains("module Uart("))
            assert(uart.contains("bus_req_valid"))
            assert(uart.contains("bus_read") && uart.contains("bus_write"))
            // The line leaves at the boundary, and the bit period is the rate the parameter states: 25e6 / 115200 = 217.
            assert(uartHw.contains("txd"))
            assert(!uartHw.contains("bus_read"))
            assert(uartHw.contains("8'hD9"))

        test("the bus DPI device is a bus device whose answer is the simulator's"):
            assert(busDpi.contains("module BusDpiDevice("))
            for port <- Seq("bus_req_valid", "bus_resp_valid", "bus_req_bits_wen", "bus_req_bits_wstrb")
            do assert(busDpi.contains(port))
            assert(busDpi.contains("bus_read") && busDpi.contains("bus_write"))

        test("the read-only bus DPI device answers reads only"):
            assert(ibusDpi.contains("module IbusDpiDevice("))
            assert(ibusDpi.contains("bus_read"))
            assert(!ibusDpi.contains("bus_write"))

        test("the debugger observes the commit trace and forwards it to the simulator"):
            assert(debugger.contains("module Debugger("))
            for port <- Seq(
                    "trace_valid",
                    "trace_bits_next_pc",
                    "trace_bits_csr_valid",
                    "trace_bits_csr_addr",
                    "trace_bits_rd_index"
                )
            do assert(debugger.contains(port))
            assert(debugger.contains("commit_trace"))

        test("the blinker at full duty is the plain toggle — no PWM window is built"):
            assert(blink.contains("module Blink("))
            for port <- Seq("clock", "resetN", "led", "count") do assert(blink.contains(port))
            assert(!blink.contains("window"))

        test("a duty below 1 gates the lit phase with a counted PWM window"):
            assert(blinkDim.contains("module Blink("))
            assert(blinkDim.contains("window"))
