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

# Run all tests in one module (e.g. nzea_core / nzea_rtl)
test module="nzea_rtl":
    @mill --no-server {{module}}.test

# Run selected ScalaTest suites in one module.
# Example: just test-suites nzea_rtl FabricBusCrossbarTest FabricBusAdapterTest
test-suites module *suites:
    @bash -lc 'set -euo pipefail; \
      if [ "{{suites}}" = "" ]; then \
        echo "No suites provided. Example: just test-suites nzea_rtl FabricBusCrossbarTest FabricBusAdapterTest" >&2; \
        exit 1; \
      fi; \
      fq=(); \
      for s in {{suites}}; do \
        if [[ "$s" == *.* ]]; then fq+=("$s"); else fq+=("{{module}}.$s"); fi; \
      done; \
      echo "Running suites: ${fq[*]}"; \
      mill --no-server {{module}}.test.testOnly "${fq[@]}"'

# Run a suite collection matched by regex on *Test.scala filename.
# Example: just test-match nzea_rtl "FabricBus.*Test"
test-match module pattern:
    @bash -lc 'set -euo pipefail; \
      mapfile -t suites < <( \
        rg --files "{{module}}/test/src" -g "*Test.scala" \
          | xargs -r -n1 basename \
          | sed "s/\\.scala$//" \
          | rg "{{pattern}}" \
          | sort -u \
      ); \
      if [ "${#suites[@]}" -eq 0 ]; then \
        echo "No suite matched pattern: {{pattern}}" >&2; \
        exit 1; \
      fi; \
      fq=(); \
      for s in "${suites[@]}"; do fq+=("{{module}}.$s"); done; \
      echo "Matched ${#fq[@]} suites: ${fq[*]}"; \
      mill --no-server {{module}}.test.testOnly "${fq[@]}"'

# Convenience alias for RTL test benches.
# Example: just tb "FabricBus.*Test"
tb pattern:
    @just test-match nzea_rtl "{{pattern}}"

# ---- 4-state simulation with iverilog ----

# Run 4-state simulation (build + run). Requires iverilog in PATH (nix develop).
# Fixed target=tile. Example: just iv platform=hellofpga isa=riscv32i
iv platform isa:
    @just iv-build {{platform}} {{isa}}
    @just iv-run {{platform}} {{isa}}

# Compile testbench + RTL with iverilog.
# Output: build/tile/<platform>/<isa>/hw/iverilog/tb.vvp
iv-build platform isa:
    @bash -c 'p="{{platform}}"; i="{{isa}}"; p="${p#*=}"; i="${i#*=}"; rtl="build/tile/$p/$i/hw"; if [ ! -f "$rtl/filelist.f" ]; then echo "RTL not found, generating..." && just dump --target tile --platform "$p" --isa "$i" --sim false; fi; mkdir -p "$rtl/iverilog"; cp iverilog_tb/*.hex "$rtl/iverilog/"; echo "Compiling with iverilog..." && iverilog -g2012 -Wall -Wno-timescale -o "$rtl/iverilog/tb.vvp" iverilog_tb/*.sv "$rtl"/*.sv'

# Run compiled iverilog simulation.
iv-run platform isa:
    @bash -c 'p="{{platform}}"; i="{{isa}}"; p="${p#*=}"; i="${i#*=}"; cd "build/tile/$p/$i/hw/iverilog" && vvp tb.vvp && echo "Waveform: build/tile/$p/$i/hw/iverilog/tb.fst"'

