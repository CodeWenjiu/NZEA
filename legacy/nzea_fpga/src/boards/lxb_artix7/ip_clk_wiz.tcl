# Xilinx IP creation for the lxb_artix7 board.
#
# Sourced by edalize into the Vivado project script (via tclSource file_type).
# The MMCM is instantiated in RTL as `clk_wiz_0` (Mmcm50to200 blackbox); the
# ILA is instantiated as `u_ila_0` (IlaProbes blackbox). Both must exist as IP
# in the project before synthesis.
#
# NOTE: the CONFIG properties below are Xilinx-specific and version-sensitive;
# they live here (not in edalize) because edalize deliberately does not model
# vendor IP configuration.

# ── Clocking Wizard IP ──
create_ip -name clk_wiz -vendor xilinx.com -library ip -module_name clk_wiz_0
set_property -dict [list \
  CONFIG.PRIMITIVE {MMCM} \
  CONFIG.PRIM_IN_FREQ {50.000} \
  CONFIG.CLKOUT1_USED {true} \
  CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {200.000} \
  CONFIG.CLKOUT2_USED {true} \
  CONFIG.CLKOUT2_REQUESTED_OUT_FREQ {100.000} \
  CONFIG.USE_LOCKED {true} \
  CONFIG.USE_RESET {true} \
] [get_ips clk_wiz_0]
generate_target all [get_ips clk_wiz_0]
