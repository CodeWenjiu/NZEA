_default:
    @just --list

# Install BSP metadata for editors
init:
    @mill --no-server mill.bsp.BSP/install

# Elaborate Chisel → Verilog
dump *ARGS:
    @mill --no-server nzea_cli.run {{ ARGS }}

# 4-state iverilog simulation
iv platform isa hex='iverilog_tb/hello.hex' boot='dir' wave='0':
    @nu iverilog_tb/scripts/iv-build.nu {{ platform }} {{ isa }} {{ hex }}
    @[ "{{ wave }}" = "1" ] && echo "Waveform: build/tile/{{ platform }}/{{ isa }}/hw/iverilog/tb.fst" || true
    @nu iverilog_tb/scripts/iv-run.nu {{ platform }} {{ isa }} {{ hex }} {{ boot }} {{ wave }}

# Run yosys synthesis
[group('synth')]
synth *ARGS:
    @just dump --sim false {{ ARGS }}
    @nu synth/flow/synth.nu {{ ARGS }}

# Run STA (requires synthesis first)
[group('synth')]
sta *ARGS:
    @just synth {{ ARGS }}
    @nu synth/flow/sta.nu {{ ARGS }}

[group('utility')]
clean:
    @rm -rf build

[group('utility')]
clean-all: clean
    @mill mill clean

# ---- FPGA (Tang Nano 20K) ----

fpga_dev := "GW2AR-LV18QN88C8/I7"
fpga_fam := "GW2A-18C"
fpga_board := "tangnano20k"
fpga_top := "top"
fpga_rtl := "fpga/rtl/" + fpga_top + ".v"
fpga_cst := "fpga/constraints/tangnano20k.cst"
fpga_build := "build/fpga"

[group('fpga')]
pack:
    @nu fpga/scripts/pack.nu {{ fpga_rtl }} {{ fpga_build }} {{ fpga_top }} {{ fpga_dev }} {{ fpga_fam }} {{ fpga_cst }}

[group('fpga')]
report:
    @nu fpga/scripts/report.nu {{ fpga_build }} {{ fpga_top }}

[group('fpga')]
prog: pack
    @openFPGALoader -b {{ fpga_board }} --verify {{ fpga_build }}/{{ fpga_top }}.fs

[group('fpga')]
flash: pack
    @openFPGALoader -b {{ fpga_board }} -f --verify {{ fpga_build }}/{{ fpga_top }}.fs
