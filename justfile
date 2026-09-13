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

# Run Vivado build (synth + impl + bitstream) headlessly
[group('fpga')]
vivado-build board='lxb_artix7' isa='riscv32im':
    @nu nzea_fpga/scripts/vivado-flow.nu --board {{ board }} --isa {{ isa }} --build-only

# Program the FPGA over JTAG headlessly
[group('fpga')]
vivado-program board='lxb_artix7' isa='riscv32im':
    @nu nzea_fpga/scripts/vivado-flow.nu --board {{ board }} --isa {{ isa }} --program-only

# Full Vivado flow: build bitstream then program the board
[group('fpga')]
vivado-flow board='lxb_artix7' isa='riscv32im':
    @nu nzea_fpga/scripts/vivado-flow.nu --board {{ board }} --isa {{ isa }}

# Simulate the DDR3 adapter against the real generated MIG IP under xsim (minutes, needs Vivado).
# For Xilinx-IP protocol questions only - the fast Chisel unit tests stay in `just test`.
# --tb <NAME> selects the cosim testbench wrapper (see --tb-list); --dump writes a
# VCD of the adapter internals for pulse; --tb-args passes extra xsim arguments
# verbatim, e.g. --tb-args "-testplusarg GAP=0".
[group('fpga')]
[arg("dump", long="dump", value="--dump")]
[arg("tb", long="tb")]
[arg("tb_args", long="tb-args")]
[arg("tb_list", long="tb-list", value="--tb-list")]
vivado-sim board='lxb_artix7' isa='riscv32im' dump='' tb_args='' tb='nzea_ddr3_sim_top' tb_list='':
    @nu nzea_fpga/scripts/vivado-sim.nu --board {{ board }} --isa {{ isa }} {{ dump }} {{ tb_list }} --tb "{{ tb }}" --tb-args "{{ tb_args }}"

# Assemble an RV32IM .S test program into a tile boot hex (file mode).
# Built-in DDR test programs make references to the shared common.S helpers and
# therefore cannot be assembled standalone - use asm2hex-test for those.
[group('fpga')]
asm2hex src out:
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p asm2hex -- {{ src }} {{ out }}

# Assemble a built-in DDR test program into a tile boot hex (see asm2hex --list)
[group('fpga')]
asm2hex-test name out:
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p asm2hex -- --test {{ name }} {{ out }}

# Send hex file to tile via UART bootloader (BootFsm protocol through the probe tool)
[group('fpga')]
uart-load hex port baud='115200':
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p probe_cli -- load {{ hex }} {{ port }} --baud {{ baud }}

# Reset the tile CPU over UART (keeps RAM, restarts from the reset vector)
[group('fpga')]
uart-reset port baud='115200':
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p probe_cli -- reset {{ port }} --baud {{ baud }}

# Read one word from a tile address over UART (generated probe program);
# port empty = auto-discover the CH340 via /dev/serial/by-id
[group('fpga')]
uart-read addr port='' baud='115200':
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p probe_cli -- memread {{ addr }} {{ port }} --baud {{ baud }}

# Attach a streaming serial monitor (timestamps); --reset restarts the CPU right after attaching
[group('fpga')]
[arg("reset", long="reset", value="--reset")]
uart-monitor port baud='115200' reset='':
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p probe_cli -- monitor {{ port }} --baud {{ baud }} {{ reset }}

# Capture an ILA window to VCD (reset-edge by default), rename probes for pulse
[group('fpga')]
ila-capture mode='reset' port='/dev/serial/by-id/usb-1a86_USB_Serial-if00-port0':
    @nu nzea_fpga/scripts/ila-capture.nu --mode {{ mode }} --port {{ port }}

# ---- Testing ----

# Run all tests in a module (default: nzea_rtl)
[group('test')]
test module='nzea_rtl':
    @mill --no-server {{ module }}.test

# Run tests matching a class name pattern (e.g., "Vector.*Test" or "FabricBusCrossbarTest")
[group('test')]
test-match module pattern:
    @mill --no-server {{ module }}.test.testOnly "*{{ pattern }}*"
