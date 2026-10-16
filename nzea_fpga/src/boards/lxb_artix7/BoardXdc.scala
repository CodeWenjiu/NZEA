package nzea_fpga.boards.lxb_artix7

import nzea_fpga.XdcRegistry

/** The board's own pin constraints: the configuration bank, the 50 MHz oscillator, the button and the two LEDs, with
  * the timing exceptions their domains carry — what a build of this level needs before the DDR3 bus, which has its own
  * file beside the bus that declares its pads ([[nzea_fpga.device.ddr3.Ddr3Xdc]]), is even a question.
  *
  * The locations are the vendor's: `A7_lite.xdc` beside this file is the board's full pin map (HDMI, Ethernet, UART,
  * the keys, the bus), kept as the reference these lines were copied from. The *names* are the design's — a constraint
  * binds by name — and the ports bound here are the level's own ([[nzea_fpga.TopIO]]).
  *
  * The exceptions are two facts of [[nzea_fpga.Top]]'s own pins: the button is asynchronous (every domain's
  * synchronizer is what makes its release belong to that domain), and the oscillator's domain only observes. Nothing
  * here names a clock the design asked for: a grid-by-grid exception would be a place for a future path to hide, and
  * the crossings that needed them are gone with the shared reset level they were about (see the file's own text below).
  */
object BoardXdc:

    /** Register the level's own constraints with the dump — called where the pads are declared ([[nzea_fpga.Top]]). */
    def register(): Unit = XdcRegistry.onEmit("board")(text)

    /** The file's text, as the dump writes it. */
    val text: String =
        s"""|# The lxb_artix7 board's own constraints: the configuration bank, the 50 MHz oscillator, the button and the LEDs —
           |# the pins this level declares for itself — and the timing exceptions their domains carry.
           |
           |# ── Configuration bank ──
           |set_property CFGBVS VCCO [current_design]
           |set_property CONFIG_VOLTAGE 3.3 [current_design]
           |
           |# ── Clock and reset ──
           |set_property PACKAGE_PIN J19 [get_ports CLK_50M]
           |set_property PACKAGE_PIN L18 [get_ports RESET]
           |set_property IOSTANDARD LVCMOS33 [get_ports CLK_50M]
           |set_property IOSTANDARD LVCMOS33 [get_ports RESET]
           |
           |# The oscillator is the design's one primary clock; the MMCM's outputs are derived from it by the IP, which constrains
           |# them itself.
           |create_clock -period 20.000 -name CLK_50M [get_ports CLK_50M]
           |
           |# ── LEDs (active-low: the pin sinks the current, so a zero lights the LED) ──
           |set_property PACKAGE_PIN M18 [get_ports LED1]
           |set_property PACKAGE_PIN N18 [get_ports LED2]
           |set_property IOSTANDARD LVCMOS33 [get_ports LED1]
           |set_property IOSTANDARD LVCMOS33 [get_ports LED2]
           |
           |# ── Timing exceptions ──
           |#
           |# Two facts about this design that the tools cannot read off the netlist. Each one is stated here rather than worked
           |# around in the RTL, and neither changes the design's behavior — the simulator is what holds the design to that.
           |#
           |# 1. The button is an asynchronous input: every domain's own synchronizer is what makes its release belong to that
           |#    domain (`nzea_rtl`'s `Resets`: a reason is the producer's own level, and the consumer is what takes it), so
           |#    checking the raw pin against any domain would be checking the synchronizer's own job.
           |set_false_path -from [get_ports RESET]
           |#
           |# 2. The oscillator's domain in this design only *observes*: the debug core samples the other domains through it (read
           |#    for "is it alive", not for their value at a cycle), and one direction is enough — everything the 50 MHz domain
           |#    drives it drives inside itself.
           |set_false_path -to [get_clocks -quiet {CLK_50M}]
           |#
           |# And that is all of them. Three exceptions used to name the wizard's grids here (`CLK_50M` -> the taps grid, the
           |# loads grid, the 100 MHz): while one domain cleaned the button's level and handed it to the others, the oscillator's
           |# domain was the source of a reset level the other domains caught, and each of those paths had to be excused one grid
           |# at a time. Every domain now waits for the *pin* and synchronizes it itself, so that crossing's only launch point is
           |# the port — exception 1 — and a grid-by-grid exception would only be a place for a later path to hide.
           |""".stripMargin
