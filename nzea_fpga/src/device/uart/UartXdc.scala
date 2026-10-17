package nzea_fpga.device.uart

import nzea_fpga.Pin
import nzea_fpga.XdcRegistry

/** The board's serial line: the pins the board's bridge chip talks to the fabric on, and their constraints.
  *
  * The line belongs to the bridge itself (a CH340 on this board), so the pin map's name is the contract: `UART_RX` is
  * the fabric's *input* — the loader's receiver listens at the pin, and the tile's UART listens through its own
  * boundary too — and `UART_TX` is the fabric's *output*, the line a program prints on.
  *
  * Registered by the level that wires the line ([[nzea_fpga.Top]]): both pins travel with the design that declares
  * them, and a line for a port the design does not have is the one thing an XDC may never carry.
  */
object UartXdc:

    /** The pin the design listens on, as the vendor's pin map names it. */
    val RxPin = "UART_RX"

    /** The pin the design drives, as the vendor's pin map names it. */
    val TxPin = "UART_TX"

    /** The pins a *logic* declares through its needs ([[nzea_fpga.Pin]]): the loader's receiver is the listener. The
      * transmit pin is this level's own — declared with [[nzea_fpga.Top.pins]], because the top is the part that wires
      * it to the tile's UART.
      */
    val pins: Seq[Pin] = Seq(Pin.in(RxPin))

    /** Register the line's constraints with the dump — said by the level that wires it ([[nzea_fpga.Top]]): a design
      * that reaches the line on neither end writes none of these.
      */
    def register(): Unit = XdcRegistry.onEmit("uart")(text)

    val text: String =
        s"""|# The board's serial line, on the pins the vendor's pin map gives it.
            |set_property PACKAGE_PIN U2 [get_ports $RxPin]
            |set_property IOSTANDARD LVCMOS33 [get_ports $RxPin]
            |set_property PACKAGE_PIN V2 [get_ports $TxPin]
            |set_property IOSTANDARD LVCMOS33 [get_ports $TxPin]
            |""".stripMargin
