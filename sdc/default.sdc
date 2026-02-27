# Synopsys Design Constraints for nzea
# Used by P&R tools (nextpnr, Vivado, etc.) for timing analysis
# Yosys synthesis does not use SDC; this is for downstream flow

# Create clock: 100MHz (10ns period)
create_clock -name clock -period 10.0 [get_ports clock]

# Input delay (example)
# set_input_delay -clock clock 1.0 [all_inputs]

# Output delay (example)
# set_output_delay -clock clock 1.0 [all_outputs]
