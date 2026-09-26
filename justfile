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

# Publish the ./.zaozi submodule fork into the local ivy repo. Re-run whenever
# ./.zaozi changes; the build consumes it as me.jiuyang::zaozi.
[group('zaozi')]
zaozi-publish:
    @cd .zaozi && nix develop --command bash -c 'mill --no-server __.publishLocal'

[group('utility')]
clean:
    @rm -rf build

[group('utility')]
clean-all: clean
    @mill mill clean

# ---- legacy oracle ----
# Bring up the frozen pre-migration Chisel core in .oracle/ (tag `legacy-baseline`),
# so the old flow (`just dump` / `just iv` / `just vivado-*`) stays runnable. See
# legacy/README.md. The recipes below this line are the only legacy entry points.
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
