_default:
    @just --list

# Install BSP metadata for editors: that is all Metals needs on this side (the extension itself is a Zed-side install).
init:
    @mill --no-server mill.bsp.BSP/install

# Elaborate RTL to Verilog. `just dump --target core --isa riscv32im [--outDir <dir>]` is what the simulators
# call, so the flags are passed through to the CLI (defaults live there); without --outDir the layout is
# build/<target>/<platform>/<isa> — for a board, build/fpga/<board>/<isa>, the directory its Vivado work root sits in.
[group('build')]
dump *ARGS:
    @mill --no-server nzea_cli.runMain nzea_cli.Main {{ ARGS }}

# Run Yosys synthesis: dumps the synthesis top (`--flow yosys`, buses exposed, no DPI bridges) and synthesizes it,
# mapping onto the PDK when PDK_PATH is set
[group('synth')]
synth *ARGS:
    @just dump --flow yosys {{ ARGS }}
    @nu synth/flow/synth.nu {{ ARGS }}

# Run STA on the synthesized netlist (requires `just synth` first; needs PDK_PATH and iEDA)
[group('synth')]
sta *ARGS:
    @just synth {{ ARGS }}
    @nu synth/flow/sta.nu {{ ARGS }}

# Assemble a module's test suite (e.g. `just test nzea_rtl`)
[group('test')]
test module='nzea_rtl':
    @mill --no-server {{ module }}.test

# Assemble a core-level difftest program (`nzea_core/test/asm/<name>.S`) for one ISA into an ELF under
# `build/asm/` (the builder is `nzea_core/test/asm/build-elf.sh`). The run belongs to remu's own shell — the
# program's header carries the command.
[group('test')]
asm-elf name target='riscv32im':
    @bash nzea_core/test/asm/build-elf.sh {{ name }} {{ target }}

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

[group('utility')]
clean:
    @rm -rf build

[group('utility')]
clean-all: clean
    @mill --no-server clean

[group('utility')]
wave path:
    @nix run --impure github:nix-community/nixGL#nixGLIntel -- surfer {{ path }}

# ---- FPGA (lxb_artix7 / Vivado) ----
# The board flow lives in its own module, beside the scripts that implement it:
# `just fpga dump|project|build|program|capture` (`just --list fpga`).
mod fpga 'nzea_fpga/justfile'

# ---- board tools (probe/) ----
# The toolbox talks to a programmed board and does not depend on the core sources. Both recipes are plain
# pass-throughs: the subcommands and their defaults live in the tools' own `--help`, not in a second copy here.

# probe_cli: `load <hex> <port>`, `reset <port>`, `memread <addr> [port]`, `monitor <port>` — `just probe --help`.
[group('probe')]
probe *ARGS:
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p probe_cli -- {{ ARGS }}

# asm2hex: assemble a built-in (`--test <name>`, `--list`) or file test program into a tile boot hex.
[group('probe')]
asm2hex *ARGS:
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p asm2hex -- {{ ARGS }}
