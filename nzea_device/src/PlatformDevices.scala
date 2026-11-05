package nzea_device

import mainargs.TokensReader
import nzea_rtl.RtlFlow
import nzea_rtl.bus.{AddrRange, SlaveKind}
import nzea_rtl.bus.given
import nzea_rtl.stdlib.SyncRamPorts

/** One device on the platform: its window and its kind, the one description the address map, the IO and the wiring all
  * derive from. The kind ([[DeviceKind]]) is what builds the device and says what it shows at the boundary, so adding a
  * device is one case there plus one entry in the list.
  */
case class DeviceSpec(name: String, range: AddrRange, kind: DeviceKind, outstanding: Int = 1)

object DeviceSpec:

    given upickle.default.ReadWriter[DeviceSpec] = upickle.default.macroRW

    /** The flag's spelling is one device's own JSON form, the same the map's serialization uses; a `Seq[DeviceSpec]`
      * flag is this repeated (`--externals <json> --externals <json>`).
      */
    given TokensReader.Simple[DeviceSpec] with
        def shortName = "device-json"

        def read(strs: Seq[String]): Either[String, DeviceSpec] =
            strs.lastOption.toRight("a device requires a value").flatMap { text =>
                scala.util.Try(upickle.default.read[DeviceSpec](text)).toEither.left.map(_.getMessage)
            }

/** The platform's hardware for a flow — the baseline inventory the core top (its MMIO windows) and the tile (its
  * devices and wiring) both read. A flow builds what it can: the bridge flow gets every window (the simulator answers
  * them all, so a device with no hardware yet — the UART — is still a window), a synthesis flow gets what has hardware.
  * A window the level above answers is not built (the tile's `built` list shadows it). The two flows diverge on
  * purpose, and the inventory is stated beside the devices rather than on `RtlFlow` so platform addresses stay out of
  * `nzea_rtl`.
  */
object PlatformDevices:

    /** The rate the platform's bridge speaks, the same number the loader's receiver is told ([[nzea_fpga.Top]]): the
      * UART transmits at it, and a program's bytes reach the host at it. Two consumers, one line.
      */
    private val LineHz = 115200

    /** The I2C wire's rate: Fast-mode. The wire is per window, so every register access to the arm, scan and motion
      * devices is framed at this bit rate ([[nzea_device.devices.I2cMaster]]).
      */
    val I2cHz: Int = 400_000

    /** The secondary bus behind the peripheral window: its device clock and the device clocks one access takes. A 25
      * MHz bus answering in one of its clocks is four tile cycles on the 100 MHz board — a slow peripheral bus, which
      * is the point of the window. The bridge flow's delay resolves the same facts (`Delay`).
      */
    val PeripheralDeviceHz: Int = 25_000_000
    val PeripheralDeviceCycles: Int = 1

    def devices(flow: RtlFlow): Seq[DeviceSpec] = flow match
        case RtlFlow.Verilator =>
            Seq(
                DeviceSpec(
                    "ram",
                    AddrRange(BigInt("80000000", 16), BigInt("08000000", 16)),
                    DeviceKind.Ram(SyncRamPorts.OneRW)
                ),
                DeviceSpec(
                    "uart16550",
                    AddrRange(BigInt("10000000", 16), BigInt("00000008", 16)),
                    DeviceKind.Uart(LineHz)
                ),
                DeviceSpec("finisher", AddrRange(BigInt("00100000", 16), BigInt("00000004", 16)), DeviceKind.Finisher),
                DeviceSpec("clint", AddrRange(BigInt("02000000", 16), BigInt("0000c000", 16)), DeviceKind.Clint),
                // The display group is plain memory behind the bus: no wire, one access at the secondary bus's pace.
                // The range covers remu's `display`/`mouse`/`keyboard` bases (0x88000000/0x88001000/0x88002000).
                DeviceSpec(
                    "display",
                    AddrRange(BigInt("88000000", 16), BigInt("00004000", 16)),
                    DeviceKind.PeripheralBridge(PeripheralDeviceHz, PeripheralDeviceCycles)
                ),
                // The motion-control devices are off-chip, each on its own I2C wire; the window is the register map
                // remu's device models answer at.
                DeviceSpec(
                    "arm",
                    AddrRange(BigInt("8A000000", 16), BigInt("00000068", 16)),
                    DeviceKind.I2c(slaveAddr = 0x10, bitHz = I2cHz)
                ),
                DeviceSpec(
                    "scan",
                    AddrRange(BigInt("8B000000", 16), BigInt("00000120", 16)),
                    DeviceKind.I2c(slaveAddr = 0x20, bitHz = I2cHz)
                ),
                DeviceSpec(
                    "motion",
                    AddrRange(BigInt("8C000000", 16), BigInt("00000070", 16)),
                    DeviceKind.I2c(slaveAddr = 0x30, bitHz = I2cHz)
                )
            )
        case RtlFlow.Yosys =>
            // The synthesis flow: what has hardware. The UART is print-only (its receive half is not ported) and the
            // RAM's window is its storage — a window worth building, not the 128 MiB the simulator models.
            Seq(
                DeviceSpec(
                    "ram",
                    AddrRange(BigInt("80000000", 16), BigInt("00020000", 16)),
                    DeviceKind.Ram(SyncRamPorts.OneRW)
                ),
                DeviceSpec(
                    "uart16550",
                    AddrRange(BigInt("10000000", 16), BigInt("00000008", 16)),
                    DeviceKind.Uart(LineHz)
                ),
                DeviceSpec("finisher", AddrRange(BigInt("00100000", 16), BigInt("00000004", 16)), DeviceKind.Finisher),
                DeviceSpec("clint", AddrRange(BigInt("02000000", 16), BigInt("0000c000", 16)), DeviceKind.Clint)
            )

    /** The windows with side effects, as the commit trace's spelling (`base:size`, hex): what a design on this platform
      * classifies as `is_mmio`.
      */
    def mmioWindows(flow: RtlFlow): Seq[String] =
        devices(flow).collect { case d if d.kind.slaveKind == SlaveKind.Mmio => d.range }.map(windowText)

    private def windowText(window: AddrRange): String =
        s"0x${window.base.toString(16)}:0x${window.size.toString(16)}"
