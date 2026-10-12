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

# Assemble a core-level difftest program (`nzea_core/test/asm/<name>.S`) for one ISA into an ELF under `build/asm/`.
# The run belongs to remu's own shell — the program's header carries the command.
[group('test')]
asm-elf name target='riscv32im':
    #!/usr/bin/env bash
    set -euo pipefail
    src="nzea_core/test/asm/{{ name }}.S"
    dir="build/asm"
    mkdir -p "$dir"
    out="$dir/{{ name }}-{{ target }}"
    case "{{ target }}" in
    riscv32*) triple=riscv32-unknown-none-elf; march=rv32im; abi=ilp32 ;;
    riscv64*) triple=riscv64-unknown-none-elf; march=rv64im; abi=lp64  ;;
    *) echo "asm-elf: unknown target '{{ target }}' (want riscv32* or riscv64*)" >&2; exit 1 ;;
    esac
    clang -target "$triple" -march="$march" -mabi="$abi" -mno-relax -c "$src" -o "$out.o"
    printf 'OUTPUT_ARCH(riscv)\nENTRY(_start)\nSECTIONS {\n  . = 0x80000000;\n  .text : { *(.text*) }\n  .rodata : { *(.rodata*) }\n}\n' > "$out.ld"
    rust-lld -flavor gnu -T "$out.ld" -o "$out.elf" "$out.o"
    echo "built $out.elf"

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
# The board's RTL plus its Vivado project in one step: `just dump --target fpga` and the project generator
# (edalize) that writes build_all.tcl / program.tcl into the board's work root. Positional args are board and isa;
# the ILA is part of the board design, not a knob. (`name=value` does not work here: just 1.58 passes it through
# literally.)
[group('fpga')]
fpga-dump board='lxb_artix7' isa='riscv32i':
    @just dump --target fpga --board {{ board }} --isa {{ isa }}
    @just fpga-project {{ board }} {{ isa }}

# Regenerate only the Vivado project from the RTL already dumped (no re-elaboration).
[group('fpga')]
fpga-project board='lxb_artix7' isa='riscv32i':
    @nu nzea_fpga/scripts/vivado-project.nu --board {{ board }} --isa {{ isa }}

# Build the bitstream headlessly (synth + impl, ~5 min). Requires `just fpga-dump` first: this does not regenerate RTL.
[group('fpga')]
vivado-build board='lxb_artix7' isa='riscv32i':
    @nu nzea_fpga/scripts/vivado-flow.nu --board {{ board }} --isa {{ isa }} --build-only

# Program the FPGA over JTAG headlessly (board connected, programmed with the ILA build for captures).
[group('fpga')]
vivado-program board='lxb_artix7' isa='riscv32i':
    @nu nzea_fpga/scripts/vivado-flow.nu --board {{ board }} --isa {{ isa }} --program-only

# Build then program, in sequence.
[group('fpga')]
vivado-flow board='lxb_artix7' isa='riscv32i':
    @nu nzea_fpga/scripts/vivado-flow.nu --board {{ board }} --isa {{ isa }}

# Capture an ILA window to VCD and rename its probes for pulse (board programmed with a current build).
[group('fpga')]
ila-capture board='lxb_artix7' isa='riscv32i' mode='snapshot':
    @nu nzea_fpga/scripts/ila-capture.nu --board {{ board }} --isa {{ isa }} --mode {{ mode }}

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
