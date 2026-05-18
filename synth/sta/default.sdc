# Nzea clock constraint. Nzea Top uses "clock" port.
set CLK_PORT_NAME clock
if {[info exists env(CLK_PORT_NAME)]} {
  set CLK_PORT_NAME $::env(CLK_PORT_NAME)
}

set CLK_FREQ_MHZ 500
if {[info exists env(CLK_FREQ_MHZ)]} {
  set CLK_FREQ_MHZ $::env(CLK_FREQ_MHZ)
}

set clk_port [get_ports $CLK_PORT_NAME]
create_clock -name core_clock -period [expr 1000.0 / $CLK_FREQ_MHZ] $clk_port
