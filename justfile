_default:
    @just --list

# Initialize Project
init:
    @mill mill.bsp.BSP/install

# Generate Verilog to build/<target>/<platform>/<isa>/<sim|sta>/ (default: sim=true). Use --sim false for synth-ready RTL.
dump *ARGS:
    @mill nzea_cli.run {{ ARGS }}

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

# Run all tests in one module (e.g. nzea_core / nzea_rtl)
test module="nzea_rtl":
    @mill --no-server {{module}}.test

# Run selected ScalaTest suites in one module.
# Example: just test-suites nzea_rtl LiteBusCrossbarTest LiteBusXbarTest
test-suites module *suites:
    @bash -lc 'set -euo pipefail; \
      if [ "{{suites}}" = "" ]; then \
        echo "No suites provided. Example: just test-suites nzea_rtl LiteBusCrossbarTest LiteBusXbarTest" >&2; \
        exit 1; \
      fi; \
      fq=(); \
      for s in {{suites}}; do \
        if [[ "$s" == *.* ]]; then fq+=("$s"); else fq+=("{{module}}.$s"); fi; \
      done; \
      echo "Running suites: ${fq[*]}"; \
      mill --no-server {{module}}.test.testOnly "${fq[@]}"'

# Run a suite collection matched by regex on *Test.scala filename.
# Example: just test-match nzea_rtl "LiteBus(Arbiter|Crossbar)Test"
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
# Example: just tb "LiteBus.*Test"
tb pattern:
    @just test-match nzea_rtl "{{pattern}}"
