_default:
    @just --list

# Install BSP metadata for editors
init:
    @mill --no-server mill.bsp.BSP/install

# Elaborate Chisel → Verilog
dump *ARGS:
    @mill --no-server nzea_cli.run {{ ARGS }}

# 4-state iverilog simulation
iv target platform isa hex='nzea_sim/sim/tile/hello.hex' boot='dir' wave='0':
    @nu nzea_sim/scripts/iv-build.nu {{ target }} {{ platform }} {{ isa }} {{ hex }}
    @nu nzea_sim/scripts/iv-run.nu {{ target }} {{ platform }} {{ isa }} {{ hex }} {{ boot }} {{ wave }}

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
    @mkdir -p build/fpga/tangnano20k/riscv32i/hw
    @cp nzea_fpga/sim/tangnano20k/bram_1024x32.sv build/fpga/tangnano20k/riscv32i/hw/Bram1024x32.sv 2>/dev/null || true
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
uart-load hex port baud='115200':
    @cd nzea_fpga/tools && uv run uart-load.py ../../{{ hex }} {{ port }} --baud {{ baud }}

# ---- Testing ----

# Run all tests in a module (default: nzea_rtl)
[group('test')]
test module='nzea_rtl':
    @mill --no-server {{ module }}.test

# Run tests matching a class name pattern (e.g., "Vector.*Test" or "FabricBusCrossbarTest")
[group('test')]
test-match module pattern:
    @mill --no-server {{ module }}.test.testOnly "*{{ pattern }}*"
