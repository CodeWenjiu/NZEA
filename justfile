_default:
    @just --list

# Initialize Project
init:
    @mill mill.bsp.BSP/install

# Generate Verilog to build/<target>/<platform>/<isa> (default target=core). Use --synthPlatform yosys for synthesis.
dump *ARGS:
    @mill nzea_cli.run {{ ARGS }}

# Tile (NzeaTile): same as dump with --target tile
dump-tile *ARGS:
    @mill nzea_cli.run --target tile {{ ARGS }}

# Synth only: build/<target>/yosys/<isa>/synth/synth_stat.txt (area), synth_check.txt
synth *ARGS:
    @just dump --synthPlatform yosys {{ ARGS }}
    @nu scripts/synth.nu {{ ARGS }}

# Synth + STA: area + timing. Requires nix develop (iEDA, PDK_PATH)
# Reports: build/<target>/yosys/<isa>/synth/ (area, timing rpt, sta.log; power report disabled in sta.tcl)
sta *ARGS:
    @just synth {{ ARGS }}
    @nu scripts/sta.nu {{ ARGS }}

# Clean ALL
clean-all: clean
    @mill mill clean

clean:
    @rm -rf build
