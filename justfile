_default:
    @just --list

# Initialize Project
init:
    @mill mill.bsp.BSP/install

# Generate Verilog to build/<target>/<platform>/<isa>/<sim|sta>/ (default: sim=true). Use --sim false for synth-ready RTL.
dump *ARGS:
    @mill nzea_cli.run {{ ARGS }}

# Tile (NzeaTile): same as dump with --target tile
dump-tile *ARGS:
    @mill nzea_cli.run --target tile {{ ARGS }}

# Synth only: RTL from .../<isa>/sta/, reports in .../sta/synth/ (synth_stat.txt, synth_check.txt)
synth *ARGS:
    @just dump --sim false {{ ARGS }}
    @nu scripts/synth.nu {{ ARGS }}

# Synth + STA: area + timing. Requires nix develop (iEDA, PDK_PATH)
# Reports: build/<target>/yosys/<isa>/sta/synth/ (area, timing rpt, sta.log; power report disabled in sta.tcl)
sta *ARGS:
    @just synth {{ ARGS }}
    @nu scripts/sta.nu {{ ARGS }}

# Clean ALL
clean-all: clean
    @mill mill clean

clean:
    @rm -rf build
