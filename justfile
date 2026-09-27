_default:
    @just --list

# Install BSP metadata for editors
init:
    @mill --no-server mill.bsp.BSP/install

# Wire this clone to the language server the flake pins: build `zaozi-lsp` from the flake.lock revision into
# `.zed/ls` (gitignored; `.envrc` puts its bin on PATH, which is what the Zed extension resolves), and point the
# presentation-compiler island at the locally published `zaozi-compiler-plugin` jar. The two Zed-side steps that
# cannot live in a repository are printed at the end; run `just zaozi-publish` first on a fresh clone.
[group('editor')]
zed-setup:
    #!/usr/bin/env bash
    set -euo pipefail
    rev=$(nix eval --impure --raw --expr "(builtins.fromJSON (builtins.readFile \"$PWD/flake.lock\")).nodes.\"zaozi-lsp\".locked.rev")
    [ -n "$rev" ] || { echo "zed-setup: flake.lock carries no 'zaozi-lsp' input to pin" >&2; exit 1; }
    echo "zaozi-lsp revision (flake.lock): $rev"
    nix build "github:xinpian-tech/zaozi-lsp/$rev" -o .zed/ls
    .zed/ls/bin/zaozi-lsp --version
    jar=$(find "$HOME/.ivy2/local/me.jiuyang" -path '*/jars/zaozi-compiler-plugin_3.jar' 2>/dev/null | head -1 || true)
    if [ -n "${jar:-}" ]; then
      mkdir -p .zaozi-lsp
      printf '{ "compilerPlugins": [ { "jars": ["%s"], "options": [] } ], "servicePluginJars": [] }\n' "$jar" > .zaozi-lsp/pc-plugins.json
      echo "island PC plugin -> .zaozi-lsp/pc-plugins.json ($jar)"
    else
      echo "zed-setup: no locally published zaozi-compiler-plugin jar — run 'just zaozi-publish' and re-run" >&2
    fi
    if [ -f zaozi-lsp-zed/extension/extension.toml ]; then
      echo "installing the Zed extension from ./zaozi-lsp-zed (nix-zed-extensions build)"
      nix run ./zaozi-lsp-zed#install
    else
      echo "zed-setup: zaozi-lsp-zed/ submodule is empty — run 'git submodule update --init zaozi-lsp-zed'" >&2
    fi
    cat <<'EOF'

    Zed-side steps (once per machine, not expressible in a repository):
      zed: extensions                            # uninstall the `scala` (metals-zed) extension
      zed: reload extensions                     # after this recipe installs or updates the extension
      zed: restart language server               # after pulling changes to build.mill
    Run `direnv allow` after .envrc changes; the LSP and extension themselves are pinned by flake.lock and
    the zaozi-lsp-zed submodule, so a checkout only needs this recipe.
    EOF

# Elaborate RTL to Verilog. `just dump --target core --isa riscv32im [--outDir <dir>]` is what the simulators
# call, so the flags are passed through to the CLI (defaults live there); without --outDir the legacy layout
# build/<target>/<platform>/<isa>/<flow> is used.
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
