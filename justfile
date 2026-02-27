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

# Synth + STA: 面积 + 时序. 需 nix develop (iEDA, PDK_PATH)
# 报告: build/synth/synth_stat.txt (面积), Top.rpt (时序), Top.pwr (功耗), sta.log
sta: synth
    @nu scripts/sta.nu

# Clean ALL
clean-all: clean
    @mill mill clean

clean:
    @rm -rf build
