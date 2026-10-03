package nzea_device

import nzea_rtl.{RtlFlow, TestFlow}
import nzea_device.clint.*
import nzea_device.dpi.*
import nzea_device.finisher.*
import nzea_device.ram.*
import nzea_device.uart.*
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
        Clint.moduleName(ClintParameter(32, 32, 4, 1, 0x02000000L, RtlFlow.Yosys)),
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
                    "--flow",
                    "yosys"
                )
            ),
        cfg => Clint.main(Array("design", cfg.toString))
    )

    private lazy val ram = TestFlow.lower(
        RamFabricSlave.moduleName(RamFabricSlaveParameter(32, 32, 4, 1, 0x80000000L, RtlFlow.Yosys)),
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
                    "--flow",
                    "yosys"
                )
            ),
        cfg => RamFabricSlave.main(Array("design", cfg.toString))
    )

    /** The RAM built for the bridge flow: its memory is the simulator's, so no hardware is built. */
    private lazy val ramBridged = TestFlow.lower(
        RamFabricSlave.moduleName(RamFabricSlaveParameter(32, 32, 4, 1, 0x80000000L, RtlFlow.Verilator)),
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
                    "--flow",
                    "verilator"
                )
            ),
        cfg => RamFabricSlave.main(Array("design", cfg.toString))
    )

    private lazy val uart = TestFlow.lower(
        Uart.moduleName(UartParameter(32, 32, 4, 1, 0x10000000L, RtlFlow.Verilator)),
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
                    "--flow",
                    "verilator"
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
        Debugger.moduleName(DebuggerParameter(32, 5, 12)),
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
                    "12"
                )
            ),
        cfg => Debugger.main(Array("design", cfg.toString))
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

        test("the UART's only answer so far is the bridge flow's"):
            assert(uart.contains("module Uart("))
            assert(uart.contains("bus_req_valid"))
            assert(uart.contains("bus_read") && uart.contains("bus_write"))

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
