package nzea_fpga.boards.lxb_artix7

import nzea_fpga.XdcRegistry

/** The board's own pin constraints: the configuration bank, the 50 MHz oscillator, the button and the two LEDs, with
  * the timing exceptions their domains carry.
  *
  * The locations are the vendor's: `A7_lite.xdc` beside this file is the board's full pin map, kept as the reference
  * these lines were copied from. The *names* are the design's — a constraint binds by name — and the ports bound here
  * are the level's own ([[nzea_fpga.TopIO]]).
  */
object BoardXdc:

    def register(): Unit = XdcRegistry.onEmit("board")(text)

    val text: String =
        s"""|# The lxb_artix7 board's own constraints: the level's own pins, and the timing exceptions their domains carry.
           |set_property CFGBVS VCCO [current_design]
           |set_property CONFIG_VOLTAGE 3.3 [current_design]
           |
           |set_property PACKAGE_PIN J19 [get_ports CLK_50M]
           |set_property PACKAGE_PIN L18 [get_ports RESET]
           |set_property IOSTANDARD LVCMOS33 [get_ports CLK_50M]
           |set_property IOSTANDARD LVCMOS33 [get_ports RESET]
           |
           |# The oscillator is the design's one primary clock; the MMCM's outputs are derived from it by the IP, which constrains
           |# them itself.
           |create_clock -period 20.000 -name CLK_50M [get_ports CLK_50M]
           |
           |set_property PACKAGE_PIN M18 [get_ports LED1]
           |set_property PACKAGE_PIN N18 [get_ports LED2]
           |set_property IOSTANDARD LVCMOS33 [get_ports LED1]
           |set_property IOSTANDARD LVCMOS33 [get_ports LED2]
           |
           |# Two facts about this design that the tools cannot read off the netlist; neither changes its behavior — the
           |# simulator is what holds the design to that.
           |#
           |# 1. The button is an asynchronous input: every domain's own synchronizer is what makes its release belong to that
           |#    domain (`nzea_rtl`'s `Resets`), so checking the raw pin against any domain would be checking the
           |#    synchronizer's own job.
           |set_false_path -from [get_ports RESET]
           |#
           |# 2. The oscillator's domain in this design only *observes*: the debug core samples the other domains through it (read
           |#    for "is it alive", not for their value at a cycle), and everything the 50 MHz domain drives it drives inside
           |#    itself, so one direction is enough.
           |set_false_path -to [get_clocks -quiet {CLK_50M}]
           |""".stripMargin
