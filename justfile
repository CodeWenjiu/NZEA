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

# ---- FPGA ----

fpga_dev := "GW2AR-LV18QN88C8/I7"

[group('fpga')]
pack:
    @nu fpga/scripts/pack.nu --dev {{ fpga_dev }}

[group('fpga')]
report:
    @nu fpga/scripts/report.nu build/fpga top

[group('fpga')]
prog: pack
    @nu fpga/scripts/prog.nu --dev {{ fpga_dev }}

[group('fpga')]
flash: pack
    @nu fpga/scripts/prog.nu --dev {{ fpga_dev }} --flash

# Generate tile RTL for FPGA (--platform --isa --sim false --clock-hz ...)
dump-tile platform isa clock_hz='100000000':
    @nix develop --command bash -c 'mill --no-server nzea_cli.run --target tile --platform {{ platform }} --isa {{ isa }} --sim false --clockHz {{ clock_hz }}'

# Generate Vivado project from tile RTL
[group('fpga')]
vivado-project dev=fpga_dev:
    @nu fpga/scripts/vivado-project.nu --dev {{ dev }}

# Send hex file to tile via UART bootloader
[group('fpga')]
uart-load hex port baud='100000':
    @nix develop --command bash -c 'cd fpga/tools && uv run uart-load.py ../../{{ hex }} {{ port }} --baud {{ baud }}'
