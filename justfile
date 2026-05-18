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
    @nu scripts/synth.nu {{ ARGS }}

# Synth + STA: area + timing. Requires nix develop (iEDA, PDK_PATH)
# Reports: build/<target>/<platform>/<isa>/hw/synth/ (area, timing rpt, sta.log; power report disabled in sta.tcl)
sta *ARGS:
    @just synth {{ ARGS }}
    @nu scripts/sta.nu {{ ARGS }}

# Clean ALL
clean-all: clean
    @mill mill clean

clean:
    @rm -rf build

# ---- 4-state simulation with iverilog ----

# Run 4-state simulation (build + run). Requires iverilog in PATH (nix develop).
# Fixed target=tile. Example: just iv platform=hellofpga isa=riscv32i
iv platform isa hex='hello.hex' boot='dir' wave='0':
    @just iv-build {{ platform }} {{ isa }}
    @just iv-run {{ platform }} {{ isa }} {{ hex }} {{ boot }} {{ wave }}

# Compile testbench + RTL with iverilog.
# Output: build/tile/<platform>/<isa>/hw/iverilog/tb.vvp
iv-build platform isa:
    @bash -c 'p="{{ platform }}"; i="{{ isa }}"; p="${p#*=}"; i="${i#*=}"; rtl="build/tile/$p/$i/hw"; if [ ! -f "$rtl/filelist.f" ]; then echo "RTL not found, generating..." && just dump --target tile --platform "$p" --isa "$i" --sim false; fi; mkdir -p "$rtl/iverilog"; cp iverilog_tb/*.hex "$rtl/iverilog/"; echo "Compiling with iverilog..." && iverilog -g2012 -Wall -Wno-timescale -o "$rtl/iverilog/tb.vvp" iverilog_tb/*.sv "$rtl"/*.sv'

# Run compiled iverilog simulation.
iv-run platform isa hex='hello.hex' boot='dir' wave='0':
    @bash -c 'p="{{ platform }}"; i="{{ isa }}"; h="{{ hex }}"; b="{{ boot }}"; w="{{ wave }}"; p="${p#*=}"; i="${i#*=}"; h="${h#*=}"; b="${b#*=}"; w="${w#*=}"; wflag=""; if [ "$w" = "1" ]; then wflag="+WAVE=1"; fi; cd "build/tile/$p/$i/hw/iverilog" && vvp tb.vvp +HEX="$h" +BOOT="$b" $wflag'
