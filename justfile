_default:
    @just --list

# Initialize Project
init:
    @mill mill.bsp.BSP/install

# Generate Verilog (default: build/sim for simulation). Use --synthPlatform yosys for synthesis
run *ARGS:
    @mill nzea_cli.run {{ ARGS }}

# Synth only: build/synth/synth_stat.txt (area), synth_check.txt
synth:
    @just run --synthPlatform yosys
    @nu scripts/synth.nu

# Synth + STA: area + timing. Requires nix develop (iEDA, PDK_PATH)
# Reports: build/synth/synth_stat.txt (area), Top.rpt (timing), Top.pwr (power), sta.log
sta: synth
    @nu scripts/sta.nu

# Clean ALL
clean-all: clean
    @mill mill clean

clean:
    @rm -rf build
