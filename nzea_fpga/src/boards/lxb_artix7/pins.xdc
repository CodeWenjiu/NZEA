# Pin and clock constraints for the lxb_artix7 board, for the ports the design actually declares.
#
# The board's full pin map — HDMI, Ethernet, DDR3, UART, keys, the LEDs' neighbours — is A7_lite.xdc beside this file:
# the vendor's original, kept as reference. A constraint on a port the design does not have is an error in Vivado, so
# this file carries only what the top declares and grows as the top does; the locations are copied from the reference.
#
# Port names bind by name: the RTL's pin names are the contract (see TopIO).

# ── Configuration bank ──
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]

# ── Clock and reset ──
set_property PACKAGE_PIN J19 [get_ports CLK_50M]
set_property PACKAGE_PIN L18 [get_ports RESET]
set_property IOSTANDARD LVCMOS33 [get_ports CLK_50M]
set_property IOSTANDARD LVCMOS33 [get_ports RESET]

# The oscillator is the design's one primary clock; the MMCM's outputs are derived from it by the IP, which constrains
# them itself.
create_clock -period 20.000 -name CLK_50M [get_ports CLK_50M]

# ── LEDs (active-low: the pin sinks the current, so a zero lights the LED) ──
set_property PACKAGE_PIN M18 [get_ports LED1]
set_property PACKAGE_PIN N18 [get_ports LED2]
set_property IOSTANDARD LVCMOS33 [get_ports LED1]
set_property IOSTANDARD LVCMOS33 [get_ports LED2]
