package nzea_fpga.device.ddr3

import nzea_fpga.ip.ClockOut

/** The bus's grids, as the device's own statement: what each is, and what runs on it.
  *
  * These five are one family — the interface is the *phase relationships* between them — so they all come from one
  * generator ([[nzea_fpga.Clocks]] builds one wizard for every grid a design asks for, so a phase relationship between
  * two of them is guaranteed rather than hoped for). Who runs on what ([[Ddr3Pads]] says why each grid is where it is):
  *
  *   - [[command]] — the command/address pads' serializers, and the read capture's own clock;
  *   - [[device]] — the data's and the write strobe's serializers, and the CK's `ODDR`;
  *   - [[loads]] — the controller itself, and the device grid's serial load clock;
  *   - [[taps]] — the input delays' calibration (`IDELAYCTRL`) and the command grid's load clock;
  *   - [[capture]] — the deserializers' output register (`CLKDIVP`).
  *
  * The values are not knobs: they are the device's requirements at 400 MHz (tCK = 2500 ps), which is why they are
  * constants here and not parameters ([[README.md]] keeps their sources).
  */
object Ddr3Clocks:

    val command = ClockOut(400.0, 0.0)

    /** A quarter period after [[command]], which is exactly what the device's write capture wants between a strobe and
      * its own data (`UG586`'s `OCLK`/`OCLK_DELAYED` relationship).
      */
    val device = ClockOut(400.0, 90.0)

    /** The controller's clock, and the load clock of [[device]]'s serializers: 45° of 200 MHz is the same 625 ps the
      * 400 MHz pair is shifted by, so a serializer's load edge lands on its own shift edges.
      */
    val loads = ClockOut(200.0, 45.0)

    /** The tap calibration's reference, and the load clock of [[command]]'s serializers. */
    val taps = ClockOut(200.0, 0.0)

    /** The deserializers' output register, half a period off the 200 MHz the fabric reads it on. */
    val capture = ClockOut(200.0, 180.0)
