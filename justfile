_default:
    @just --list

# Install BSP metadata for editors
init:
    @mill --no-server mill.bsp.BSP/install

# Elaborate Chisel → Verilog
dump *ARGS:
    @mill --no-server nzea_cli.run {{ ARGS }}

# 4-state iverilog simulation
iv platform isa hex='nzea_tile/sim/hello.hex' boot='dir' wave='0':
    @nu nzea_tile/sim/scripts/iv-build.nu {{ platform }} {{ isa }} {{ hex }}
    @[ "{{ wave }}" = "1" ] && echo "Waveform: build/tile/{{ platform }}/{{ isa }}/hw/iverilog/tb.fst" || true
    @nu nzea_tile/sim/scripts/iv-run.nu {{ platform }} {{ isa }} {{ hex }} {{ boot }} {{ wave }}

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

# ---- FPGA ----

fpga_dev := "GW2AR-LV18QN88C8/I7"

# Build FPGA bitstream (synthesis + PnR). Regenerates RTL if Scala sources changed.
[group('fpga')]
pack dev=fpga_dev:
    @nzea_fpga/scripts/pack.nu --dev {{ dev }}

# Report resource utilization and timing
[group('fpga')]
report dev=fpga_dev:
    @nzea_fpga/scripts/report.nu --dev {{ dev }}

# Program FPGA via openFPGALoader
[group('fpga')]
prog: pack
    @nzea_fpga/scripts/prog.nu --dev {{ fpga_dev }}

# Program FPGA flash via openFPGALoader
[group('fpga')]
flash: pack
    @nzea_fpga/scripts/prog.nu --dev {{ fpga_dev }} --flash

# Generate Vivado project from FPGA RTL
[group('fpga')]
vivado-project dev=fpga_dev:
    @nzea_fpga/scripts/vivado-project.nu --dev {{ dev }}

# Send hex file to tile via UART bootloader
[group('fpga')]
uart-load hex port baud='100000':
    @nix develop --command bash -c 'cd nzea_fpga/tools && uv run uart-load.py ../../{{ hex }} {{ port }} --baud {{ baud }}'
