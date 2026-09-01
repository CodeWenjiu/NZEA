# Xilinx ILA IP creation for the lxb_artix7 board.
#
# Sourced by edalize into the Vivado project script (via tclSource file_type).
# The ILA is instantiated in RTL as `u_ila_0` (IlaProbes blackbox). Probe
# widths below must match IlaProbes.widths (see IlaProbes.scala); update both
# together when adding a probe.
#
# NOTE: the CONFIG properties below are Xilinx-specific and version-sensitive;
# they live here (not in edalize) because edalize deliberately does not model
# vendor IP configuration.

# ── ILA IP ──
create_ip -name ila -vendor xilinx.com -library ip -module_name u_ila_0
set_property -dict [list \
  CONFIG.C_DATA_DEPTH 4096 \
  CONFIG.C_NUM_OF_PROBES 11 \
  CONFIG.C_PROBE0_WIDTH 1 \
  CONFIG.C_PROBE1_WIDTH 1 \
  CONFIG.C_PROBE2_WIDTH 1 \
  CONFIG.C_PROBE3_WIDTH 1 \
  CONFIG.C_PROBE4_WIDTH 1 \
  CONFIG.C_PROBE5_WIDTH 3 \
  CONFIG.C_PROBE6_WIDTH 29 \
  CONFIG.C_PROBE7_WIDTH 32 \
  CONFIG.C_PROBE8_WIDTH 4 \
  CONFIG.C_PROBE9_WIDTH 1 \
  CONFIG.C_PROBE10_WIDTH 5 \
] [get_ips u_ila_0]
generate_target all [get_ips u_ila_0]
