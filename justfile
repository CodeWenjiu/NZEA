_default:
    @just --list

# Initialize Project
init:
    @mill --no-server mill.bsp.BSP/install

# Generate Verilog to build/<target>/<platform>/<isa>/<dpi|hw>/ (default: sim=true→dpi). Use --sim false for hw RTL.
dump *ARGS:
    @mill --no-server nzea_cli.run {{ ARGS }}

# Generate Verilog for tile target (convenience alias for dump --target tile).
dump-tile *ARGS:
    @just dump --target tile {{ ARGS }}

# Synth only: RTL from .../<platform>/<isa>/hw/, reports in .../hw/synth/ (synth_stat.txt, synth_check.txt)
synth *ARGS:
    @just dump --sim false {{ ARGS }}
    @nu synth/flow/synth.nu {{ ARGS }}

# Synth + STA: area + timing. Requires nix develop (iEDA, PDK_PATH)
# Reports: build/<target>/<platform>/<isa>/hw/synth/ (area, timing rpt, sta.log; power report disabled in sta.tcl)
sta *ARGS:
    @just synth {{ ARGS }}
    @nu synth/flow/sta.nu {{ ARGS }}

# Clean ALL
clean-all: clean
    @mill mill clean

clean:
    @rm -rf build

# ---- 4-state simulation with iverilog ----

# Run 4-state simulation (build + run). Requires iverilog in PATH (nix develop).
# Fixed target=tile. Example: just iv platform=hellofpga isa=riscv32i
iv platform isa hex='hello.hex' boot='dir' wave='0':
    @nu iverilog_tb/scripts/iv-build.nu {{ platform }} {{ isa }}
    @nu iverilog_tb/scripts/iv-run.nu {{ platform }} {{ isa }} {{ hex }} {{ boot }} {{ wave }}
