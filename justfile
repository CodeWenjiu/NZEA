_default:
    @just --list

# Install BSP metadata for editors
init:
    @mill --no-server mill.bsp.BSP/install

# Run a module's test suite (e.g. `just test nzea_rtl`)
[group('test')]
test module='nzea_rtl':
    @mill --no-server {{ module }}.test

# Run tests matching a class name pattern (e.g., "PrefixAdderTest")
[group('test')]
test-match module pattern:
    @mill --no-server {{ module }}.test.testOnly "*{{ pattern }}*"

# Re-run whenever ./.zaozi changes. Uses this repo's dev shell (zaozi's own shell also realizes
# the mtf-locked Maven repository, which we do not need); JAVA_TOOL_OPTIONS=-Xss32m because
# scalac recurses through the 95K-line jextract'd CAPI.java.
# Publish the ./.zaozi submodule fork into the local ivy repo (`me.jiuyang::*`).
[group('zaozi')]
zaozi-publish:
    @nix develop --command bash -c 'cd .zaozi && JAVA_TOOL_OPTIONS=-Xss32m mill --no-server mlirlib.publishLocal && mill --no-server circtlib.publishLocal && mill --no-server zaozi.publishLocal && mill --no-server decoder.publishLocal && mill --no-server stdlib.publishLocal && mill --no-server zaozi-compiler-plugin.publishLocal'

# Each generator writes its own .mlirbc, so `firld` links them into one circuit before firtool;
# the counter's Verilog body comes from the flow, not from firtool.
# Toolchain spike (temporary): elaborate, link, lower and simulate the POC into build/poc.
[group('zaozi')]
poc:
    @nix develop --command bash -c 'set -e; rm -rf build/poc; mkdir -p build/poc; \
      mill --no-server nzea_rtl.runMain nzea_rtl.PocEmit build/poc 8; \
      ZAOZI_OUTDIR=build/poc mill --no-server nzea_rtl.runMain nzea_rtl.Poc config build/poc/poc.json --width 8 --depth 4; \
      ZAOZI_OUTDIR=build/poc mill --no-server nzea_rtl.runMain nzea_rtl.Poc design build/poc/poc.json; \
      cd build/poc; \
      firld *.mlirbc --base-circuit Poc_w8_d4 --no-mangle -o design.mlir; \
      firtool design.mlir -o Poc_w8_d4.sv --lowering-options=locationInfoStyle=wrapInAtSquareBracket,disallowLocalVariables,disallowPackedArrays,noAlwaysComb -disable-all-randomization -strip-debug-info; \
      iverilog -g2012 -o tb.vvp ../../nzea_rtl/sim/poc/tb.sv Poc_w8_d4.sv PocCounter_w8.sv; \
      vvp tb.vvp'

[group('utility')]
clean:
    @rm -rf build

[group('utility')]
clean-all: clean
    @mill --no-server clean

# ---- legacy oracle ----
# Runs `git worktree add .oracle legacy-baseline`; the worktree has its own flake, see legacy/README.md.
# Bring up the frozen pre-migration Chisel core in .oracle/.
[group('oracle')]
legacy-oracle:
    @git worktree add .oracle legacy-baseline
    @echo "oracle ready — run: cd .oracle && nix develop"

[group('oracle')]
legacy-oracle-remove:
    @git worktree remove --force .oracle

# Compare one file against its frozen predecessor while porting it, e.g.
#   just legacy-diff nzea_rtl/src/bus/LiteBus.scala
[group('oracle')]
legacy-diff path:
    @if [ -f "{{ path }}" ]; then git diff --no-index "legacy/{{ path }}" "{{ path }}" || true; else echo "not ported yet — frozen copy: legacy/{{ path }}"; fi

# ---- board tools (probe/) ----
# These talk to a programmed board and do not depend on the core sources.

# Assemble an RV32IM .S test program into a tile boot hex (file mode).
# Built-in DDR test programs make references to the shared common.S helpers and
# therefore cannot be assembled standalone - use asm2hex-test for those.
[group('probe')]
asm2hex src out:
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p asm2hex -- {{ src }} {{ out }}

# Assemble a built-in DDR test program into a tile boot hex (see asm2hex --list)
[group('probe')]
asm2hex-test name out:
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p asm2hex -- --test {{ name }} {{ out }}

# Send hex file to tile via UART bootloader (BootFsm protocol through the probe tool)
[group('probe')]
uart-load hex port baud='115200':
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p probe_cli -- load {{ hex }} {{ port }} --baud {{ baud }}

# Reset the tile CPU over UART (keeps RAM, restarts from the reset vector)
[group('probe')]
uart-reset port baud='115200':
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p probe_cli -- reset {{ port }} --baud {{ baud }}

# Read one word from a tile address over UART (generated probe program);
# port empty = auto-discover the CH340 via /dev/serial/by-id
[group('probe')]
uart-read addr port='' baud='115200':
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p probe_cli -- memread {{ addr }} {{ port }} --baud {{ baud }}

# Attach a streaming serial monitor (timestamps); --reset restarts the CPU right after attaching
[group('probe')]
[arg("reset", long="reset", value="--reset")]
uart-monitor port baud='115200' reset='':
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p probe_cli -- monitor {{ port }} --baud {{ baud }} {{ reset }}
